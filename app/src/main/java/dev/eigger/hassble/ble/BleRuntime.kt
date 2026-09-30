package dev.eigger.hassble.ble

import dev.eigger.hassble.service.BleGatewayService
import dev.eigger.hassble.config.AdvertiseCounterMode
import dev.eigger.hassble.config.AdvertisementInstanceMode
import dev.eigger.hassble.config.effectiveStateClass
import dev.eigger.hassble.config.BleScanModeOption
import dev.eigger.hassble.config.ConfigValidator
import dev.eigger.hassble.config.ControlAction
import dev.eigger.hassble.config.ControlConfig
import dev.eigger.hassble.config.DeviceConfig
import dev.eigger.hassble.config.GatewayConfig
import dev.eigger.hassble.config.PublishRule
import dev.eigger.hassble.config.SensorConfig
import dev.eigger.hassble.config.Source
import dev.eigger.hassble.config.SourceField
import dev.eigger.hassble.config.ValidationIssue
import dev.eigger.hassble.config.ValidationLevel
import dev.eigger.hassble.config.parseDurationMs
import dev.eigger.hassble.decode.Decoder
import dev.eigger.hassble.decode.ValueFilter
import dev.eigger.hassble.net.CommandPayload
import dev.eigger.hassble.net.DeviceRef
import dev.eigger.hassble.net.EntityMsg
import dev.eigger.hassble.net.HaWsClient
import dev.eigger.hassble.service.LiveEventLogger
import dev.eigger.hassble.service.LogType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * 앱의 두뇌. config + 사용자 선택(enabledKeys)으로:
 *  - 엔티티 선언(declare) → HA가 생성
 *  - BLE raw → 디코딩 → 값 필터(통신완화) → state push
 *  - HA command → config 매핑(hex) → BLE write
 *
 * advertisement 소스는 instance_mode(mac|shared)와 match.mac에 따라 인스턴스를 구분한다.
 */
class BleRuntime(
    private val scope: CoroutineScope,
    private val ws: HaWsClient,
    private val scanner: AdvertisementScanner,
    private val gatt: GattNotifySource,
    private val obd: Elm327Source,
    private val advertiser: BleAdvertiser? = null,
    private val onDiscoveredAdvChanged: (List<DiscoveredAdvInstance>) -> Unit = {},
    private val onSensorValuesChanged: (List<SensorLastValue>) -> Unit = {},
    private val onLinkDataReceived: (String, Long) -> Unit = { _, _ -> },
    private val onLinkStatus: (DeviceLinkStatus) -> Unit = {},
    private val onAdvCounterChanged: (String, Int) -> Unit = { _, _ -> },
    private val onAdvertisingChanged: (String, Boolean) -> Unit = { _, _ -> },
    /** 처리 오류 로그가 남을 때(새 종류 첫 발생·반복 N회마다). 서비스가 알림의 오류 건수를 갱신한다. */
    private val onPipelineError: () -> Unit = {},
) {
    private val json = Json { ignoreUnknownKeys = true }
    private var scanJob: Job? = null
    private val deviceConnectionJobs = java.util.concurrent.ConcurrentHashMap<String, Job>()

    private lateinit var config: GatewayConfig
    private lateinit var enabled: Set<String>                 // "deviceId/sensorKey"
    private var boundDevices: Map<String, String> = emptyMap() // Map of deviceId -> MAC
    private var scanMode: BleScanModeOption = BleScanModeOption.BALANCED
    private var autoConnectDisabledIds: Set<String> = emptySet()
    private var unfilteredScan: Boolean = false
    // 현재 스캔 세션의 필터에 들어간 재연결 대기 MAC. 바뀌면 세션을 다시 세운다.
    @Volatile private var lastWaitMacs: Set<String> = emptySet()
    private var advCounters: Map<String, Int> = emptyMap()

    // Cached states for change tracking between apply() calls
    private var lastConfig: GatewayConfig? = null
    private var lastEnabled: Set<String> = emptySet()
    private var lastBoundDevices: Map<String, String> = emptyMap()
    private var lastScanMode: BleScanModeOption? = null
    private var lastAutoConnectDisabledIds: Set<String> = emptySet()
    private var lastUnfilteredScan: Boolean? = null

    private val devices = java.util.concurrent.ConcurrentHashMap<String, DeviceConfig>()
    private val filters = java.util.concurrent.ConcurrentHashMap<String, ValueFilter>()  // uniqueId → filter
    // mode+pid 하나에 센서 여러 개가 붙을 수 있다 (예: 현대 22B002 → 오도미터·연료·전압)
    private val obdIndex = java.util.concurrent.ConcurrentHashMap<String, Map<Pair<String, String>, List<SensorConfig>>>()
    private val controls = java.util.concurrent.ConcurrentHashMap<String, Pair<DeviceConfig, ControlConfig>>()  // uniqueId →
    private val declaredAdvInstances = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val discoveredAdvInstances = java.util.concurrent.ConcurrentHashMap<String, DiscoveredAdvInstance>()
    // instanceId → presence 상태. lastSeen을 여기 같이 들고 있어 discoveredAdvInstances(UI용)가
    // 비워져도 off 판정이 가능하다. HA 전송은 online 전이 때만.
    private val advertisementPresence = java.util.concurrent.ConcurrentHashMap<String, AdvertisementPresence>()
    private var presenceJob: Job? = null
    // scanJob 교체는 항상 이 mutex 안에서 cancelAndJoin → scanner.stop() → launchScan() 순서로 한다.
    // apply()/restartScan()/stop()이 서로 다른 스레드에서 겹쳐도 세션이 둘이 되거나,
    // collect 중인 스캐너 캐시를 다른 쪽이 비우는 일이 없게.
    private val scanLifecycleMutex = Mutex()
    // 필터에 막힌 수신은 UI 목록 발행을 UI_REFRESH_MS 단위로 모은다. true면 예약된 발행이 있다.
    private val sensorUiPublishPending = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile private var stopped = false
    // ── 파이프라인 진단 ──────────────────────────────────────────────────────
    // 수신값/HA 명령 한 건의 예외가 수집(collect) 전체를 끝내 "연결됨인데 아무것도 처리 안 됨" 상태로
    // 게이트웨이 재시작 전까지 남던 문제가 있었다. 이제 건별로 격리하고, 그 사실과 처리량을 heartbeat에 남긴다.
    private val pipelineErrors = PipelineErrorLog(
        log = { LiveEventLogger.log(LogType.LINK, it) },
        onLogged = { onPipelineError() },
    )

    /** 지금까지 버린 수신값·명령 수(알림 표시용). */
    val pipelineErrorCount: Long get() = pipelineErrors.totalCount
    private val readingCount = java.util.concurrent.atomic.AtomicLong(0)
    @Volatile private var lastReadingMs = 0L
    private val commandCount = java.util.concurrent.atomic.AtomicLong(0)
    @Volatile private var lastCommandMs = 0L
    private val collectorRestarts = java.util.concurrent.atomic.AtomicLong(0)
    private var eventJob: Job? = null
    private val lastSensorValues = java.util.concurrent.ConcurrentHashMap<String, SensorLastValue>()
    private var validationIssues: List<ValidationIssue> = emptyList()
    // async HA cleanup 진행 중인 deviceId → 완료 전 apply()에서 재시작 방지
    private val pendingHaCleanupIds = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    fun start() {
        launchEventCollector()
    }

    /** HA 명령 수집. 명령 하나의 예외로 수집이 끝나면 이후 HA 버튼(주차위치 요청 등)이 전부 무시됐다. */
    private fun launchEventCollector() {
        val job = ws.events.onEach(::safeOnEvent).launchIn(scope)
        eventJob = job
        watchCollector(job, "HA command collector", relaunchOnNormalEnd = true) { launchEventCollector() }
    }

    /**
     * collector가 취소가 아닌 이유로 끝나면 로그를 남기고 [relaunch]로 다시 띄운다. 건별 격리로
     * 끝날 일이 없어야 하지만, 끝났을 때 조용히 멈춰 있지 않게 하는 마지막 안전망이다.
     */
    private fun watchCollector(job: Job, name: String, relaunchOnNormalEnd: Boolean, relaunch: () -> Unit) {
        job.invokeOnCompletion { cause ->
            if (stopped || cause is CancellationException) return@invokeOnCompletion
            // 기기 연결 flow는 auto_connect가 꺼져 있으면 정상 종료한다 — 그건 의도된 끝이다.
            if (cause == null && !relaunchOnNormalEnd) return@invokeOnCompletion
            collectorRestarts.incrementAndGet()
            LiveEventLogger.log(LogType.LINK,
                "[Error] $name ended unexpectedly (${cause ?: "completed"}) — relaunching in ${COLLECTOR_RELAUNCH_DELAY_MS / 1000}s")
            scope.launch {
                delay(COLLECTOR_RELAUNCH_DELAY_MS)
                if (!stopped) relaunch()
            }
        }
    }

    private fun safeOnEvent(event: JsonObject) {
        try {
            onEvent(event)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            pipelineErrors.record("HA command handling", e, detail = "event=$event")
        }
    }

    private fun safeOnReading(r: RawReading) {
        readingCount.incrementAndGet()
        lastReadingMs = System.currentTimeMillis()
        try {
            onReading(r)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            pipelineErrors.record("reading handling (device=${r.deviceId}, source=${r.source})", e)
        }
    }

    /** heartbeat 로그용 한 줄 요약. */
    fun diagnostics(nowMs: Long = System.currentTimeMillis()): String {
        fun age(ms: Long) = if (ms == 0L) "never" else "${(nowMs - ms) / 1000}s ago"
        return "readings=${readingCount.get()} (last ${age(lastReadingMs)}), " +
            "haCommands=${commandCount.get()} (last ${age(lastCommandMs)}), " +
            "pipelineErrors=${pipelineErrors.totalCount}, collectorRestarts=${collectorRestarts.get()}, " +
            "scanCollector=${if (scanJob?.isActive == true) "active" else "inactive"}, " +
            "commandCollector=${if (eventJob?.isActive == true) "active" else "inactive"}"
    }

    fun redeclareEntities() {
        if (!::config.isInitialized) return
        declaredAdvInstances.clear()
        for (d in config.devices) {
            declareAndPrepare(d)
        }
    }

    private fun runConfigValidation(config: GatewayConfig) {
        val issues = ConfigValidator.validate(config)
        validationIssues = issues
        if (issues.isEmpty()) return
        val errors = issues.count { it.level == ValidationLevel.ERROR }
        val warnings = issues.count { it.level == ValidationLevel.WARNING }
        LiveEventLogger.log(LogType.LINK, "=== Config Validation: $errors error(s), $warnings warning(s) ===")
        for (issue in issues) {
            LiveEventLogger.log(LogType.LINK, issue.toString())
        }
        if (errors > 0) {
            LiveEventLogger.log(LogType.LINK, "=== ${errors} sensor(s)/control(s) will be disabled due to errors ===")
        }
    }

    /** 설정 적용 + 사용자가 켠 센서로 엔티티 선언 + BLE 기동. */
    fun apply(
        config: GatewayConfig,
        enabledKeys: Set<String>,
        boundDevices: Map<String, String>,
        scanMode: BleScanModeOption = BleScanModeOption.BALANCED,
        autoConnectDisabledIds: Set<String> = emptySet(),
        unfilteredScan: Boolean = false,
        advCounters: Map<String, Int> = emptyMap(),
    ) {
        val oldConfig = this.lastConfig
        val oldEnabled = this.lastEnabled
        val oldBoundDevices = this.lastBoundDevices
        val oldScanMode = this.lastScanMode
        val oldAutoConnectDisabledIds = this.lastAutoConnectDisabledIds
        val oldUnfilteredScan = this.lastUnfilteredScan

        if (config != oldConfig) runConfigValidation(config)

        this.config = config
        this.enabled = enabledKeys
        this.boundDevices = boundDevices
        this.scanMode = scanMode
        this.autoConnectDisabledIds = autoConnectDisabledIds
        this.unfilteredScan = unfilteredScan
        this.advCounters = advCounters

        if (oldConfig == null) {
            // First run: complete initialization
            // 캐시 파라미터를 return 전에 반드시 업데이트해야 한다.
            // 업데이트하지 않으면 settingsJob이 apply()를 재호출할 때마다
            // oldConfig==null로 판단해 startSources()를 반복 호출하고,
            // 진행 중인 연결 Job이 계속 취소·재시작된다.
            this.lastConfig = config
            this.lastEnabled = enabledKeys
            this.lastBoundDevices = boundDevices
            this.lastScanMode = scanMode
            this.lastAutoConnectDisabledIds = autoConnectDisabledIds
            this.lastUnfilteredScan = unfilteredScan
            this.lastWaitMacs = ConnectionWaitMacs.of(config, boundDevices, autoConnectDisabledIds)

            devices.clear(); filters.clear(); obdIndex.clear(); controls.clear()
            declaredAdvInstances.clear()
            discoveredAdvInstances.clear()
            lastSensorValues.clear()
            publishDiscoveredAdv()
            publishSensorValues()

            startSources()
            return
        }

        // --- Active scan (Advertisement) job dynamic detection ---
        val newAdvDevices = config.devices.filter { it.source == Source.advertisement }
        val oldAdvDevices = oldConfig.devices.filter { it.source == Source.advertisement }

        val newWaitMacs = ConnectionWaitMacs.of(config, boundDevices, autoConnectDisabledIds)
        val advChanged = (scanMode != oldScanMode && newAdvDevices.isNotEmpty()) ||
                newWaitMacs != lastWaitMacs ||
                unfilteredScan != oldUnfilteredScan ||
                newAdvDevices.size != oldAdvDevices.size ||
                newAdvDevices.zip(oldAdvDevices).any { (newD, oldD) -> newD != oldD }

        if (advChanged) {
            // launchScan()이 돌기 전에 apply()가 다시 불려도 같은 변경으로 세션을 또 세우지 않게 미리 기록한다.
            lastWaitMacs = newWaitMacs
            relaunchScan("config changed")
        }

        // --- Connection devices and advertisement devices change detection ---
        val currentDeviceIds = config.devices.map { it.id }.toSet()
        val oldDeviceIds = oldConfig.devices.map { it.id }.toSet()

        // 1. Remove deleted devices
        val deletedIds = oldDeviceIds - currentDeviceIds
        for (id in deletedIds) {
            val mode = haRemoveModeForDevice(oldConfig.devices.firstOrNull { it.id == id })
            stopDevice(id)
            scope.launch {
                runCatching { ws.removeDevice(id, mode) }
            }
        }

        // 2. Add or Update existing devices
        for (d in config.devices) {
            val deviceId = d.id
            val isNewDevice = deviceId !in oldDeviceIds

            // Check if any parameters or states for this device changed
            val oldD = oldConfig.devices.firstOrNull { it.id == deviceId }
            val configChanged = oldD != d
            val boundMacChanged = boundDevices[deviceId] != oldBoundDevices[deviceId]
            val autoConnectChanged = (deviceId in autoConnectDisabledIds) != (deviceId in oldAutoConnectDisabledIds)

            // Check if active sensors key set for this device changed
            val newEnabledKeys = enabledKeys.filter { it.startsWith("$deviceId/") }.toSet()
            val oldEnabledKeys = oldEnabled.filter { it.startsWith("$deviceId/") }.toSet()
            val sensorsChanged = newEnabledKeys != oldEnabledKeys

            val needsRestart = isNewDevice || configChanged || boundMacChanged || autoConnectChanged || sensorsChanged

            if (needsRestart) {
                // async HA cleanup이 진행 중인 device는 그 완료를 기다리지 않고 skip
                // (이전 async 블록이 구 config로 startDevice를 호출하는 것을 방지)
                if (deviceId in pendingHaCleanupIds) continue
                stopDevice(deviceId)
                val needsHaCleanup = configChanged && oldD != null &&
                        ConfigValidator.hasSensorStructureChange(oldD, d)
                if (needsHaCleanup) {
                    // 센서 platform/type이 바뀐 경우: HA 구 엔티티를 먼저 삭제 후 재선언
                    val capturedD = d
                    val cleanupMode = haRemoveModeForDevice(capturedD)
                    pendingHaCleanupIds.add(capturedD.id)
                    scope.launch {
                        try {
                            runCatching { ws.removeDevice(capturedD.id, cleanupMode) }
                            // cleanup이 진행되는 동안 기기가 삭제/제외됐다면 재기동하지 않는다.
                            if (lastConfig?.devices?.any { it.id == capturedD.id } == true) {
                                startDevice(capturedD)
                            }
                        } finally {
                            pendingHaCleanupIds.remove(capturedD.id)
                        }
                    }
                } else {
                    // MAC이 새로 바인딩된 경우: 광고 스캔 없이 즉시 연결 시도
                    val justBound = boundMacChanged && d.source != Source.advertisement
                    startDevice(d, forceConnect = justBound)
                }
            }
        }

        // Cache parameters for next call
        this.lastConfig = config
        this.lastEnabled = enabledKeys
        this.lastBoundDevices = boundDevices
        this.lastScanMode = scanMode
        this.lastAutoConnectDisabledIds = autoConnectDisabledIds
        this.lastUnfilteredScan = unfilteredScan
    }

    private fun declareAndPrepare(d: DeviceConfig) {
        // match.mac 없는 광고 프로필은 첫 패킷 수신 시 MAC별로 동적 선언
        if (isDynamicAdvertisement(d)) return
        declareEntitiesForInstance(d, d.id, d.name)
    }

    private fun declareEntitiesForInstance(d: DeviceConfig, instanceId: String, deviceDisplayName: String) {
        val ref = DeviceRef(instanceId, deviceDisplayName)

        if (d.source == Source.obd || d.source == Source.gatt_notify) {
            ws.declareEntity(EntityMsg(
                id = 0, uniqueId = "${instanceId}_link_status", platform = "binary_sensor",
                name = "Link Status", device = ref,
                deviceClass = "connectivity",
                entityCategory = "diagnostic"
            ))
            val currentStatus = BleGatewayService.deviceLinkStatuses.value.firstOrNull { it.profileId == instanceId }
            val isConnected = currentStatus?.state == DeviceLinkState.Connected || currentStatus?.state == DeviceLinkState.Polling
            ws.sendStates(listOf("${instanceId}_link_status" to if (isConnected) "on" else "off"))
        }

        if (d.advertise != null) {
            ws.declareEntity(EntityMsg(
                id = 0, uniqueId = "${instanceId}_advertising", platform = "binary_sensor",
                name = "Advertising", device = ref,
                entityCategory = "diagnostic",
            ))
            val isAdv = advertiser?.isAdvertising(d.id) == true
            ws.sendStates(listOf("${instanceId}_advertising" to if (isAdv) "on" else "off"))
        }

        if (d.source == Source.advertisement && presenceTimeoutMs(d) > 0) {
            // 광고가 끊겨도 센서 엔티티는 마지막 값을 유지한다. 대신 "지금 수신 중인가"를 이 엔티티가
            // 알려 주고, HA 자동화는 off→on 전이를 재발견 트리거로 쓸 수 있다.
            ws.declareEntity(EntityMsg(
                id = 0, uniqueId = "${instanceId}_advertisement", platform = "binary_sensor",
                name = "Advertisement", device = ref,
                deviceClass = "connectivity", entityCategory = "diagnostic",
            ))
            val now = System.currentTimeMillis()
            val lastSeen = advertisementPresence[instanceId]?.lastSeenMs?.takeIf { it > 0 } ?: latestSeenMs(instanceId)
            val online = lastSeen != null && now - lastSeen < presenceTimeoutMs(d)
            advertisementPresence[instanceId] = AdvertisementPresence(online, lastSeen ?: 0L)
            ws.sendStates(listOf("${instanceId}_advertisement" to if (online) "on" else "off"))
        }

        val errorKeys = ConfigValidator.errorKeys(validationIssues, d.id)
        for (s in d.sensors) {
            if (!isEnabled(d.id, s.key)) continue
            if (s.key in errorKeys) continue
            val entityUid = uid(instanceId, s.key)
            filters[entityUid] = ValueFilter(resolveRule(d, s))
            // text_sensor·event는 문자열 상태라 숫자 메타(unit/state_class/precision)를 쓰지 않는다.
            val isNumeric = s.platform != "text_sensor" && s.platform != "event"
            ws.declareEntity(EntityMsg(
                id = 0, uniqueId = entityUid, platform = haPlatform(s),
                name = title(s.key), device = ref,
                deviceClass = s.deviceClass,
                unit = if (isNumeric) s.unit else null,
                stateClass = if (isNumeric) s.effectiveStateClass() else null,
                suggestedDisplayPrecision = if (isNumeric) s.accuracyDecimals else null,
                icon = s.icon,
                entityCategory = s.entityCategory,
                eventTypes = s.eventTypes.ifEmpty { null },
            ))
        }
        for (c in d.controls) {
            if (c.key in errorKeys) continue
            if (d.source == Source.gatt_notify && d.gatt?.writeCharUuid.isNullOrBlank()) continue
            if (d.source == Source.obd && d.obd?.txCharUuid.isNullOrBlank()) continue
            val entityUid = uid(instanceId, c.key)
            controls[entityUid] = d to c
            ws.declareEntity(EntityMsg(
                id = 0, uniqueId = entityUid, platform = c.type.name,
                name = c.name ?: title(c.key), device = ref,
                icon = c.icon, entityCategory = c.entityCategory,
                options = c.options.ifEmpty { null },
                min = c.min, max = c.max, step = c.step,
            ))
        }
    }

    private fun ensureAdvertisementInstance(d: DeviceConfig, mac: String, deviceName: String?) {
        val instanceId = advertisementInstanceId(d, mac)
        if (instanceId in declaredAdvInstances) return
        declaredAdvInstances.add(instanceId)
        val label = deviceName?.takeIf { it.isNotBlank() }?.let { "$it ($mac)" }
            ?: "${d.name} ($mac)"
        declareEntitiesForInstance(d, instanceId, label)
        ws.sendAvailability(instanceId, true)
    }

    private fun stopDevice(deviceId: String) {
        deviceConnectionJobs[deviceId]?.cancel()
        deviceConnectionJobs.remove(deviceId)
        advertiser?.stop(deviceId, AdvertiseStopReason.Shutdown)

        val d = devices[deviceId]
        if (d != null) {
            when (d.source) {
                Source.gatt_notify -> gatt.disconnect(deviceId)
                Source.obd -> obd.disconnect(deviceId)
                else -> {}
            }
        } else {
            gatt.disconnect(deviceId)
            obd.disconnect(deviceId)
        }

        devices.remove(deviceId)
        obdIndex.remove(deviceId)

        val controlKeysToRemove = controls.keys.filter { it.startsWith("${deviceId}_") }
        controlKeysToRemove.forEach { controls.remove(it) }

        val filterKeysToRemove = filters.keys.filter { it.startsWith("${deviceId}_") }
        filterKeysToRemove.forEach { filters.remove(it) }

        val sensorKeysToRemove = lastSensorValues.keys.filter { it.startsWith("${deviceId}_") || lastSensorValues[it]?.profileId == deviceId }
        sensorKeysToRemove.forEach { lastSensorValues.remove(it) }
        publishSensorValues()

        val advKeysToRemove = discoveredAdvInstances.keys.filter { it.startsWith("$deviceId|") || discoveredAdvInstances[it]?.profileId == deviceId }
        advKeysToRemove.forEach { discoveredAdvInstances.remove(it) }
        publishDiscoveredAdv()

        declaredAdvInstances.removeAll { belongsToDevice(it, deviceId) }
        advertisementPresence.keys.removeAll { belongsToDevice(it, deviceId) }

        val mac = boundDevices[deviceId] ?: ""
        onLinkStatus(DeviceLinkStatus(deviceId, DeviceLinkState.Disconnected, mac))
    }

    private fun startDevice(d: DeviceConfig, forceConnect: Boolean = false) {
        devices[d.id] = d
        if (d.source == Source.obd) {
            val errKeys = ConfigValidator.errorKeys(validationIssues, d.id)
            obdIndex[d.id] = d.sensors.filter { it.pid != null && it.key !in errKeys }
                .groupBy { it.mode to it.pid!!.uppercase() }
        }
        declareAndPrepare(d)

        val resolved = resolveDeviceMac(d)
        val job = scope.launch {
            val selfJob = coroutineContext[kotlinx.coroutines.Job]
            val oldJob = deviceConnectionJobs[resolved.id]
            if (oldJob != null && oldJob !== selfJob && oldJob.isActive) {
                LiveEventLogger.log(LogType.LINK, "device=${resolved.id}: cancelling previous active job before reconnecting")
                oldJob.cancelAndJoin()
            }

            when (resolved.source) {
                Source.gatt_notify -> {
                    val mac = resolved.gatt?.mac
                    if (!mac.isNullOrBlank() && (forceConnect || d.id !in autoConnectDisabledIds)) {
                        val keys = resolved.sensors.map { it.key }.filter { isEnabled(resolved.id, it) }.toSet()
                        if (keys.isNotEmpty()) {
                            val autoReconnect = d.id !in autoConnectDisabledIds
                            var skipScan = forceConnect  // 첫 수동 연결만 스캔 생략, 이후 재연결은 항상 광고 대기
                            gatt.connect(
                                resolved,
                                waitForDevice = {
                                if (skipScan) {
                                    skipScan = false
                                    onLinkStatus(DeviceLinkStatus(resolved.id, DeviceLinkState.Connecting, mac))
                                    LiveEventLogger.log(LogType.LINK, "device=${resolved.id}: direct GATT connect (manual) $mac")
                                } else {
                                    onLinkStatus(DeviceLinkStatus(resolved.id, DeviceLinkState.Scanning, mac))
                                    LiveEventLogger.log(LogType.LINK, "device=${resolved.id}: waiting for advertisement from $mac")
                                    // 재연결 대기용 별도 스캔은 없다. 이 MAC은 메인 스캔의 필터(waitMacs)에 이미
                                    // 들어 있어, 그 스캔이 광고를 보면 여기로 신호가 온다.
                                    scanner.scanForMac(mac).first()
                                }
                            }, autoReconnect = autoReconnect).collect { reading ->
                                safeOnReading(reading)
                            }
                        } else {
                            onLinkStatus(DeviceLinkStatus(resolved.id, DeviceLinkState.Disconnected, mac))
                        }
                    }
                }
                Source.obd -> {
                    val mac = resolved.obd?.mac
                    if (!mac.isNullOrBlank() && (forceConnect || d.id !in autoConnectDisabledIds)) {
                        val keys = resolved.sensors.map { it.key }.filter { isEnabled(resolved.id, it) }.toSet()
                        if (keys.isNotEmpty()) {
                            val autoReconnect = d.id !in autoConnectDisabledIds
                            var skipScan = forceConnect  // 첫 수동 연결만 스캔 생략, 이후 재연결은 광고 대기
                            obd.connect(
                                resolved,
                                keys,
                                waitForDevice = {
                                if (skipScan) {
                                    skipScan = false
                                    onLinkStatus(DeviceLinkStatus(resolved.id, DeviceLinkState.Connecting, mac))
                                    LiveEventLogger.log(LogType.LINK, "device=${resolved.id}: direct OBD connect (manual) $mac")
                                } else {
                                    onLinkStatus(DeviceLinkStatus(resolved.id, DeviceLinkState.Scanning, mac))
                                    LiveEventLogger.log(LogType.LINK, "device=${resolved.id}: waiting for advertisement from $mac")
                                    // 위 GATT notify 경로와 같다: 메인 스캔이 광고를 보면 신호가 온다.
                                    scanner.scanForMac(mac).first()
                                }
                            }, autoReconnect = autoReconnect).collect { reading ->
                                safeOnReading(reading)
                            }
                        } else {
                            onLinkStatus(DeviceLinkStatus(resolved.id, DeviceLinkState.Disconnected, mac))
                        }
                    }
                }
                else -> {}
            }
        }
        deviceConnectionJobs[resolved.id] = job
        // 수신값 처리는 건별로 격리돼 있지만, 그래도 Error 등이 연결 flow를 끝내면 그 기기는 재시작 전까지
        // 끊긴 채로 남는다. 스캔 collector와 같은 안전망을 둔다. 교체·삭제된 기기면 건드리지 않는다.
        watchCollector(job, "device=${resolved.id} connection", relaunchOnNormalEnd = false) {
            if (deviceConnectionJobs[resolved.id] === job && devices[d.id] != null) startDevice(d)
        }
    }

    private fun startSources() {
        // 첫 기동도 같은 mutex를 탄다 — 직후 BT ON restartScan()과 겹쳐도 세션이 둘이 되지 않게.
        relaunchScan("initial start")
        for (d in config.devices) {
            startDevice(d)
        }
    }

    private fun launchScan() {
        val adv = config.devices.filter { it.source == Source.advertisement }
        val waitMacs = ConnectionWaitMacs.of(config, boundDevices, autoConnectDisabledIds)
        lastWaitMacs = waitMacs
        if (adv.isNotEmpty() || waitMacs.isNotEmpty()) {
            // 재연결 대기 MAC만 있으면 지연이 중요하지 않다(차가 켜져야 광고가 뜨는 건 분·시간 단위). 주차 내내
            // 사용자의 실시간 scanMode로 돌면 배터리를 갉아먹으니 LOW_POWER로 돌린다.
            val mode = if (adv.isEmpty()) BleScanModeOption.LOW_POWER else scanMode
            val job = scanner.scan(adv, mode, unfilteredScan, waitMacs).onEach(::safeOnReading).launchIn(scope)
            scanJob = job
            watchCollector(job, "BLE scan collector", relaunchOnNormalEnd = true) {
                // 교체된 옛 세션이면 건드리지 않는다(새 세션은 이미 떠 있다).
                if (scanJob === job) relaunchScan("scan collector ended unexpectedly")
            }
        }
        startPresenceWatcher()
    }

    /**
     * 광고 스캔 세션을 밖에서 강제로 다시 세운다. Bluetooth OFF→ON처럼 스캐너가 콜백 없이 죽는
     * 사건을 서비스가 감지했을 때 호출한다. 스캐너 내부 watchdog도 같은 일을 하지만 최대 60초가
     * 걸리므로, 사건을 아는 쪽에서 바로 깨우는 편이 빠르다.
     */
    fun restartScan(reason: String) {
        if (!::config.isInitialized) return
        if (config.devices.none { it.source == Source.advertisement } &&
            ConnectionWaitMacs.of(config, boundDevices, autoConnectDisabledIds).isEmpty()) return
        LiveEventLogger.log(LogType.LINK, "BLE scan restart requested: $reason")
        relaunchScan(reason)
    }

    /** [apply]가 한 번이라도 실행돼 connectDevice 등을 받을 수 있는 상태인가. */
    val isConfigured: Boolean get() = ::config.isInitialized

    /** 현재 세션을 취소 완료까지 기다린 뒤 새 세션을 띄운다. 연달아 불려도 mutex로 한 번에 하나씩. */
    private fun relaunchScan(reason: String) {
        scope.launch {
            scanLifecycleMutex.withLock {
                scanJob?.cancelAndJoin()
                scanJob = null
                scanner.stop()
                // stop() 이후에 도착한 재시작 요청은 무시한다 — destroy 중에 세션을 다시 세우지 않게.
                if (stopped) return@withLock
                launchScan()
                // stop()은 mutex 밖에서 플래그만 세우므로, 위 검사와 launchScan() 사이에 끼어든 경우를
                // 한 번 더 닫는다. 이 시점에 stopped면 방금 띄운 세션이 마지막이라 여기서 거둔다.
                if (stopped) {
                    scanJob?.cancel()
                    scanJob = null
                    presenceJob?.cancel()
                    presenceJob = null
                }
            }
        }
    }

    // ── advertisement presence ────────────────────────────────────────────────
    private fun presenceTimeoutMs(d: DeviceConfig): Long = parseDurationMs(d.presenceTimeout, 0)

    /** instanceId에 속한 모든 MAC(shared 모드면 여러 개) 중 가장 최근 수신 시각. */
    private fun latestSeenMs(instanceId: String): Long? =
        discoveredAdvInstances.values.filter { it.instanceId == instanceId }.maxOfOrNull { it.lastSeenMs }

    /** instanceId가 속한 프로필. 동적 인스턴스는 `{id}_{MAC}` 꼴이라 belongsToDevice와 같은 규칙으로 찾는다. */
    private fun profileForInstance(instanceId: String): DeviceConfig? =
        devices[instanceId] ?: devices.values.firstOrNull { belongsToDevice(instanceId, it.id) }

    private fun startPresenceWatcher() {
        presenceJob?.cancel()
        presenceJob = scope.launch {
            while (isActive) {
                delay(PRESENCE_TICK_MS)
                // 한 바퀴의 예외로 감시 루프가 끝나면 이후 광고가 끊겨도 off가 영영 안 나간다. 바퀴 단위로 격리한다.
                try {
                    checkPresenceOnce()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    pipelineErrors.record("advertisement presence check", e)
                }
            }
        }
    }

    private fun checkPresenceOnce() {
        val now = System.currentTimeMillis()
        val out = mutableListOf<Pair<String, String>>()
        for ((instanceId, state) in advertisementPresence) {
            if (!state.online) continue
            // 프로필이 사라졌으면(설정 삭제) 더 볼 것 없이 off. 타임아웃은 프로필에서 읽는다.
            val timeout = profileForInstance(instanceId)?.let { presenceTimeoutMs(it) } ?: 0L
            if (timeout > 0 && now - state.lastSeenMs < timeout) continue
            if (!advertisementPresence.replace(instanceId, state, state.copy(online = false))) continue
            out += "${instanceId}_advertisement" to "off"
            LiveEventLogger.log(LogType.LINK,
                "device=$instanceId: advertisement lost (off) — no packets for ${(now - state.lastSeenMs) / 1000}s, keeping last sensor values")
        }
        if (out.isNotEmpty()) ws.sendStates(out)
    }

    private fun markAdvertisementSeen(d: DeviceConfig, instanceId: String) {
        if (presenceTimeoutMs(d) <= 0) return
        val now = System.currentTimeMillis()
        val previous = advertisementPresence.put(instanceId, AdvertisementPresence(true, now))
        if (previous?.online == true) return
        ws.sendStates(listOf("${instanceId}_advertisement" to "on"))
        LiveEventLogger.log(LogType.LINK, "device=$instanceId: advertisement present (on)")
    }

    private fun resolveDeviceMac(d: DeviceConfig): DeviceConfig {
        val localMac = boundDevices[d.id]
        return if (!localMac.isNullOrBlank()) {
            when (d.source) {
                Source.gatt_notify -> d.copy(gatt = d.gatt?.copy(mac = localMac))
                Source.obd -> d.copy(obd = d.obd?.copy(mac = localMac))
                else -> d
            }
        } else {
            d
        }
    }

    // ── BLE raw → 디코딩 → 필터 → push ──────────────────────────────────────
    private fun onReading(r: RawReading) {
        val d = devices[r.deviceId] ?: return
        val logType = when (d.source) {
            Source.advertisement -> LogType.ADV
            Source.obd, Source.gatt_notify -> LogType.NOTIF
            else -> LogType.ADV
        }
        // ADV 타입은 includeAdvLogs가 꺼져 있으면(기본값) log()가 버리므로, 버릴 문자열을
        // 광고 패킷마다(주행 중엔 초당 수십 건) 조립하는 비용부터 건너뛴다.
        if (logType != LogType.ADV || LiveEventLogger.includeAdvLogs) {
            val info = buildString {
                append("device=${d.id}")
                r.macAddress?.let { append(", mac=$it") }
                r.deviceName?.let { append(", name=$it") }
                if (d.source == Source.advertisement) {
                    r.manufacturerHex?.let { append(", mfg=$it") }
                    r.serviceDataHex?.let { append(", svc=$it") }
                    r.isConnectable?.let { append(", connectable=$it") }
                } else {
                    append(", raw=${r.rawHex}")
                }
            }
            LiveEventLogger.log(logType, info)
        }

        val out = mutableListOf<Pair<String, Any>>()

        when (d.source) {
            Source.advertisement -> {
                val mac = r.macAddress ?: return
                if (d.advertise?.stopOnResponse == true && advertiser?.isAdvertising(d.id) == true) {
                    advertiser.stop(d.id, AdvertiseStopReason.ResponseReceived)
                }
                val instanceId = advertisementInstanceId(d, mac)
                // 선언보다 먼저 기록해야 동적 인스턴스의 첫 선언에서 presence가 바로 on으로 나간다.
                recordAdvertisementSeen(d, mac, r.deviceName, instanceId, r)
                if (isDynamicAdvertisement(d)) {
                    ensureAdvertisementInstance(d, mac, r.deviceName)
                }
                markAdvertisementSeen(d, instanceId)
                for (s in d.sensors) {
                    if (!isEnabled(d.id, s.key) || s.decode == null) continue
                    val bytes = advertisementPayloadBytes(r, s.sourceField) ?: continue
                    if (s.length != null) {
                        if (bytes.size != s.length) continue
                    } else {
                        val minLen = s.minLength ?: (s.decode.offset + s.decode.length)
                        if (bytes.size < minLen) continue
                    }
                    emit(d, instanceId, s, Decoder.decodeStructured(bytes, s.decode), out)
                }
            }
            Source.obd -> {
                val (mode, pid, data) = Decoder.parseObdResponse(r.rawHex) ?: return
                val sensors = obdIndex[d.id]?.get(mode to pid) ?: return
                var matched = false
                for (s in sensors) {
                    if (!isEnabled(d.id, s.key)) continue
                    if (s.decode == null && s.formula == null) continue
                    if (!matched) {
                        onLinkDataReceived(d.id, System.currentTimeMillis())
                        matched = true
                    }
                    // decode가 있으면 우선한다. formula 변수는 응답의 앞 20바이트까지만
                    // 가리킬 수 있어서, 그 뒤에 있는 값은 offset으로만 읽을 수 있다.
                    // 응답이 짧아 범위를 벗어나면 decodeStructured가 null을 주고,
                    // emit이 그것을 버리므로 그 주기에는 아무것도 발행되지 않는다.
                    val value = if (s.decode != null) {
                        Decoder.decodeStructured(data, s.decode)
                    } else {
                        runCatching { Decoder.evalFormula(s.formula!!, data) }.getOrNull()
                    }
                    emit(d, d.id, s, value, out)
                }
            }
            Source.gatt_notify -> {
                val bytes = Decoder.hexToBytes(r.rawHex) ?: return
                onLinkDataReceived(d.id, System.currentTimeMillis())
                for (s in d.sensors) {
                    if (!isEnabled(d.id, s.key) || s.decode == null) continue
                    if (s.length != null) {
                        if (bytes.size != s.length) continue
                    } else {
                        val minLen = s.minLength ?: (s.decode.offset + s.decode.length)
                        if (bytes.size < minLen) continue
                    }
                    emit(d, d.id, s, Decoder.decodeStructured(bytes, s.decode), out)
                }
            }
        }
        ws.sendStates(out)
    }

    private fun advertisementPayloadBytes(r: RawReading, field: SourceField): ByteArray? {
        val hex = when (field) {
            SourceField.manufacturer_data -> r.manufacturerHex
            SourceField.service_data -> r.serviceDataHex
            SourceField.raw -> r.fullScanHex
        } ?: r.rawHex
        return Decoder.hexToBytes(hex)
    }

    private fun emit(
        d: DeviceConfig,
        instanceId: String,
        s: SensorConfig,
        value: Any?,
        out: MutableList<Pair<String, Any>>,
    ) {
        if (value == null) return
        // 쓰레기 바이트를 float로 디코딩하면 NaN/Infinity가 나온다. HA에 보낼 수 없고, 아래 반올림에서
        // "NaN".toLong()이 예외를 던진다.
        if ((value is Double && !value.isFinite()) || (value is Float && !value.isFinite())) return
        // 반올림 문자열은 다시 숫자로 파싱하므로 소수점이 쉼표인 로케일(de, fr 등)에서도 깨지지 않게 ROOT.
        val rounded: Any = if (s.accuracyDecimals != null && value is Double) {
            if (s.accuracyDecimals == 0) "%.0f".format(java.util.Locale.ROOT, value).toLong()
            else "%.${s.accuracyDecimals}f".format(java.util.Locale.ROOT, value).toDouble()
        } else value
        val entityUid = uid(instanceId, s.key)
        val filter = filters[entityUid] ?: return  // not declared (validation error or not enabled)
        val published = filter.allow(rounded)
        if (published) out += entityUid to rounded
        // 필터에 막혀도 수신 시각은 남긴다. 예전엔 전송될 때만 기록해서, 같은 값이 계속 들어오면
        // 화면이 "N분 전"에 멈춰 수신이 끊긴 것처럼 보였다.
        recordSensorValue(d.id, instanceId, s.key, rounded, s.unit, s.accuracyDecimals, published)
    }

    // ── HA command → BLE write ──────────────────────────────────────────────
    private fun onEvent(event: JsonObject) {
        if (event["kind"]?.jsonPrimitive?.content != "command") return
        val cmd = json.decodeFromJsonElement(CommandPayload.serializer(), event)
        commandCount.incrementAndGet()
        lastCommandMs = System.currentTimeMillis()
        val (d, c) = controls[cmd.uniqueId] ?: run {
            // 예전엔 조용히 버렸다. HA 버튼을 눌렀는데 아무 일도 없을 때 여기서 걸렸는지 로그로 가릴 수 있게 한다.
            LiveEventLogger.log(LogType.LINK,
                "HA command ignored: no control registered for unique_id=${cmd.uniqueId} (action=${cmd.action})")
            return
        }
        LiveEventLogger.log(LogType.LINK, "HA command: device=${d.id}, control=${c.key}, action=${cmd.action}")
        when (c.action) {
            ControlAction.advertise -> {
                when (cmd.action) {
                    "press", "turn_on" -> startAdvertise(d)
                    "turn_off" -> stopAdvertise(d.id)
                }
                return
            }
            ControlAction.stop_advertise -> {
                stopAdvertise(d.id)
                return
            }
            null -> {}
        }
        val prim = cmd.value as? JsonPrimitive
        val hex = when (cmd.action) {
            "turn_on" -> c.command["on"]
            "turn_off" -> c.command["off"]
            "set_value" -> c.command["template"]?.let { formatCommand(it, prim?.doubleOrNull ?: 0.0) }
            "select_option" -> prim?.contentOrNull?.let { c.command[it] }
            "press" -> c.command["press"]
            else -> null
        } ?: return
        LiveEventLogger.log(LogType.TX, "BLE Write: device=${d.id}, action=${cmd.action}, hex=$hex")
        scope.launch {
            when (d.source) {
                Source.gatt_notify -> gatt.write(d, hex)
                Source.obd -> obd.write(d, hex)
                else -> {}
            }
        }
    }

    /**
     * 기기 삭제 시 config 재로드(네트워크)를 기다리지 않고 즉시 BLE 연결/스캔 상태를 정리한다.
     * HA 엔티티 제거는 호출 측에서 [haRemoveModeForDeviceId]와 [dev.eigger.hassble.net.HaWsClient.removeDevice]로 처리한다.
     */
    fun stopDeviceNow(deviceId: String) {
        if (!::config.isInitialized) return
        stopDevice(deviceId)
    }

    /** 삭제·HA 정리 요청 전에 호출 — [devices]가 비워지기 전에 remove mode를 결정한다. */
    fun haRemoveModeForDeviceId(deviceId: String) = haRemoveModeForDevice(
        devices[deviceId]
            ?: if (::config.isInitialized) config.devices.firstOrNull { it.id == deviceId } else null
            ?: lastConfig?.devices?.firstOrNull { it.id == deviceId },
    )

    /** 게이트웨이 실행 중 특정 기기를 수동으로 연결 시작. */
    fun connectDevice(deviceId: String) {
        if (!::config.isInitialized) return
        val d = devices[deviceId] ?: config.devices.firstOrNull { it.id == deviceId } ?: return
        // 수동 연결: 기존 job(스캔 대기 포함)을 취소하고 강제 재연결
        stopDevice(deviceId)
        startDevice(d, forceConnect = true)
    }

    /** 게이트웨이 실행 중 특정 기기를 수동으로 연결 해제. */
    fun disconnectDevice(deviceId: String) {
        deviceConnectionJobs[deviceId]?.cancel()
        deviceConnectionJobs.remove(deviceId)
        gatt.disconnect(deviceId)
        obd.disconnect(deviceId)
        val d = devices[deviceId]
        val mac = when (d?.source) {
            Source.gatt_notify -> resolveDeviceMac(d).gatt?.mac
            Source.obd -> resolveDeviceMac(d).obd?.mac
            else -> boundDevices[deviceId]
        }
        onLinkStatus(DeviceLinkStatus(deviceId, DeviceLinkState.Disconnected, mac))
    }

    private fun startAdvertise(d: DeviceConfig) {
        val advConfig = d.advertise ?: return
        if (ConfigValidator.hasDeviceError(validationIssues, d.id)) return
        resetPublishFilters(d)
        val requestStartMs = System.currentTimeMillis()
        val readingsAtStart = readingCount.get()
        val seed = when (advConfig.counterMode) {
            AdvertiseCounterMode.reset -> advConfig.counterStart and 0xFF
            AdvertiseCounterMode.persist -> {
                val current = advCounters[d.id]
                if (current != null) AdvertisePayload.nextCounter(current) else (advConfig.counterStart and 0xFF)
            }
        }
        val started = advertiser?.start(
            deviceId = d.id,
            config = advConfig,
            counterSeed = seed,
            onCounter = { newCounter ->
                if (advConfig.counterMode == AdvertiseCounterMode.persist) {
                    advCounters = advCounters + (d.id to newCounter)
                    onAdvCounterChanged(d.id, newCounter)
                }
            },
            onStopped = { reason ->
                publishAdvertisingState(d, false)
                if (reason == AdvertiseStopReason.Timeout && d.source == Source.advertisement &&
                    !gotResponseSince(d, requestStartMs)
                ) {
                    logNoResponseDiagnostics(d, readingCount.get() - readingsAtStart)
                }
            },
        ) ?: false
        if (started) {
            publishAdvertisingState(d, true)
        }
    }

    /** [sinceMs] 이후 이 프로필의 인스턴스 중 하나라도 광고를 받았는가. */
    private fun gotResponseSince(d: DeviceConfig, sinceMs: Long): Boolean =
        InstanceIds.of(d.id, declaredAdvInstances).any { (latestSeenMs(it) ?: 0L) >= sinceMs }

    /**
     * 요청이 응답 없이 끝났을 때 원인을 가릴 수 있는 스냅샷을 남긴다: 스캐너가 살아 있는지, 요청 동안
     * 수신이 있었는지, 무선을 나눠 쓰는 GATT/OBD 연결이 있었는지, 필터 없는 스캔인지.
     */
    private fun logNoResponseDiagnostics(d: DeviceConfig, readingsDuringRequest: Long) {
        val links = deviceConnectionJobs.filterValues { it.isActive }.keys.joinToString().ifEmpty { "none" }
        LiveEventLogger.log(LogType.LINK,
            "device=${d.id}: request ended with no response — readings during request=$readingsDuringRequest, " +
                "active connections=[$links], unfilteredScan=$unfilteredScan, ${BleScanHealth.state.value.describe()}")
    }

    private fun publishAdvertisingState(d: DeviceConfig, isAdvertising: Boolean) {
        onAdvertisingChanged(d.id, isAdvertising)
        val stateStr = if (isAdvertising) "on" else "off"
        val targetInstanceIds = if (isDynamicAdvertisement(d)) {
            declaredAdvInstances.filter { belongsToDevice(it, d.id) }.ifEmpty { listOf(d.id) }
        } else {
            listOf(d.id)
        }
        ws.sendStates(targetInstanceIds.map { "${it}_advertising" to stateStr })
    }

    /** 게이트웨이 실행 중 특정 기기의 BLE 광고 송신을 시작. */
    fun triggerAdvertise(deviceId: String) {
        if (!::config.isInitialized) return
        val d = devices[deviceId] ?: config.devices.firstOrNull { it.id == deviceId } ?: return
        startAdvertise(d)
    }

    /** 게이트웨이 실행 중 특정 기기의 BLE 광고 송신을 중단. */
    fun stopAdvertise(deviceId: String) {
        advertiser?.stop(deviceId, AdvertiseStopReason.Manual)
    }

    fun isAdvertising(deviceId: String): Boolean = advertiser?.isAdvertising(deviceId) == true

    fun stop() {
        // relaunchScan()이 대기 중이어도 launchScan()으로 넘어가지 못하게 먼저 막는다.
        stopped = true
        eventJob?.cancel()
        eventJob = null
        scanJob?.cancel()
        scanJob = null
        presenceJob?.cancel()
        presenceJob = null
        advertisementPresence.clear()
        deviceConnectionJobs.values.forEach { it.cancel() }
        deviceConnectionJobs.clear()

        if (::config.isInitialized) {
            for (d in config.devices) {
                when (d.source) {
                    Source.gatt_notify -> gatt.disconnect(d.id)
                    Source.obd -> obd.disconnect(d.id)
                    else -> {}
                }
            }
        }

        scanner.stop()
        advertiser?.stopAll()
        discoveredAdvInstances.clear()
        publishDiscoveredAdv()
        lastSensorValues.clear()
        publishSensorValues()

        pendingHaCleanupIds.clear()
        lastConfig = null
        lastEnabled = emptySet()
        lastBoundDevices = emptyMap()
        lastScanMode = null
        lastAutoConnectDisabledIds = emptySet()
        lastUnfilteredScan = null
    }

    private fun recordSensorValue(
        profileId: String,
        instanceId: String,
        sensorKey: String,
        value: Any,
        unit: String?,
        accuracyDecimals: Int?,
        published: Boolean,
    ) {
        val entityUid = uid(instanceId, sensorKey)
        lastSensorValues[entityUid] = SensorLastValue.next(
            prev = lastSensorValues[entityUid],
            profileId = profileId,
            instanceId = instanceId,
            sensorKey = sensorKey,
            display = formatDisplayValue(value, unit, accuracyDecimals),
            published = published,
            nowMs = System.currentTimeMillis(),
        )
        if (published) {
            publishSensorValues()
        } else {
            scheduleSensorUiPublish()
        }
    }

    /**
     * HA에 전송되지 않은 수신은 초당 수십 건이라 매번 목록 전체를 정렬·발행하지 않는다. 센서가 몇 개든
     * UI_REFRESH_MS에 한 번만 발행하고, 대기 중 들어온 수신은 그 한 번에 모두 반영된다(마지막 값도 빠짐없이).
     * 전송된 값은 recordSensorValue가 즉시 발행한다.
     */
    private fun scheduleSensorUiPublish() {
        if (!sensorUiPublishPending.compareAndSet(false, true)) return
        scope.launch {
            delay(UI_REFRESH_MS)
            sensorUiPublishPending.set(false)
            publishSensorValues()
        }
    }

    private fun formatDisplayValue(value: Any, unit: String?, accuracyDecimals: Int?): String {
        val str = when (value) {
            is Double -> when {
                accuracyDecimals != null -> "%.${accuracyDecimals}f".format(value)
                value == value.toLong().toDouble() -> value.toLong().toString()
                else -> value.toString()
            }
            is Float -> when {
                accuracyDecimals != null -> "%.${accuracyDecimals}f".format(value)
                value == value.toLong().toFloat() -> value.toLong().toString()
                else -> value.toString()
            }
            else -> value.toString()
        }
        return if (unit.isNullOrBlank()) str else "$str $unit"
    }

    private fun publishSensorValues() {
        onSensorValuesChanged(
            lastSensorValues.values.sortedWith(
                compareBy({ it.profileId }, { it.instanceId }, { it.sensorKey }),
            ),
        )
    }

    private fun recordAdvertisementSeen(
        d: DeviceConfig,
        mac: String,
        deviceName: String?,
        instanceId: String,
        reading: RawReading,
    ) {
        val key = "${d.id}|${normalizeMac(mac)}"
        discoveredAdvInstances[key] = DiscoveredAdvInstance(
            profileId = d.id,
            mac = mac,
            deviceName = deviceName?.takeIf { it.isNotBlank() },
            instanceId = instanceId,
            lastSeenMs = System.currentTimeMillis(),
            manufacturerHex = reading.manufacturerHex,
            serviceDataHex = reading.serviceDataHex,
        )
        publishDiscoveredAdv()
    }

    private fun publishDiscoveredAdv() {
        onDiscoveredAdvChanged(
            discoveredAdvInstances.values.sortedWith(compareBy({ it.profileId }, { it.mac })),
        )
    }

    // ── helpers ──────────────────────────────────────────────────────────────
    private fun usesMacInstances(d: DeviceConfig) =
        d.source == Source.advertisement && d.instanceMode == AdvertisementInstanceMode.mac

    private fun isDynamicAdvertisement(d: DeviceConfig) =
        usesMacInstances(d) && d.match?.mac.isNullOrBlank()

    private fun advertisementInstanceId(d: DeviceConfig, mac: String): String {
        if (!usesMacInstances(d)) return d.id
        if (!d.match?.mac.isNullOrBlank()) return d.id
        return "${d.id}_${normalizeMac(mac)}"
    }

    private fun normalizeMac(mac: String) = mac.replace(":", "").replace("-", "").uppercase()

    /**
     * instanceId가 이 프로필의 것인가. 동적 인스턴스는 `{id}_{12자리 MAC}`이므로 접두사만 보면
     * `car`를 지울 때 `car_park_…`까지 걸린다. 접두사 뒤가 정확히 MAC일 때만 같은 프로필로 본다.
     */
    private fun belongsToDevice(instanceId: String, deviceId: String): Boolean =
        InstanceIds.belongsTo(instanceId, deviceId)

    private fun isEnabled(deviceId: String, key: String) = "$deviceId/$key" in enabled
    private fun uid(deviceId: String, key: String) = "${deviceId}_$key"
    private fun title(key: String) = key.replace('_', ' ').replaceFirstChar { it.uppercase() }

    private fun resolveRule(d: DeviceConfig, s: SensorConfig): PublishRule =
        s.publish ?: d.publish ?: config.defaults.publish

    private fun haPlatform(s: SensorConfig): String = when (s.platform) {
        "text_sensor" -> "sensor"
        else -> s.platform
    }

    private fun formatCommand(template: String, value: Double): String =
        Regex("""\{value(?::([^}]+))?\}""").replace(template) { m ->
            val fmt = m.groupValues[1]
            if (fmt.isEmpty()) value.toInt().toString() else String.format("%$fmt", value.toInt())
        }

    /**
     * 요청(광고 송신) 직전에 이 기기 센서들의 발행 필터를 비운다. 주차위치처럼 요청→응답인 기기는
     * 같은 자리에 다시 주차하면 응답이 이전 값과 같아 on_change_only에 막혀 HA에 아무것도 안 갔다.
     * 게이트웨이를 재시작하면 필터가 새로 만들어져 "재시작하니 잡힌다"로 보이던 원인이다.
     */
    private fun resetPublishFilters(d: DeviceConfig) {
        var count = 0
        for (instanceId in InstanceIds.of(d.id, declaredAdvInstances)) {
            for (s in d.sensors) {
                filters[uid(instanceId, s.key)]?.let { it.reset(); count++ }
            }
        }
        if (count > 0) {
            LiveEventLogger.log(LogType.TX, "device=${d.id}: publish filters reset for request ($count sensor(s)) — next response is always sent")
        }
    }

    /** `{instanceId}_advertisement`의 현재 상태와 마지막 수신 시각. */
    private data class AdvertisementPresence(val online: Boolean, val lastSeenMs: Long)

    companion object {
        private const val PRESENCE_TICK_MS = 10_000L
        private const val UI_REFRESH_MS = 1_000L
        private const val COLLECTOR_RELAUNCH_DELAY_MS = 5_000L
    }
}

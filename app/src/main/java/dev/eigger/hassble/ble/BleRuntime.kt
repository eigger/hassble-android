package dev.eigger.hassble.ble

import dev.eigger.hassble.service.BleGatewayService
import dev.eigger.hassble.config.AdvertisementInstanceMode
import dev.eigger.hassble.config.effectiveStateClass
import dev.eigger.hassble.config.BleScanModeOption
import dev.eigger.hassble.config.ConfigValidator
import dev.eigger.hassble.config.ControlConfig
import dev.eigger.hassble.config.DeviceConfig
import dev.eigger.hassble.config.GatewayConfig
import dev.eigger.hassble.config.PublishRule
import dev.eigger.hassble.config.SensorConfig
import dev.eigger.hassble.config.Source
import dev.eigger.hassble.config.SourceField
import dev.eigger.hassble.config.ValidationIssue
import dev.eigger.hassble.config.ValidationLevel
import dev.eigger.hassble.decode.Decoder
import dev.eigger.hassble.decode.ValueFilter
import dev.eigger.hassble.net.CommandPayload
import dev.eigger.hassble.net.DeviceRef
import dev.eigger.hassble.net.EntityMsg
import dev.eigger.hassble.net.HaWsClient
import dev.eigger.hassble.service.LiveEventLogger
import dev.eigger.hassble.service.LogType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
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
    private val onDiscoveredAdvChanged: (List<DiscoveredAdvInstance>) -> Unit = {},
    private val onSensorValuesChanged: (List<SensorLastValue>) -> Unit = {},
    private val onLinkDataReceived: (String, Long) -> Unit = { _, _ -> },
    private val onLinkStatus: (DeviceLinkStatus) -> Unit = {},
) {
    private val json = Json { ignoreUnknownKeys = true }
    private var scanJob: Job? = null
    private val deviceConnectionJobs = java.util.concurrent.ConcurrentHashMap<String, Job>()

    private lateinit var config: GatewayConfig
    private lateinit var enabled: Set<String>                 // "deviceId/sensorKey"
    private var boundDevices: Map<String, String> = emptyMap() // Map of deviceId -> MAC
    private var scanMode: BleScanModeOption = BleScanModeOption.BALANCED
    private var autoConnectDisabledIds: Set<String> = emptySet()

    // Cached states for change tracking between apply() calls
    private var lastConfig: GatewayConfig? = null
    private var lastEnabled: Set<String> = emptySet()
    private var lastBoundDevices: Map<String, String> = emptyMap()
    private var lastScanMode: BleScanModeOption? = null
    private var lastAutoConnectDisabledIds: Set<String> = emptySet()

    private val devices = java.util.concurrent.ConcurrentHashMap<String, DeviceConfig>()
    private val filters = java.util.concurrent.ConcurrentHashMap<String, ValueFilter>()  // uniqueId → filter
    private val obdIndex = java.util.concurrent.ConcurrentHashMap<String, Map<Pair<String, String>, SensorConfig>>()
    private val controls = java.util.concurrent.ConcurrentHashMap<String, Pair<DeviceConfig, ControlConfig>>()  // uniqueId →
    private val declaredAdvInstances = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val discoveredAdvInstances = java.util.concurrent.ConcurrentHashMap<String, DiscoveredAdvInstance>()
    private val lastSensorValues = java.util.concurrent.ConcurrentHashMap<String, SensorLastValue>()
    private var validationIssues: List<ValidationIssue> = emptyList()
    // async HA cleanup 진행 중인 deviceId → 완료 전 apply()에서 재시작 방지
    private val pendingHaCleanupIds = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    fun start() {
        ws.events.onEach(::onEvent).launchIn(scope)
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
        autoConnectDisabledIds: Set<String> = emptySet()
    ) {
        val oldConfig = this.lastConfig
        val oldEnabled = this.lastEnabled
        val oldBoundDevices = this.lastBoundDevices
        val oldScanMode = this.lastScanMode
        val oldAutoConnectDisabledIds = this.lastAutoConnectDisabledIds

        if (config != oldConfig) runConfigValidation(config)

        this.config = config
        this.enabled = enabledKeys
        this.boundDevices = boundDevices
        this.scanMode = scanMode
        this.autoConnectDisabledIds = autoConnectDisabledIds

        if (oldConfig == null) {
            // First run: complete initialization
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

        val advChanged = scanMode != oldScanMode ||
                newAdvDevices.size != oldAdvDevices.size ||
                newAdvDevices.zip(oldAdvDevices).any { (newD, oldD) -> newD != oldD }

        if (advChanged) {
            scanJob?.cancel()
            scanJob = null
            scanner.stop()
            if (newAdvDevices.isNotEmpty()) {
                scanJob = scanner.scan(newAdvDevices, scanMode).onEach(::onReading).launchIn(scope)
            }
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
                    startDevice(d)
                }
            }
        }

        // Cache parameters for next call
        this.lastConfig = config
        this.lastEnabled = enabledKeys
        this.lastBoundDevices = boundDevices
        this.lastScanMode = scanMode
        this.lastAutoConnectDisabledIds = autoConnectDisabledIds
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

        val errorKeys = ConfigValidator.errorKeys(validationIssues, d.id)
        for (s in d.sensors) {
            if (!isEnabled(d.id, s.key)) continue
            if (s.key in errorKeys) continue
            val entityUid = uid(instanceId, s.key)
            filters[entityUid] = ValueFilter(resolveRule(d, s))
            val isTextSensor = s.platform == "text_sensor"
            ws.declareEntity(EntityMsg(
                id = 0, uniqueId = entityUid, platform = haPlatform(s),
                name = title(s.key), device = ref,
                deviceClass = s.deviceClass,
                unit = if (isTextSensor) null else s.unit,
                stateClass = if (isTextSensor) null else s.effectiveStateClass(),
                suggestedDisplayPrecision = if (isTextSensor) null else s.accuracyDecimals,
                icon = s.icon,
                entityCategory = s.entityCategory,
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

        declaredAdvInstances.removeAll { it == deviceId || it.startsWith("${deviceId}_") }

        val mac = boundDevices[deviceId] ?: ""
        onLinkStatus(DeviceLinkStatus(deviceId, DeviceLinkState.Disconnected, mac))
    }

    private fun startDevice(d: DeviceConfig, forceConnect: Boolean = false) {
        devices[d.id] = d
        if (d.source == Source.obd) {
            val errKeys = ConfigValidator.errorKeys(validationIssues, d.id)
            obdIndex[d.id] = d.sensors.filter { it.pid != null && it.key !in errKeys }
                .associateBy { it.mode to it.pid!!.uppercase() }
        }
        declareAndPrepare(d)

        val resolved = resolveDeviceMac(d)
        val job = scope.launch {
            val oldJob = deviceConnectionJobs[resolved.id]
            if (oldJob != null && oldJob.isActive) {
                LiveEventLogger.log(LogType.LINK, "device=${resolved.id}: cancelling previous active job before reconnecting")
                oldJob.cancelAndJoin()
            }

            when (resolved.source) {
                Source.gatt_notify -> {
                    val mac = resolved.gatt?.mac
                    if (!mac.isNullOrBlank() && (forceConnect || d.id !in autoConnectDisabledIds)) {
                        val keys = resolved.sensors.map { it.key }.filter { isEnabled(resolved.id, it) }.toSet()
                        if (keys.isNotEmpty()) {
                            gatt.connect(resolved, waitForDevice = {
                                onLinkStatus(DeviceLinkStatus(resolved.id, DeviceLinkState.Scanning, mac))
                                LiveEventLogger.log(LogType.LINK, "device=${resolved.id}: scanning for advertisement from $mac")
                                scanner.scanForMac(mac, scanMode).first()
                                LiveEventLogger.log(LogType.LINK, "device=${resolved.id}: advertisement received, connecting")
                            }).collect { reading ->
                                onReading(reading)
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
                            // ELM327은 바인딩 후 광고를 멈추는 경우가 많으므로 광고 스캔 없이 GATT 직접 연결
                            obd.connect(resolved, keys, waitForDevice = {
                                LiveEventLogger.log(LogType.LINK, "device=${resolved.id}: connecting to OBD at $mac")
                            }).collect { reading ->
                                onReading(reading)
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
    }

    private fun startSources() {
        val adv = config.devices.filter { it.source == Source.advertisement }
        if (adv.isNotEmpty()) {
            scanJob = scanner.scan(adv, scanMode).onEach(::onReading).launchIn(scope)
        }
        for (d in config.devices) {
            startDevice(d)
        }
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

        val out = mutableListOf<Pair<String, Any>>()

        when (d.source) {
            Source.advertisement -> {
                val mac = r.macAddress ?: return
                if (isDynamicAdvertisement(d)) {
                    ensureAdvertisementInstance(d, mac, r.deviceName)
                }
                val instanceId = advertisementInstanceId(d, mac)
                recordAdvertisementSeen(d, mac, r.deviceName, instanceId, r)
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
                val s = obdIndex[d.id]?.get(mode to pid) ?: return
                if (!isEnabled(d.id, s.key) || s.formula == null) return
                onLinkDataReceived(d.id, System.currentTimeMillis())
                emit(d, d.id, s, runCatching { Decoder.evalFormula(s.formula, data) }.getOrNull(), out)
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
        val rounded: Any = if (s.accuracyDecimals != null && value is Double) {
            if (s.accuracyDecimals == 0) "%.0f".format(value).toLong()
            else "%.${s.accuracyDecimals}f".format(value).toDouble()
        } else value
        val entityUid = uid(instanceId, s.key)
        val filter = filters[entityUid] ?: return  // not declared (validation error or not enabled)
        if (filter.allow(rounded) != false) {
            out += entityUid to rounded
            recordSensorValue(d.id, instanceId, s.key, rounded, s.unit, s.accuracyDecimals)
        }
    }

    // ── HA command → BLE write ──────────────────────────────────────────────
    private fun onEvent(event: JsonObject) {
        if (event["kind"]?.jsonPrimitive?.content != "command") return
        val cmd = json.decodeFromJsonElement(CommandPayload.serializer(), event)
        val (d, c) = controls[cmd.uniqueId] ?: return
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
        if (deviceConnectionJobs[deviceId]?.isActive == true) return
        val d = devices[deviceId] ?: config.devices.firstOrNull { it.id == deviceId } ?: return
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

    fun stop() {
        scanJob?.cancel()
        scanJob = null
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
    }

    private fun recordSensorValue(
        profileId: String,
        instanceId: String,
        sensorKey: String,
        value: Any,
        unit: String?,
        accuracyDecimals: Int?,
    ) {
        val entityUid = uid(instanceId, sensorKey)
        lastSensorValues[entityUid] = SensorLastValue(
            profileId = profileId,
            instanceId = instanceId,
            sensorKey = sensorKey,
            value = formatDisplayValue(value, unit, accuracyDecimals),
            updatedAtMs = System.currentTimeMillis(),
        )
        publishSensorValues()
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
}

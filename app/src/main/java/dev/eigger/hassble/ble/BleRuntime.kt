package dev.eigger.hassble.ble

import dev.eigger.hassble.config.AdvertisementInstanceMode
import dev.eigger.hassble.config.BleScanModeOption
import dev.eigger.hassble.config.ControlConfig
import dev.eigger.hassble.config.DeviceConfig
import dev.eigger.hassble.config.GatewayConfig
import dev.eigger.hassble.config.PublishRule
import dev.eigger.hassble.config.SensorConfig
import dev.eigger.hassble.config.Source
import dev.eigger.hassble.config.SourceField
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
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val jobs = mutableListOf<Job>()

    private lateinit var config: GatewayConfig
    private lateinit var enabled: Set<String>                 // "deviceId/sensorKey"
    private var boundDevices: Map<String, String> = emptyMap() // Map of deviceId -> MAC
    private var scanMode: BleScanModeOption = BleScanModeOption.BALANCED
    private var disabledIds: Set<String> = emptySet()
    private var autoConnectDisabledIds: Set<String> = emptySet()
    private val devices = mutableMapOf<String, DeviceConfig>()
    private val filters = mutableMapOf<String, ValueFilter>()  // uniqueId → filter
    private val obdIndex = mutableMapOf<String, Map<Pair<String, String>, SensorConfig>>()
    private val controls = mutableMapOf<String, Pair<DeviceConfig, ControlConfig>>()  // uniqueId →
    private val declaredAdvInstances = mutableSetOf<String>()
    private val discoveredAdvInstances = mutableMapOf<String, DiscoveredAdvInstance>()
    private val lastSensorValues = mutableMapOf<String, SensorLastValue>()

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

    /** 설정 적용 + 사용자가 켠 센서로 엔티티 선언 + BLE 기동. */
    fun apply(config: GatewayConfig, enabledKeys: Set<String>, boundDevices: Map<String, String>, scanMode: BleScanModeOption = BleScanModeOption.BALANCED, disabledIds: Set<String> = emptySet(), autoConnectDisabledIds: Set<String> = emptySet()) {
        this.config = config
        this.enabled = enabledKeys
        this.boundDevices = boundDevices
        this.scanMode = scanMode
        this.disabledIds = disabledIds
        this.autoConnectDisabledIds = autoConnectDisabledIds
        jobs.forEach { it.cancel() }; jobs.clear(); scanner.stop()
        devices.clear(); filters.clear(); obdIndex.clear(); controls.clear()
        declaredAdvInstances.clear()
        discoveredAdvInstances.clear()
        lastSensorValues.clear()
        publishDiscoveredAdv()
        publishSensorValues()

        for (d in config.devices) {
            devices[d.id] = d
            if (d.source == Source.obd) {
                obdIndex[d.id] = d.sensors.filter { it.pid != null }
                    .associateBy { it.mode to it.pid!!.uppercase() }
            }
            declareAndPrepare(d)
        }
        startSources()
    }

    private fun declareAndPrepare(d: DeviceConfig) {
        if (d.id in disabledIds) return
        // match.mac 없는 광고 프로필은 첫 패킷 수신 시 MAC별로 동적 선언
        if (isDynamicAdvertisement(d)) return
        declareEntitiesForInstance(d, d.id, d.name)
    }

    private fun declareEntitiesForInstance(d: DeviceConfig, instanceId: String, deviceDisplayName: String) {
        val ref = DeviceRef(instanceId, deviceDisplayName)
        val newSensorUids = mutableListOf<String>()
        for (s in d.sensors) {
            if (!isEnabled(d.id, s.key)) continue
            val entityUid = uid(instanceId, s.key)
            filters[entityUid] = ValueFilter(resolveRule(d, s))
            ws.declareEntity(EntityMsg(
                id = 0, uniqueId = entityUid, platform = haPlatform(s),
                name = title(s.key), device = ref,
                deviceClass = s.deviceClass, unit = s.unit,
                stateClass = s.stateClass, icon = s.icon,
                entityCategory = s.entityCategory,
            ))
            newSensorUids += entityUid
        }
        ws.sendInitialStates(newSensorUids)
        for (c in d.controls) {
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

    private fun startSources() {
        val adv = config.devices.filter { it.source == Source.advertisement && it.id !in disabledIds }
        if (adv.isNotEmpty()) jobs += scanner.scan(adv, scanMode).onEach(::onReading).launchIn(scope)
        for (d in config.devices) {
            val resolved = resolveDeviceMac(d)
            when (resolved.source) {
                Source.gatt_notify -> {
                    val mac = resolved.gatt?.mac
                    if (!mac.isNullOrBlank() && d.id !in autoConnectDisabledIds) {
                        jobs += gatt.connect(resolved).onEach(::onReading).launchIn(scope)
                    }
                }
                Source.obd -> {
                    val mac = resolved.obd?.mac
                    if (!mac.isNullOrBlank() && d.id !in autoConnectDisabledIds) {
                        val keys = resolved.sensors.map { it.key }.filter { isEnabled(resolved.id, it) }.toSet()
                        jobs += obd.connect(resolved, keys).onEach(::onReading).launchIn(scope)
                    }
                }
                else -> {}
            }
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
        if (d.id in disabledIds) return
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
                    val minLen = s.minLength ?: (s.decode.offset + s.decode.length)
                    if (bytes.size < minLen) continue
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
                    val minLen = s.minLength ?: (s.decode.offset + s.decode.length)
                    if (bytes.size < minLen) continue
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
        val rounded = if (s.accuracyDecimals != null && value is Double)
            "%.${s.accuracyDecimals}f".format(value).toDouble() else value
        val entityUid = uid(instanceId, s.key)
        val filter = filters.getOrPut(entityUid) { ValueFilter(resolveRule(d, s)) }
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

    fun stop() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        scanner.stop()
        discoveredAdvInstances.clear()
        publishDiscoveredAdv()
        lastSensorValues.clear()
        publishSensorValues()
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

    fun entityUniqueIdsForDevice(deviceId: String): List<String> {
        val d = devices[deviceId] ?: return emptyList()
        val instanceIds = if (isDynamicAdvertisement(d)) {
            discoveredAdvInstances.values.filter { it.profileId == deviceId }.map { it.instanceId } + listOf(deviceId)
        } else {
            listOf(d.id)
        }
        return instanceIds.flatMap { instanceId ->
            d.sensors.map { uid(instanceId, it.key) } + d.controls.map { uid(instanceId, it.key) }
        }.distinct()
    }

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

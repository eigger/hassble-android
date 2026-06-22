package dev.eigger.hassble.ble

import dev.eigger.hassble.config.ControlConfig
import dev.eigger.hassble.config.DeviceConfig
import dev.eigger.hassble.config.GatewayConfig
import dev.eigger.hassble.config.PublishRule
import dev.eigger.hassble.config.SensorConfig
import dev.eigger.hassble.config.Source
import dev.eigger.hassble.decode.Decoder
import dev.eigger.hassble.decode.ValueFilter
import dev.eigger.hassble.net.CommandPayload
import dev.eigger.hassble.net.DeviceRef
import dev.eigger.hassble.net.EntityMsg
import dev.eigger.hassble.net.HaWsClient
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
 */
class BleRuntime(
    private val scope: CoroutineScope,
    private val ws: HaWsClient,
    private val scanner: AdvertisementScanner,
    private val gatt: GattNotifySource,
    private val obd: Elm327Source,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val jobs = mutableListOf<Job>()

    private lateinit var config: GatewayConfig
    private lateinit var enabled: Set<String>                 // "deviceId/sensorKey"
    private var boundDevices: Map<String, String> = emptyMap() // Map of deviceId -> MAC
    private val devices = mutableMapOf<String, DeviceConfig>()
    private val filters = mutableMapOf<String, ValueFilter>()  // uniqueId → filter
    private val obdIndex = mutableMapOf<String, Map<Pair<String, String>, SensorConfig>>()
    private val controls = mutableMapOf<String, Pair<DeviceConfig, ControlConfig>>()  // uniqueId →

    fun start() {
        ws.events.onEach(::onEvent).launchIn(scope)
    }

    /** 설정 적용 + 사용자가 켠 센서로 엔티티 선언 + BLE 기동. */
    fun apply(config: GatewayConfig, enabledKeys: Set<String>, boundDevices: Map<String, String>) {
        this.config = config
        this.enabled = enabledKeys
        this.boundDevices = boundDevices
        jobs.forEach { it.cancel() }; jobs.clear(); scanner.stop()
        devices.clear(); filters.clear(); obdIndex.clear(); controls.clear()

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
        val ref = DeviceRef(d.id, d.name)
        for (s in d.sensors) {
            if (!isEnabled(d.id, s.key)) continue
            val uid = uid(d.id, s.key)
            filters[uid] = ValueFilter(resolveRule(d, s))
            ws.declareEntity(EntityMsg(
                id = 0, uniqueId = uid, platform = s.platform,   // sensor | binary_sensor
                name = title(s.key), device = ref,
                deviceClass = s.deviceClass, unit = s.unit,
                stateClass = s.stateClass, icon = s.icon,
                entityCategory = s.entityCategory,
            ))
        }
        for (c in d.controls) {
            val uid = uid(d.id, c.key)
            controls[uid] = d to c
            ws.declareEntity(EntityMsg(
                id = 0, uniqueId = uid, platform = c.type.name,  // switch|number|select|button
                name = c.name ?: title(c.key), device = ref,
                icon = c.icon, entityCategory = c.entityCategory,
                options = c.options.ifEmpty { null },
                min = c.min, max = c.max, step = c.step,
            ))
        }
    }

    private fun startSources() {
        val adv = config.devices.filter { it.source == Source.advertisement }
        if (adv.isNotEmpty()) jobs += scanner.scan(adv).onEach(::onReading).launchIn(scope)
        for (d in config.devices) {
            val resolved = resolveDeviceMac(d)
            when (resolved.source) {
                Source.gatt_notify -> {
                    val mac = resolved.gatt?.mac
                    if (!mac.isNullOrBlank()) {
                        jobs += gatt.connect(resolved).onEach(::onReading).launchIn(scope)
                    }
                }
                Source.obd -> {
                    val mac = resolved.obd?.mac
                    if (!mac.isNullOrBlank()) {
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
        val bytes = Decoder.hexToBytes(r.rawHex) ?: return
        val out = mutableListOf<Pair<String, Any>>()

        if (d.source == Source.obd) {
            val (mode, pid, data) = Decoder.parseObdResponse(r.rawHex) ?: return
            val s = obdIndex[d.id]?.get(mode to pid) ?: return
            if (!isEnabled(d.id, s.key) || s.formula == null) return
            emit(d, s, runCatching { Decoder.evalFormula(s.formula, data) }.getOrNull(), out)
        } else {
            for (s in d.sensors) {
                if (!isEnabled(d.id, s.key) || s.decode == null) continue
                emit(d, s, Decoder.decodeStructured(bytes, s.decode), out)
            }
        }
        ws.sendStates(out)
    }

    private fun emit(d: DeviceConfig, s: SensorConfig, value: Any?, out: MutableList<Pair<String, Any>>) {
        if (value == null) return
        val rounded = if (s.accuracyDecimals != null && value is Double)
            "%.${s.accuracyDecimals}f".format(value).toDouble() else value
        val uid = uid(d.id, s.key)
        if (filters[uid]?.allow(rounded) != false) out += uid to rounded
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
            "select_option" -> prim?.contentOrNull?.let { c.command[it] }   // 선택 옵션 → hex
            "press" -> c.command["press"]
            else -> null
        } ?: return
        scope.launch {
            when (d.source) {
                Source.gatt_notify -> gatt.write(d, hex)
                Source.obd -> obd.write(d, hex)
                else -> {}
            }
        }
    }

    fun stop() { jobs.forEach { it.cancel() }; jobs.clear(); scanner.stop() }

    // ── helpers ──────────────────────────────────────────────────────────────
    private fun isEnabled(deviceId: String, key: String) = "$deviceId/$key" in enabled
    private fun uid(deviceId: String, key: String) = "${deviceId}_$key"
    private fun title(key: String) = key.replace('_', ' ').replaceFirstChar { it.uppercase() }

    private fun resolveRule(d: DeviceConfig, s: SensorConfig): PublishRule =
        s.publish ?: d.publish ?: config.defaults.publish

    private fun formatCommand(template: String, value: Double): String =
        Regex("""\{value(?::([^}]+))?\}""").replace(template) { m ->
            val fmt = m.groupValues[1]
            if (fmt.isEmpty()) value.toInt().toString() else String.format("%$fmt", value.toInt())
        }
}

package dev.eigger.hassble.config

import com.charleskorn.kaml.Yaml
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 내장 OBD preset DB (assets/obd_presets.yaml). git 설정의 `preset:` 참조를
 * mode/pid/formula/단위로 펼친다. ESPHome ble_elm327 preset과 동일 개념.
 */
class ObdPresetStore(private val presets: Map<String, ObdPreset>) {

    fun presetNames(): List<String> = presets.keys.sorted()

    fun expand(config: GatewayConfig): GatewayConfig {
        val devices = config.devices.map { device ->
            if (device.source != Source.obd) return@map device
            device.copy(sensors = device.sensors.map(::expandSensor))
        }
        return config.copy(devices = devices)
    }

    fun expandDevice(device: DeviceConfig): DeviceConfig =
        expand(GatewayConfig(devices = listOf(device))).devices.first()

    private fun expandSensor(s: SensorConfig): SensorConfig {
        val p = s.preset?.let { presets[it] ?: error("알 수 없는 preset: $it") } ?: return s
        return s.copy(
            mode = if (s.pid == null) p.mode else s.mode,
            pid = s.pid ?: p.pid,
            formula = s.formula ?: p.formula,
            unit = s.unit ?: p.unit,
            deviceClass = s.deviceClass ?: p.deviceClass,
            stateClass = s.stateClass ?: p.stateClass,
            icon = s.icon ?: p.icon,
            accuracyDecimals = s.accuracyDecimals ?: p.accuracyDecimals,
        )
    }

    companion object {
        fun fromYaml(text: String): ObdPresetStore =
            ObdPresetStore(Yaml.default.decodeFromString(PresetFile.serializer(), text).presets)
    }
}

@Serializable
data class PresetFile(val presets: Map<String, ObdPreset>)

@Serializable
data class ObdPreset(
    val mode: String = "01",
    val pid: String,
    val formula: String,
    val unit: String? = null,
    @SerialName("device_class") val deviceClass: String? = null,
    @SerialName("state_class") val stateClass: String? = null,
    val icon: String? = null,
    @SerialName("accuracy_decimals") val accuracyDecimals: Int? = null,
)

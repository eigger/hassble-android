package dev.eigger.hassble.config

object ObdDeviceBuilder {

    fun create(
        id: String,
        name: String,
        presetKeys: List<String>,
        serviceUuid: String = "FFF0",
        txCharUuid: String = "FFF2",
        rxCharUuid: String = "FFF1",
    ): DeviceConfig {
        require(id.isNotBlank()) { "device id required" }
        require(name.isNotBlank()) { "device name required" }
        require(presetKeys.isNotEmpty()) { "at least one preset required" }
        return DeviceConfig(
            id = id.trim(),
            name = name.trim(),
            source = Source.obd,
            obd = ObdConfig(
                serviceUuid = serviceUuid.trim(),
                txCharUuid = txCharUuid.trim(),
                rxCharUuid = rxCharUuid.trim(),
            ),
            sensors = presetKeys.map { preset ->
                SensorConfig(
                    key = presetToSensorKey(preset),
                    preset = preset,
                    updateInterval = defaultIntervalFor(preset),
                )
            },
        )
    }

    private fun presetToSensorKey(preset: String): String =
        preset.lowercase().replace(Regex("[^a-z0-9_]"), "_")

    private fun defaultIntervalFor(preset: String): String = when (preset) {
        "rpm", "speed", "throttle", "gm_current_gear", "gm_prnd_status_alt" -> "1s"
        "coolant_temp", "intake_air_temp", "ambient_temp", "gm_trans_temp" -> "10s"
        "fuel_level", "gm_fuel_level_liters", "odometer" -> "30s"
        else -> "60s"
    }
}

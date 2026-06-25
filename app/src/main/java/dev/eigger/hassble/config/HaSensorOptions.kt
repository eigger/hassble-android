package dev.eigger.hassble.config

/**
 * Home Assistant sensor entity의 device_class / state_class 선택지.
 * @see <a href="https://developers.home-assistant.io/docs/core/entity/sensor/">HA Sensor entity</a>
 */
object HaSensorOptions {

    /** HA SensorDeviceClass (YAML snake_case) */
    val deviceClasses: List<String> = listOf(
        "absolute_humidity",
        "apparent_power",
        "aqi",
        "area",
        "atmospheric_pressure",
        "battery",
        "blood_glucose_concentration",
        "co",
        "co2",
        "conductivity",
        "current",
        "data_rate",
        "data_size",
        "date",
        "distance",
        "duration",
        "energy",
        "energy_distance",
        "energy_storage",
        "enum",
        "frequency",
        "gas",
        "humidity",
        "illuminance",
        "irradiance",
        "moisture",
        "monetary",
        "nitrogen_dioxide",
        "nitrogen_monoxide",
        "nitrous_oxide",
        "ozone",
        "ph",
        "pm1",
        "pm10",
        "pm25",
        "pm4",
        "power",
        "power_factor",
        "precipitation",
        "precipitation_intensity",
        "pressure",
        "reactive_energy",
        "reactive_power",
        "signal_strength",
        "sound_pressure",
        "speed",
        "sulphur_dioxide",
        "temperature",
        "temperature_delta",
        "timestamp",
        "uptime",
        "volatile_organic_compounds",
        "volatile_organic_compounds_parts",
        "voltage",
        "volume",
        "volume_flow_rate",
        "volume_storage",
        "water",
        "weight",
        "wind_direction",
        "wind_speed",
    )

    /** HA SensorStateClass */
    val stateClasses: List<String> = listOf(
        "measurement",
        "measurement_angle",
        "total",
        "total_increasing",
    )

    private val timestampDeviceClasses = setOf("timestamp", "date", "uptime")

    fun deviceClassOptions(dataType: DataType, platform: String, current: String = ""): List<String> {
        if (platform == "text_sensor" || dataType == DataType.string) return emptyList()
        val base = when (dataType) {
            DataType.timestamp -> timestampDeviceClasses.toList()
            else -> deviceClasses
        }
        return optionsIncludingSelected(current, base)
    }

    fun stateClassOptions(dataType: DataType, platform: String, current: String = ""): List<String> {
        if (platform == "text_sensor" || dataType == DataType.string) return emptyList()
        if (dataType == DataType.timestamp) return optionsIncludingSelected(current, emptyList())
        return optionsIncludingSelected(current, stateClasses)
    }

    fun defaultDeviceClass(dataType: DataType): String = when (dataType) {
        DataType.timestamp -> "timestamp"
        else -> ""
    }

    fun defaultStateClass(dataType: DataType, platform: String): String = when {
        platform == "text_sensor" || dataType == DataType.string -> ""
        dataType == DataType.timestamp -> ""
        else -> "measurement"
    }

    fun optionsIncludingSelected(current: String, base: List<String>): List<String> {
        val trimmed = current.trim()
        if (trimmed.isBlank() || trimmed in base) return base
        return listOf(trimmed) + base
    }
}

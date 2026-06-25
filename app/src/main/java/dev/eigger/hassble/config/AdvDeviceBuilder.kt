package dev.eigger.hassble.config

import dev.eigger.hassble.ble.AdvertisementCapture

object AdvDeviceBuilder {

    data class SensorDraft(
        val key: String,
        val sourceField: SourceField,
        val decode: DecodeConfig,
        val deviceClass: String? = null,
        val unit: String? = null,
        val stateClass: String? = "measurement",
        val accuracyDecimals: Int? = null,
    )

    fun slugify(name: String): String =
        name.lowercase()
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
            .ifBlank { "ble_device" }

    fun suggestMatch(capture: AdvertisementCapture.CapturedAdvertisement, useNamePrefix: Boolean): MatchConfig {
        val prefix = if (useNamePrefix) {
            capture.name.take(12).takeIf { it.isNotBlank() && !it.equals("unknown", ignoreCase = true) }
        } else {
            null
        }
        val mfrLen = capture.manufacturerHex?.length?.div(2)
        return MatchConfig(
            namePrefix = prefix,
            serviceDataUuid = capture.serviceUuid,
            manufacturerId = capture.manufacturerId,
            manufacturerMinLength = mfrLen?.takeIf { it > 0 },
        )
    }

    fun defaultSourceField(capture: AdvertisementCapture.CapturedAdvertisement): SourceField = when {
        !capture.manufacturerHex.isNullOrBlank() -> SourceField.manufacturer_data
        !capture.serviceDataHex.isNullOrBlank() -> SourceField.service_data
        else -> SourceField.raw
    }

    fun build(
        id: String,
        name: String,
        match: MatchConfig,
        sensors: List<SensorDraft>,
    ): DeviceConfig = DeviceConfig(
        id = id.trim(),
        name = name.trim(),
        source = Source.advertisement,
        instanceMode = AdvertisementInstanceMode.mac,
        match = match,
        sensors = sensors.map { draft ->
            SensorConfig(
                key = draft.key,
                deviceClass = draft.deviceClass,
                unit = draft.unit,
                stateClass = draft.stateClass,
                accuracyDecimals = draft.accuracyDecimals,
                sourceField = draft.sourceField,
                minLength = draft.decode.offset + draft.decode.length,
                decode = draft.decode,
            )
        },
    )
}

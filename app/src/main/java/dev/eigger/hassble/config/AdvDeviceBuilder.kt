package dev.eigger.hassble.config

import dev.eigger.hassble.ble.AdvertisementCapture

object AdvDeviceBuilder {

    data class SensorDraft(
        val key: String,
        val platform: String = "sensor",
        val sourceField: SourceField,
        val decode: DecodeConfig,
        val deviceClass: String? = null,
        val unit: String? = null,
        val stateClass: String? = "measurement",
        val accuracyDecimals: Int? = null,
        /** payload 최소 바이트. null이면 decode offset+length로 자동 계산 */
        val minLength: Int? = null,
        /** payload 정확한 바이트 수. null이면 검사 안 함 */
        val exactLength: Int? = null,
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
            val isText = draft.platform == "text_sensor"
            SensorConfig(
                key = draft.key,
                platform = draft.platform,
                deviceClass = draft.deviceClass,
                unit = if (isText) null else draft.unit,
                stateClass = if (isText) null else draft.stateClass,
                accuracyDecimals = if (isText) null else draft.accuracyDecimals,
                sourceField = draft.sourceField,
                length = draft.exactLength,
                minLength = draft.minLength ?: (draft.decode.offset + draft.decode.length),
                decode = draft.decode,
            )
        },
    )
}

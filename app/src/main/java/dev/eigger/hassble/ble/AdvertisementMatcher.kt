package dev.eigger.hassble.ble

import dev.eigger.hassble.config.MatchConfig

/**
 * 광고 패킷 매칭. match에 지정된 **모든** 조건을 만족해야 한다 (AND).
 * 예: service_data_uuid + manufacturer_id 둘 다 있으면 둘 다 일치해야 Jaalee로 인식.
 */
object AdvertisementMatcher {

    fun matches(
        match: MatchConfig,
        deviceAddress: String,
        deviceName: String,
        hasServiceUuid: (String) -> Boolean,
        hasManufacturerId: (Int) -> Boolean,
    ): Boolean {
        val checks = mutableListOf<() -> Boolean>()

        match.mac?.let { mac ->
            checks += { mac.equals(deviceAddress, ignoreCase = true) }
        }
        match.serviceDataUuid?.let { uuid ->
            checks += { hasServiceUuid(uuid) }
        }
        match.namePrefix?.let { prefix ->
            checks += { deviceName.startsWith(prefix, ignoreCase = true) }
        }
        match.manufacturerId?.let { id ->
            checks += { hasManufacturerId(id) }
        }

        return checks.isNotEmpty() && checks.all { it() }
    }

    fun bytesToHex(bytes: ByteArray): String =
        bytes.joinToString("") { String.format("%02X", it) }
}

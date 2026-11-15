package dev.eigger.hassble.ble

import dev.eigger.hassble.config.DeviceConfig
import dev.eigger.hassble.config.Source

/**
 * GATT/OBD 기기 바인딩 스캔 목록 필터. 아래 중 하나라도 맞으면 목록에 보인다 (OR).
 * - 기기의 `match` 블록 (런타임에선 쓰지 않고 이 필터에만 쓴다. 예: BM6/BM7 iBeacon 접두)
 * - 광고 서비스 UUID에 service_uuid 포함
 * - 광고 이름에 기기 name 포함
 * service_uuid도 match도 없으면 필터 없이 전부 보인다.
 */
object BindScanFilter {
    fun matches(
        device: DeviceConfig,
        address: String,
        name: String,
        serviceUuids: List<String>,
        manufacturerPayload: (Int) -> ByteArray?,
    ): Boolean {
        val upperUuids = serviceUuids.map { it.uppercase() }
        val hasUuid = { u: String -> upperUuids.any { it.contains(u.uppercase()) } }
        val targetServiceUuid = when (device.source) {
            Source.gatt_notify -> device.gatt?.serviceUuid
            Source.obd -> device.obd?.serviceUuid
            else -> null
        }
        val match = device.match
        if (targetServiceUuid == null && match == null) return true
        if (match != null && AdvertisementMatcher.matches(match, address, name, hasUuid, manufacturerPayload)) return true
        if (targetServiceUuid != null && hasUuid(targetServiceUuid)) return true
        return name.contains(device.name, ignoreCase = true)
    }
}

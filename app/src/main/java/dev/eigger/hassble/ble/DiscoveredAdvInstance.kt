package dev.eigger.hassble.ble

/** 광고 스캔으로 탐지·등록된 BLE 기기 인스턴스 (UI 표시용). */
data class DiscoveredAdvInstance(
    val profileId: String,
    val mac: String,
    val deviceName: String?,
    val instanceId: String,
    val lastSeenMs: Long,
    /** 마지막으로 수신한 manufacturer payload (company ID 제외) hex. 디코딩 진단용. */
    val manufacturerHex: String? = null,
    /** 마지막으로 수신한 service_data payload hex. */
    val serviceDataHex: String? = null,
)

package dev.eigger.hassble.ble

/** GATT/OBD 기기 연결 상태 (UI 표시용). */
enum class DeviceLinkState {
    Disconnected,
    Scanning,
    Connecting,
    Connected,
    Polling,
    Error,
}

data class DeviceLinkStatus(
    val profileId: String,
    val state: DeviceLinkState,
    val mac: String? = null,
    val lastDataMs: Long? = null,
    val errorMessage: String? = null,
)

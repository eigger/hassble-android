package dev.eigger.hassble.ble

/** GATT/OBD 기기 연결 상태 (UI 표시용). */
enum class DeviceLinkState {
    Disconnected,
    Scanning,
    Connecting,
    Connected,
    Polling,
    /** poll 모드(gatt.poll): 직전 주기 성공 후 다음 주기까지 연결 없이 대기. 정상 상태로 취급한다. */
    Idle,
    Error,
}

/** HA `{id}_link_status`가 on인 상태: 실제로 붙어 있거나, poll 기기가 정상 대기 중. */
fun DeviceLinkState?.isLinkUp(): Boolean =
    this == DeviceLinkState.Connected || this == DeviceLinkState.Polling || this == DeviceLinkState.Idle

data class DeviceLinkStatus(
    val profileId: String,
    val state: DeviceLinkState,
    val mac: String? = null,
    val lastDataMs: Long? = null,
    val errorMessage: String? = null,
)

package dev.eigger.hassble.service

import dev.eigger.hassble.ble.DeviceLinkState
import dev.eigger.hassble.ble.DeviceLinkStatus

/**
 * POLL_DEVICE의 stop_after 완료 판정(순수 로직).
 *
 * - poll 기기: 성공하면 Idle(lastDataMs=성공 시각), 실패하면 Disconnected(errorMessage).
 * - notify/OBD 기기: 첫 수신 시 Polling(lastDataMs=수신 시각).
 * [previous]는 요청 직전의 상태로, 그와 같은 값은 낡은 상태라 무시한다.
 */
internal fun isPollFinished(status: DeviceLinkStatus?, previous: DeviceLinkStatus?, requestedAtMs: Long): Boolean {
    if (status == null || status == previous) return false
    return when (status.state) {
        DeviceLinkState.Idle, DeviceLinkState.Polling -> (status.lastDataMs ?: 0L) >= requestedAtMs
        DeviceLinkState.Disconnected -> status.errorMessage != null
        DeviceLinkState.Error -> true
        else -> false
    }
}

/** POLL_DEVICE 요청 하나의 결과. [accepted]=false면 연결 요청 자체가 거절됨, [finished]=false면 시간 초과. */
internal data class PollOutcome(
    val deviceId: String,
    val requestedAtMs: Long,
    val accepted: Boolean,
    val finished: Boolean,
)

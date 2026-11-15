package dev.eigger.hassble.service

import dev.eigger.hassble.config.BleScanModeOption

/** Tasker 등 자동화 앱이 보내는 공개 브로드캐스트 액션/extra. 값 변경은 하위 호환을 깨뜨린다. */
object AutomationActions {
    const val ACTION_START_GATEWAY = "dev.eigger.hassble.action.START_GATEWAY"
    const val ACTION_STOP_GATEWAY = "dev.eigger.hassble.action.STOP_GATEWAY"
    const val ACTION_SET_SCAN_MODE = "dev.eigger.hassble.action.SET_SCAN_MODE"
    const val ACTION_POLL_DEVICE = "dev.eigger.hassble.action.POLL_DEVICE"

    val ALL: Set<String> = setOf(ACTION_START_GATEWAY, ACTION_STOP_GATEWAY, ACTION_SET_SCAN_MODE, ACTION_POLL_DEVICE)

    const val EXTRA_MODE = "mode"
    const val EXTRA_DEVICE_ID = "device_id"
    const val EXTRA_STOP_AFTER = "stop_after"

    /** stop_after=true일 때 poll이 끝나지 않아도 게이트웨이를 멈추는 상한. */
    const val STOP_AFTER_TIMEOUT_MS = 60_000L

    /**
     * scan mode 문자열 파싱. 대소문자·앞뒤 공백·'-'/' ' 구분을 무시하고 별칭도 받는다.
     * 알 수 없으면 null.
     */
    fun parseScanMode(raw: String?): BleScanModeOption? {
        val key = raw?.trim()?.uppercase()?.replace('-', '_')?.replace(' ', '_') ?: return null
        return when (key) {
            "LOW_POWER", "POWER_SAVING" -> BleScanModeOption.LOW_POWER
            "BALANCED" -> BleScanModeOption.BALANCED
            "LOW_LATENCY", "HIGH_PERFORMANCE" -> BleScanModeOption.LOW_LATENCY
            else -> null
        }
    }
}

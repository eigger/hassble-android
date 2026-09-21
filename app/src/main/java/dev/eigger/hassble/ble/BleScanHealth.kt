package dev.eigger.hassble.ble

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 광고 스캔 세션의 상태 요약. 서비스 heartbeat 로그와 진단 UI에서 "지금 스캐너가 살아 있나"를
 * 확인하는 용도라, 매 ScanResult마다 갱신하지 않고 [BleScanHealth.onResult]가 1초 단위로 묶는다.
 */
data class ScanHealthSnapshot(
    val scanning: Boolean = false,
    /** 현재(또는 마지막) 세션이 startScan()에 성공한 시각. */
    val sessionStartMs: Long? = null,
    /** 스캐너가 마지막으로 ScanResult를 한 건이라도 받은 시각 (세션 경계와 무관). */
    val lastResultMs: Long? = null,
    /** 세션 시작 횟수 (첫 시작 포함). */
    val sessionCount: Int = 0,
    /** 마지막 세션이 끝난 이유. watchdog 재시작·에러·중단 모두 포함. */
    val lastStopReason: String? = null,
    /** 마지막 onScanFailed errorCode (ScanCallback 상수). 없으면 null. */
    val lastFailureCode: Int? = null,
    /** 필터 수. 0이면 화면 꺼짐 시 Android가 결과를 주지 않는 unfiltered 스캔이다. */
    val filterCount: Int = 0,
) {
    fun describe(nowMs: Long = System.currentTimeMillis()): String {
        val lastAge = lastResultMs?.let { "${(nowMs - it) / 1000}s ago" } ?: "never"
        val sessionAge = sessionStartMs?.let { "${(nowMs - it) / 1000}s" } ?: "-"
        return buildString {
            append("scanning=$scanning, lastResult=$lastAge, session=$sessionAge (#$sessionCount), filters=$filterCount")
            lastStopReason?.let { append(", lastStop='$it'") }
            lastFailureCode?.let { append(", lastFailureCode=$it") }
        }
    }
}

object BleScanHealth {
    private val _state = MutableStateFlow(ScanHealthSnapshot())
    val state: StateFlow<ScanHealthSnapshot> = _state.asStateFlow()

    @Volatile private var lastPublishedResultMs = 0L

    fun onScanStarted(nowMs: Long, filterCount: Int) {
        _state.value = _state.value.copy(
            scanning = true,
            sessionStartMs = nowMs,
            sessionCount = _state.value.sessionCount + 1,
            filterCount = filterCount,
        )
    }

    fun onResult(nowMs: Long) {
        // 주행 중엔 초당 수십 건이라 StateFlow 갱신을 1초 단위로 묶는다.
        if (nowMs - lastPublishedResultMs < 1_000L) return
        lastPublishedResultMs = nowMs
        _state.value = _state.value.copy(lastResultMs = nowMs)
    }

    fun onScanStopped(reason: String, failureCode: Int? = null) {
        _state.value = _state.value.copy(
            scanning = false,
            lastStopReason = reason,
            lastFailureCode = failureCode ?: _state.value.lastFailureCode,
        )
    }

    fun reset() {
        lastPublishedResultMs = 0L
        _state.value = ScanHealthSnapshot()
    }
}

/**
 * 스캔 세션을 언제 강제로 끊고 다시 startScan() 할지 정하는 순수 규칙.
 *
 * 왜 필요한가: Nordic BleScanner flow는 onScanFailed가 올 때만 예외로 끝난다. 그런데 Android는
 * 스캔을 "조용히" 무력화하는 경우가 있다 — AOSP ScanManager는 30분 연속 스캔을 콜백 없이
 * opportunistic 모드로 강등하고, Bluetooth OFF나 일부 단말의 스택 재시작도 콜백 없이 세션만
 * 사라진다. 그 상태에선 flow가 살아 있는 채로 영원히 결과 0건이 되므로 밖에서 끊어 줘야 한다.
 */
object ScanWatchdogPolicy {
    /** 이 시간 동안 ScanResult가 한 건도 없으면 세션을 재시작한다. */
    const val IDLE_LIMIT_MS = 60_000L
    /** 진짜로 주변에 광고가 없는 곳(심야 주차장 등)에서 무한 재시작하지 않도록 유휴 재시작은 여기까지 늘린다. */
    const val IDLE_LIMIT_MAX_MS = 10 * 60_000L
    /** AOSP 30분 타임아웃보다 먼저 세션을 갈아 끼운다. */
    const val MAX_SESSION_MS = 25 * 60_000L
    /** watchdog 점검 주기. */
    const val TICK_MS = 5_000L
    /** stopScan() 후 startScan()까지 쉬는 시간 — 스택이 이전 세션을 정리할 여유. */
    const val RESTART_DELAY_MS = 1_000L

    /**
     * 유휴 재시작이 연속 [consecutiveIdleRestarts]번 이어졌을 때 다음 세션의 유휴 한도.
     * 60s → 2m → 4m → 8m → 10m(상한). 결과가 한 건이라도 오면 카운터를 0으로 되돌린다.
     */
    fun idleLimitMs(consecutiveIdleRestarts: Int): Long {
        val shift = consecutiveIdleRestarts.coerceIn(0, 10)
        return (IDLE_LIMIT_MS shl shift).coerceAtMost(IDLE_LIMIT_MAX_MS)
    }

    /**
     * 재시작해야 하면 이유 문자열, 아니면 null.
     * [lastResultMs]는 세션 시작 시각으로 초기화돼 있어야 한다(결과가 한 번도 없어도 idle이 잰다).
     */
    fun restartReason(
        nowMs: Long,
        sessionStartMs: Long,
        lastResultMs: Long,
        idleLimitMs: Long,
        maxSessionMs: Long = MAX_SESSION_MS,
    ): String? {
        val idle = nowMs - lastResultMs
        if (idle >= idleLimitMs) {
            return "no ScanResult for ${idle / 1000}s (limit ${idleLimitMs / 1000}s)"
        }
        val age = nowMs - sessionStartMs
        if (age >= maxSessionMs) {
            return "session age ${age / 60_000}m reached ${maxSessionMs / 60_000}m (pre-empting Android 30-min scan downgrade)"
        }
        return null
    }
}

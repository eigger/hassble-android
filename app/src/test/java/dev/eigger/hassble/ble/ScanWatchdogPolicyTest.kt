package dev.eigger.hassble.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 스캔 세션을 언제 강제로 재시작할지 정하는 규칙. Nordic flow는 onScanFailed가 올 때만 끝나서,
 * Android가 콜백 없이 스캔을 무력화하면(30분 opportunistic 강등, BT OFF 등) 밖에서 끊어야 한다.
 */
class ScanWatchdogPolicyTest {

    private val start = 1_000_000L

    @Test
    fun `healthy session with recent results is left alone`() {
        val reason = ScanWatchdogPolicy.restartReason(
            nowMs = start + 120_000, sessionStartMs = start, lastResultMs = start + 118_000,
            idleLimitMs = ScanWatchdogPolicy.IDLE_LIMIT_MS,
        )
        assertNull(reason)
    }

    @Test
    fun `restarts when no result for the idle limit`() {
        val reason = ScanWatchdogPolicy.restartReason(
            nowMs = start + ScanWatchdogPolicy.IDLE_LIMIT_MS, sessionStartMs = start, lastResultMs = start,
            idleLimitMs = ScanWatchdogPolicy.IDLE_LIMIT_MS,
        )
        assertNotNull(reason)
        assertTrue(reason!!.contains("no ScanResult"))
    }

    @Test
    fun `old healthy session is never replaced just for its age`() {
        val now = start + 3 * 60 * 60_000L
        assertNull(
            ScanWatchdogPolicy.restartReason(
                nowMs = now, sessionStartMs = start, lastResultMs = now - 1_000,
                idleLimitMs = ScanWatchdogPolicy.IDLE_LIMIT_MS,
            ),
        )
    }

    @Test
    fun `never restarts while the screen is off or locked even when idle or old`() {
        val now = start + 3 * 60 * 60_000L
        assertNull(
            ScanWatchdogPolicy.restartReason(
                nowMs = now, sessionStartMs = start, lastResultMs = start,
                idleLimitMs = ScanWatchdogPolicy.IDLE_LIMIT_MS, active = false,
            ),
        )
        assertNull(
            ScanWatchdogPolicy.restartReason(
                nowMs = now, sessionStartMs = start, lastResultMs = start,
                idleLimitMs = ScanWatchdogPolicy.IDLE_LIMIT_MS, active = false, startedInactive = true,
            ),
        )
    }

    @Test
    fun `session started while locked is replaced once the device is unlocked`() {
        val reason = ScanWatchdogPolicy.restartReason(
            nowMs = start + 5_000, sessionStartMs = start, lastResultMs = start + 4_000,
            idleLimitMs = ScanWatchdogPolicy.IDLE_LIMIT_MS, active = true, startedInactive = true,
        )
        assertNotNull(reason)
        assertTrue(reason!!.contains("screen"))
    }

    @Test
    fun `idle limit backs off and caps`() {
        assertEquals(5 * 60_000L, ScanWatchdogPolicy.idleLimitMs(0))
        assertEquals(ScanWatchdogPolicy.IDLE_LIMIT_MAX_MS, ScanWatchdogPolicy.idleLimitMs(1))
        assertEquals(ScanWatchdogPolicy.IDLE_LIMIT_MAX_MS, ScanWatchdogPolicy.idleLimitMs(2))
        // 아주 큰 값이 들어와도 오버플로 없이 상한에 머문다.
        assertEquals(ScanWatchdogPolicy.IDLE_LIMIT_MAX_MS, ScanWatchdogPolicy.idleLimitMs(100))
        assertEquals(ScanWatchdogPolicy.IDLE_LIMIT_MS, ScanWatchdogPolicy.idleLimitMs(-1))
    }

    @Test
    fun `health snapshot describes last result age and session`() {
        val snap = ScanHealthSnapshot(
            scanning = true, sessionStartMs = start, lastResultMs = start + 5_000,
            sessionCount = 3, lastStopReason = "watchdog", lastFailureCode = 2, filterCount = 2,
        )
        val text = snap.describe(nowMs = start + 65_000)
        assertEquals(
            "scanning=true, lastResult=60s ago, session=65s (#3), filters=2, lastStop='watchdog', lastFailureCode=2",
            text,
        )
        assertEquals(
            "scanning=false, lastResult=never, session=- (#0), filters=0",
            ScanHealthSnapshot().describe(nowMs = start),
        )
    }
}

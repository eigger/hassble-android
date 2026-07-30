package dev.eigger.hassble.ble

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 유휴 구간에서 다음 센서까지 정확히 자는 시간을 계산하는 로직.
 * 이전에는 10ms마다 계속 깨어나 확인했는데, 60s 주기 센서라도 초당 100번
 * 깨어나 드라이브 내내 배터리를 소모했다.
 */
class IdleWaitTest {

    @Test
    fun `waits exactly until the nearest sensor is due`() {
        val now = 1_000_000L
        val nextPollAtMs = listOf(now + 950, now + 30_000, now + 5_000)
        assertEquals(950L, NordicElm327Source.computeIdleWaitMs(nextPollAtMs, now))
    }

    @Test
    fun `never waits less than the loop floor`() {
        val now = 1_000_000L
        // 이미 지났어야 할 시각(음수 차이)이 들어와도 음수로 delay() 하지 않는다.
        val nextPollAtMs = listOf(now - 5, now + 100)
        assertEquals(10L, NordicElm327Source.computeIdleWaitMs(nextPollAtMs, now))
    }

    @Test
    fun `caps a long-idle sensor set to the max wait`() {
        val now = 1_000_000L
        // 모든 센서가 60s 주기여도 5s 넘게 자지는 않는다.
        val nextPollAtMs = listOf(now + 60_000, now + 45_000)
        assertEquals(5_000L, NordicElm327Source.computeIdleWaitMs(nextPollAtMs, now))
    }

    @Test
    fun `a single sensor uses its own due time`() {
        val now = 1_000_000L
        assertEquals(1_000L, NordicElm327Source.computeIdleWaitMs(listOf(now + 1_000), now))
    }
}

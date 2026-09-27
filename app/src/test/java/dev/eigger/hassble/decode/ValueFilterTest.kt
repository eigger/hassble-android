package dev.eigger.hassble.decode

import dev.eigger.hassble.config.PublishRule
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ValueFilterTest {

    private val t0 = 1_000_000L

    @Test
    fun `default rule suppresses an unchanged value`() {
        val f = ValueFilter(PublishRule())
        assertTrue(f.allow(-2.0, t0))
        // 같은 층에 다시 주차: 값이 같아 on_change_only에 막힌다.
        assertFalse(f.allow(-2.0, t0 + 60_000))
    }

    @Test
    fun `reset lets the next identical value through even inside min_interval`() {
        val f = ValueFilter(PublishRule())
        assertTrue(f.allow("B2-17", t0))
        f.reset()
        // 요청 직후 응답: 값도 같고 min_interval(10s) 안이지만 통과해야 한다.
        assertTrue(f.allow("B2-17", t0 + 3_000))
        // 같은 응답 버스트의 나머지는 다시 걸러진다.
        assertFalse(f.allow("B2-17", t0 + 3_200))
    }
}

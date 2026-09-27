package dev.eigger.hassble.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SensorLastValueTest {

    private val t0 = 1_000_000L

    private fun next(prev: SensorLastValue?, display: String, published: Boolean, now: Long) =
        SensorLastValue.next(prev, "p", "p", "floor", display, published, now)

    @Test
    fun `published value is recorded with send time and value`() {
        val v = next(null, "-2", published = true, now = t0)
        assertEquals(t0, v.updatedAtMs)
        assertEquals(t0, v.publishedAtMs)
        assertEquals("-2", v.publishedValue)
    }

    @Test
    fun `filtered receive updates the receive time but keeps what HA has`() {
        val sent = next(null, "50", published = true, now = t0)
        val filtered = next(sent, "51.5", published = false, now = t0 + 60_000)
        assertEquals("51.5", filtered.value)
        assertEquals(t0 + 60_000, filtered.updatedAtMs)
        assertEquals(t0, filtered.publishedAtMs)
        assertEquals("50", filtered.publishedValue)
    }

    @Test
    fun `receive before anything was sent has no send time`() {
        val v = next(null, "B2-17", published = false, now = t0)
        assertNull(v.publishedAtMs)
        assertNull(v.publishedValue)
    }

    @Test
    fun `a later publish replaces what HA has`() {
        val sent = next(null, "A", published = true, now = t0)
        val again = next(sent, "B", published = true, now = t0 + 10)
        assertEquals("B", again.publishedValue)
        assertEquals(t0 + 10, again.publishedAtMs)
    }
}

package dev.eigger.hassble.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SensorLastValueTest {

    private val t0 = 1_000_000L

    private fun next(prev: SensorLastValue?, display: String, published: Boolean, now: Long) =
        SensorLastValue.next(prev, "p", "p", "floor", display, published, now, refreshMs = 1_000L)

    @Test
    fun `published value is recorded with send time and value`() {
        val v = next(null, "-2", published = true, now = t0)!!
        assertEquals(t0, v.updatedAtMs)
        assertEquals(t0, v.publishedAtMs)
        assertEquals("-2", v.publishedValue)
    }

    @Test
    fun `filtered receive updates the receive time but keeps what HA has`() {
        val sent = next(null, "50", published = true, now = t0)!!
        val filtered = next(sent, "51.5", published = false, now = t0 + 60_000)!!
        assertEquals("51.5", filtered.value)
        assertEquals(t0 + 60_000, filtered.updatedAtMs)
        assertEquals(t0, filtered.publishedAtMs)
        assertEquals("50", filtered.publishedValue)
    }

    @Test
    fun `unpublished receives are throttled even when the value keeps changing`() {
        val sent = next(null, "20.0", published = true, now = t0)!!
        assertNull(next(sent, "20.1", published = false, now = t0 + 100))
        assertNull(next(sent, "20.2", published = false, now = t0 + 900))
        assertNotNull(next(sent, "20.3", published = false, now = t0 + 1_000))
    }

    @Test
    fun `published values are never throttled`() {
        val sent = next(null, "A", published = true, now = t0)!!
        val again = next(sent, "B", published = true, now = t0 + 10)!!
        assertEquals("B", again.publishedValue)
    }
}

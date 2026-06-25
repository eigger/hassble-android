package dev.eigger.hassble.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HaSensorOptionsTest {

    @Test
    fun `timestamp type limits device classes`() {
        val options = HaSensorOptions.deviceClassOptions(DataType.timestamp, "sensor")
        assertEquals(listOf("timestamp", "date", "uptime"), options)
    }

    @Test
    fun `text sensor hides class options`() {
        assertTrue(HaSensorOptions.deviceClassOptions(DataType.int16, "text_sensor").isEmpty())
        assertTrue(HaSensorOptions.stateClassOptions(DataType.int16, "text_sensor").isEmpty())
    }

    @Test
    fun `unknown current value is preserved in options`() {
        val options = HaSensorOptions.deviceClassOptions(DataType.int16, "sensor", "legacy_custom")
        assertEquals("legacy_custom", options.first())
    }
}

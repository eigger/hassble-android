package dev.eigger.hassble.ble

import dev.eigger.hassble.config.AdvertisePayloadPhase
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdvertisePayloadTest {

    @Test
    fun testNextCounter() {
        assertEquals(1, AdvertisePayload.nextCounter(0))
        assertEquals(255, AdvertisePayload.nextCounter(254))
        assertEquals(0, AdvertisePayload.nextCounter(255))
        assertEquals(1, AdvertisePayload.nextCounter(256))
    }

    @Test
    fun testRenderTokenDecimal() {
        val template = "05CB34{counter}"
        assertEquals("05CB340", AdvertisePayload.render(template, 0))
        assertEquals("05CB3442", AdvertisePayload.render(template, 42))
        assertEquals("05CB34255", AdvertisePayload.render(template, 255))
    }

    @Test
    fun testRenderTokenHexFormat() {
        val template = "05CB3440{counter:02X}"
        assertEquals("05CB344000", AdvertisePayload.render(template, 0))
        assertEquals("05CB34400A", AdvertisePayload.render(template, 10))
        assertEquals("05CB3440FF", AdvertisePayload.render(template, 255))
    }

    @Test
    fun testRenderWithoutToken() {
        val template = "05CB3440FF"
        assertEquals("05CB3440FF", AdvertisePayload.render(template, 0))
        assertEquals("05CB3440FF", AdvertisePayload.render(template, 255))
    }

    @Test
    fun testToBytesValid() {
        val bytes = AdvertisePayload.toBytes("05CB344000")
        assertNotNull(bytes)
        assertArrayEquals(byteArrayOf(0x05, 0xCB.toByte(), 0x34, 0x40, 0x00), bytes)
    }

    @Test
    fun testToBytesInvalid() {
        // Odd length
        assertNull(AdvertisePayload.toBytes("05CB3"))
        // Non-hex chars
        assertNull(AdvertisePayload.toBytes("05CB3440ZZ"))
    }

    @Test
    fun testValidationErrorValid() {
        assertNull(AdvertisePayload.validationError("05CB34447B91F8C69BBE41157C70BB631C40{counter:02X}"))
        assertNull(AdvertisePayload.validationError("05CB3440"))
    }

    @Test
    fun testValidationErrorBlank() {
        assertNotNull(AdvertisePayload.validationError(""))
        assertNotNull(AdvertisePayload.validationError("   "))
    }

    @Test
    fun testValidationErrorExceedsMaxPayload() {
        // 25 bytes (50 hex chars) exceeds 24 bytes limit
        val tooLong = "00".repeat(25)
        assertNotNull(AdvertisePayload.validationError(tooLong))
        // 24 bytes (48 hex chars) is OK
        val exact24 = "00".repeat(24)
        assertNull(AdvertisePayload.validationError(exact24))
    }

    @Test
    fun testValidationErrorDecimalTokenOddLength() {
        // "05{counter}" when counter=0 gives "050" (odd length -> invalid hex)
        val invalidDecimal = "05{counter}"
        assertNotNull(AdvertisePayload.validationError(invalidDecimal))
    }

    @Test
    fun testRenderStateTokenHexFormat() {
        val template = "02050064{state:02X}{counter:02X}"
        assertEquals("020500644100", AdvertisePayload.render(template, 0, 0x41))
        assertEquals("020500644004", AdvertisePayload.render(template, 4, 0x40))
        assertEquals("02050064FF0A", AdvertisePayload.render(template, 10, 255))
    }

    @Test
    fun testRenderPhaseKeepsCounter() {
        val base = "02050064{state:02X}{counter:02X}"
        val press = AdvertisePayloadPhase(state = 0x41, duration = "200ms")
        val hold = AdvertisePayloadPhase(state = 0x40, duration = "3s")
        assertEquals("020500644104", AdvertisePayload.renderPhase(base, 4, press))
        assertEquals("020500644004", AdvertisePayload.renderPhase(base, 4, hold))
    }

    @Test
    fun testHasStateToken() {
        assertTrue(AdvertisePayload.hasStateToken("02050064{state:02X}{counter:02X}"))
        assertFalse(AdvertisePayload.hasStateToken("0205006441{counter:02X}"))
    }

    @Test
    fun testEstimatedLegacyAdvSizeWithAptSmartKey() {
        assertEquals(27, AdvertisePayload.estimatedLegacyAdvSize(6, "APT SmartKey"))
        assertEquals(13, AdvertisePayload.estimatedLegacyAdvSize(6, null))
        assertEquals(6, AdvertisePayload.maxRenderedPayloadBytes("02050064{state:02X}{counter:02X}"))
    }

    @Test
    fun testValidationErrorWithStateToken() {
        assertNull(AdvertisePayload.validationError("02050064{state:02X}{counter:02X}"))
    }
}

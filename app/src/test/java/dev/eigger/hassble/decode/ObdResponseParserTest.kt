package dev.eigger.hassble.decode

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ObdResponseParserTest {

    @Test
    fun `compact single frame`() {
        val hex = ObdResponseParser.normalizeElm327Response("410C1AF8\r\r>")
        assertEquals("410C1AF8", hex)
    }

    @Test
    fun `spaced single frame`() {
        val hex = ObdResponseParser.normalizeElm327Response("41 0C 1A F8 \r>")
        assertEquals("410C1AF8", hex)
    }

    @Test
    fun `mode 22 single frame`() {
        val hex = ObdResponseParser.normalizeElm327Response("62F1900102\r>")
        assertEquals("62F1900102", hex)
    }

    @Test
    fun `iso-tp single frame pci`() {
        val bytes = byteArrayOf(0x04, 0x41, 0x0C, 0x1A.toByte(), 0xF8.toByte())
        val payload = ObdResponseParser.reassembleIsotp(bytes)
        assertArrayEquals(byteArrayOf(0x41, 0x0C, 0x1A.toByte(), 0xF8.toByte()), payload)
    }

    @Test
    fun `iso-tp multi frame`() {
        val bytes = byteArrayOf(
            0x10, 0x0A, 0x62, 0xF1.toByte(), 0x90.toByte(), 0x01, 0x02, 0x03,
            0x21, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0A,
        )
        val payload = ObdResponseParser.reassembleIsotp(bytes)
        assertEquals(10, payload.size)
        assertEquals(0x62, payload[0].toInt() and 0xFF)
        assertEquals(0x07, payload[9].toInt() and 0xFF)
    }

    @Test
    fun `no data returns null`() {
        assertNull(ObdResponseParser.normalizeElm327Response("NO DATA\r>"))
    }
}

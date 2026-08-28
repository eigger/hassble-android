package dev.eigger.hassble.decode

import dev.eigger.hassble.config.DataType
import dev.eigger.hassble.config.DecodeConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DecoderStringTest {

    @Test
    fun testFixedLengthString() {
        // "B32" -> ASCII 0x42, 0x33, 0x32
        val bytes = byteArrayOf(0x00, 0x01, 0x42, 0x33, 0x32, 0x99.toByte())
        val config = DecodeConfig(offset = 2, length = 3, type = DataType.string)
        val result = Decoder.decodeStructured(bytes, config)
        assertEquals("B32", result)
    }

    @Test
    fun testVariableLengthStringLengthZero() {
        // length: 0 means read until end of bytes
        val bytes = byteArrayOf(0x00, 0x01, 0x42, 0x33, 0x32)
        val config = DecodeConfig(offset = 2, length = 0, type = DataType.string)
        val result = Decoder.decodeStructured(bytes, config)
        assertEquals("B32", result)
    }

    @Test
    fun testVariableLengthStringWithTrailingPadding() {
        // "B2  \0" -> should be trimmed to "B2"
        val bytes = byteArrayOf(0x42, 0x32, 0x20, 0x20, 0x00)
        val config = DecodeConfig(offset = 0, length = 0, type = DataType.string)
        val result = Decoder.decodeStructured(bytes, config)
        assertEquals("B2", result)
    }

    @Test
    fun testOffsetOutOfBounds() {
        val bytes = byteArrayOf(0x42, 0x33)
        val config = DecodeConfig(offset = 5, length = 0, type = DataType.string)
        val result = Decoder.decodeStructured(bytes, config)
        assertNull(result)
    }
}

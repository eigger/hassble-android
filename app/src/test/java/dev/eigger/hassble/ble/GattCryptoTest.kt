package dev.eigger.hassble.ble

import dev.eigger.hassble.config.GattEncryption
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GattCryptoTest {
    private val bm6 = GattEncryption(keyHex = "6c656167656e64fffe30313030303039")
    private val bm7 = GattEncryption(keyHex = "6c656167656e64fffe30313030303040")
    private val poll = "d1550700000000000000000000000000"

    private fun b(hex: String) = GattCrypto.hexToBytes(hex)!!
    private fun h(bytes: ByteArray) = GattCrypto.bytesToHex(bytes).lowercase()

    @Test
    fun `bm6 poll command encrypts to known vector`() {
        assertEquals("697ea0b5d54cf024e794772355554114", h(GattCrypto.encrypt(bm6, b(poll))))
    }

    // slydiman/bm7-battery-monitor ESPHome/bm7.yaml의 ble_write 값
    @Test
    fun `bm7 poll command encrypts to known vector`() {
        assertEquals("586d7b2377c6924dcd750acb29f5bf8d", h(GattCrypto.encrypt(bm7, b(poll))))
    }

    @Test
    fun `bm6 reply decrypts to known vector`() {
        assertEquals(
            "d155070017010004ab00000000020000",
            h(GattCrypto.decrypt(bm6, b("5a7a41c3a57ca1fa9247f76557c5d618"))),
        )
    }

    @Test
    fun `round trip with custom iv and multiple blocks`() {
        val enc = GattEncryption(keyHex = bm7.keyHex, ivHex = "000102030405060708090a0b0c0d0e0f")
        val plain = b(poll + poll.replace("d1", "e2"))
        val ct = GattCrypto.encrypt(enc, plain)
        assertFalse(plain.contentEquals(ct))
        assertEquals(h(plain), h(GattCrypto.decrypt(enc, ct)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `encrypt rejects non block multiple`() {
        GattCrypto.encrypt(bm6, b("d155"))
    }

    @Test
    fun `decryptIfNeeded drops non multiple of 16 and passes through without encryption`() {
        assertNull(GattCrypto.decryptIfNeeded(bm6, ByteArray(15)))
        assertNull(GattCrypto.decryptIfNeeded(bm6, ByteArray(0)))
        assertEquals("AABB", GattCrypto.bytesToHex(GattCrypto.decryptIfNeeded(null, b("aabb"))!!))
    }

    @Test
    fun `isValid checks key and iv lengths`() {
        assertTrue(GattCrypto.isValid(bm6))
        assertFalse(GattCrypto.isValid(GattEncryption(keyHex = "00")))
        assertFalse(GattCrypto.isValid(GattEncryption(keyHex = bm6.keyHex, ivHex = "zz")))
    }

    @Test
    fun `prefix filter is case insensitive and optional`() {
        assertTrue(GattCrypto.matchesPrefix("D1550700ABCD", "d1550700"))
        assertFalse(GattCrypto.matchesPrefix("D15507FF0000", "d1550700"))
        assertTrue(GattCrypto.matchesPrefix("D15507FF0000", null))
        assertTrue(GattCrypto.matchesPrefix("D15507FF0000", ""))
    }

    @Test
    fun `awaitResponse skips ack and bad length then returns data`() = runBlocking {
        val ack = GattCrypto.encrypt(bm6, b("d15507ff000000000000000000000000"))
        val data = b("5a7a41c3a57ca1fa9247f76557c5d618")
        val dropped = mutableListOf<String>()
        val hex = GattCrypto.awaitResponse(
            flowOf(ByteArray(5), ack, data), bm6, "d1550700", 1_000, onDropped = { dropped += it },
        )
        assertEquals("D155070017010004AB00000000020000", hex)
        assertEquals(2, dropped.size)
    }

    @Test
    fun `awaitResponse times out with only an ack`() = runBlocking {
        val ack = GattCrypto.encrypt(bm6, b("d15507ff000000000000000000000000"))
        val result = GattCrypto.awaitResponse(
            flow { emit(ack); delay(5_000) }, bm6, "d1550700", 100,
        )
        assertNull(result)
    }
}

package dev.eigger.hassble.decode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class EvalFormulaTest {

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test
    fun `standard rpm formula`() {
        // 01 0C → 1A F8 = 6904 / 4 = 1726 rpm
        assertEquals(1726.0, Decoder.evalFormula("(a*256+b)/4", bytes(0x1A, 0xF8)), 0.001)
    }

    @Test
    fun `bytes beyond h are addressable`() {
        // 현대 계기판 22B002: g:h:i 24비트 오도미터
        val data = bytes(0x00, 0x00, 0x00, 0x00, 0x5A, 0x9C, 0x01, 0xE2, 0x40)
        assertEquals(123456.0, Decoder.evalFormula("g*65536+h*256+i", data), 0.001)
    }

    @Test
    fun `byte t is the last addressable variable`() {
        val data = ByteArray(20) { it.toByte() }
        assertEquals(19.0, Decoder.evalFormula("t", data), 0.001)
    }

    @Test
    fun `fuel level uses byte e`() {
        // 22B002 byte E * 0.5 = liters
        val data = bytes(0x00, 0x00, 0x00, 0x00, 0x5A)
        assertEquals(45.0, Decoder.evalFormula("e*0.5", data), 0.001)
    }

    @Test
    fun `unbound e is rejected instead of resolving to euler constant`() {
        // exp4j는 e를 내장 상수로 등록하므로, 가드가 없으면 2.718*0.5로 조용히 계산된다.
        val short = bytes(0x01, 0x02, 0x03, 0x04)
        assertThrows(IllegalArgumentException::class.java) {
            Decoder.evalFormula("e*0.5", short)
        }
    }

    @Test
    fun `unbound byte variable is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            Decoder.evalFormula("g*65536+h*256+i", bytes(0x01, 0x02))
        }
    }

    @Test
    fun `function names are not mistaken for byte variables`() {
        assertEquals(2.0, Decoder.evalFormula("sqrt(a)", bytes(0x04)), 0.001)
    }
}

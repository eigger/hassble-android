package dev.eigger.hassble.decode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ELM327 수신 버퍼에서 응답을 잘라내는 규칙.
 * 프롬프트 하나가 응답 하나이며, 뒤에 남은 조각은 다음 응답의 시작이므로 보존해야 한다.
 */
class RxBufferTest {

    private fun drain(buffer: StringBuilder, chunk: String): List<String> {
        buffer.append(chunk)
        return ObdResponseParser.drainCompleteResponses(buffer)
    }

    @Test
    fun `two responses in one notification stay separate`() {
        val buf = StringBuilder()
        assertEquals(listOf("410C0D2A", "410D00"), drain(buf, "410C0D2A\r>410D00\r>"))
        assertEquals(0, buf.length)
    }

    @Test
    fun `a partial response is kept for the next notification`() {
        val buf = StringBuilder()
        assertTrue(drain(buf, "0:6103AA").isEmpty())
        assertEquals(listOf("0:6103AA\rBBCC\r1:DDEE"), drain(buf, "BBCC\r1:DDEE\r>"))
        assertEquals(0, buf.length)
    }

    @Test
    fun `trailing data after a prompt survives`() {
        val buf = StringBuilder()
        assertEquals(listOf("410C0D2A"), drain(buf, "410C0D2A\r>410D"))
        assertEquals("410D", buf.toString())
        assertEquals(listOf("410D00"), drain(buf, "00\r>"))
    }

    @Test
    fun `a bare prompt yields nothing`() {
        val buf = StringBuilder()
        assertTrue(drain(buf, ">").isEmpty())
        assertTrue(drain(buf, "\r\r>").isEmpty())
    }

    @Test
    fun `one response per notification is unchanged`() {
        val buf = StringBuilder()
        assertEquals(listOf("410C0D2A"), drain(buf, "410C0D2A\r>"))
        assertEquals(listOf("410D00"), drain(buf, "410D00\r>"))
    }
}

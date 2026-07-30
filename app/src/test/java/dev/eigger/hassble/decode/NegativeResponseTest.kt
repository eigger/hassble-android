package dev.eigger.hassble.decode

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NegativeResponseTest {

    @Test
    fun `explains the cluster refusal seen on the Accent`() {
        // 엑센트 RB가 ATSH7C6 + 22B002 에 돌려준 실제 응답
        val msg = ObdResponseParser.explainNegativeResponse("7F2222")
        assertTrue(msg, msg!!.contains("service 22"))
        assertTrue(msg, msg.contains("NRC 22"))
        assertTrue(msg, msg.contains("conditionsNotCorrect"))
    }

    @Test
    fun `handles spacing and prompt characters`() {
        val msg = ObdResponseParser.explainNegativeResponse("7F 22 31 \r>")
        assertTrue(msg, msg!!.contains("requestOutOfRange"))
    }

    @Test
    fun `a 7F byte inside a positive payload is not mistaken for a refusal`() {
        // 데이터 중간의 7F는 부정 응답이 아니다 — payload 선두만 본다.
        assertNull(ObdResponseParser.explainNegativeResponse("61037F2222FFFF"))
    }

    @Test
    fun `the refusal survives ISO-TP normalization`() {
        val hex = ObdResponseParser.normalizeElm327Response("7F2222\r\r>")
        assertTrue(ObdResponseParser.explainNegativeResponse(hex!!) != null)
    }

    @Test
    fun `positive responses are not flagged`() {
        assertNull(ObdResponseParser.explainNegativeResponse("410C0D2A"))
        assertNull(ObdResponseParser.explainNegativeResponse("6101FFFFFFFF0026"))
    }

    @Test
    fun `NO DATA is not a negative response`() {
        assertNull(ObdResponseParser.explainNegativeResponse("NO DATA"))
    }

    @Test
    fun `truncated negative response does not crash`() {
        assertNull(ObdResponseParser.explainNegativeResponse("7F22"))
    }
}

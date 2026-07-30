package dev.eigger.hassble.decode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * ELM327은 멀티프레임 응답 앞에 전체 길이를 3자리 16진수 한 줄로 찍는다.
 * 길이에 A~F가 섞이는 순간 그 줄이 데이터로 오인돼 응답 전체가 버려지던 회귀를 막는다.
 */
class Elm327MultilineTest {

    /** `0:`·`1:`… 로 번호가 붙은 ELM327 멀티프레임 출력을 만든다. */
    private fun elmFrames(payloadHex: String): String {
        val total = payloadHex.length / 2
        val sb = StringBuilder("%03X\r".format(total))
        var idx = 0
        var line = 0
        while (idx < payloadHex.length) {
            val take = if (line == 0) 12 else 14   // 첫 줄 6바이트, 이후 7바이트
            sb.append("$line:").append(payloadHex.substring(idx, minOf(idx + take, payloadHex.length))).append('\r')
            idx += take
            line++
        }
        return sb.append('>').toString()
    }

    @Test
    fun `41-byte response with an all-digit length header`() {
        // 실측 2101 응답. 41바이트 → 길이 "029" — 전부 숫자라 예전 코드도 통과했다.
        val payload = "6101FFFFFFFF0026001E0C49515C8009251300FF1A89000900000049B550FF1E03000026FFFFFFFF55"
        assertEquals(41, payload.length / 2)
        assertEquals(payload, ObdResponseParser.normalizeElm327Response(elmFrames(payload)))
    }

    @Test
    fun `63-byte response whose length header contains a hex letter`() {
        // 2103이 byte 57~60을 담으려면 63바이트 → 길이 "03F".
        // 'F' 때문에 예전 코드는 길이 줄을 데이터로 붙여 hex를 홀수로 만들고 null을 냈다.
        val payload = "6103" + "AB".repeat(61)
        assertEquals(63, payload.length / 2)
        assertEquals("03F", "%03X".format(63))

        val hex = ObdResponseParser.normalizeElm327Response(elmFrames(payload))
        assertNotNull("길이 헤더 03F가 데이터로 섞이면 안 된다", hex)
        assertEquals(payload, hex)
    }

    @Test
    fun `the odometer survives the round trip`() {
        // byte 57~60 = 0x0B1C9E60 = 186,000,480 → /1000 = 186000.48 km
        val data = ByteArray(61)
        val odo = 186_000_480L
        for (i in 0 until 4) data[57 + i] = ((odo shr (8 * (3 - i))) and 0xFF).toByte()
        val payload = "6103" + data.joinToString("") { "%02X".format(it) }

        val hex = ObdResponseParser.normalizeElm327Response(elmFrames(payload))!!
        val (mode, pid, bytes) = Decoder.parseObdPayloadHex(hex)!!
        assertEquals("21", mode)
        assertEquals("03", pid)
        assertEquals(61, bytes.size)

        var v = 0L
        for (i in 57..60) v = (v shl 8) or (bytes[i].toLong() and 0xFF)
        assertEquals(186_000.48, v * 0.001, 0.001)
    }

    @Test
    fun `single frame responses are unaffected`() {
        assertEquals("410C0D2A", ObdResponseParser.normalizeElm327Response("410C0D2A\r\r>"))
    }

    @Test
    fun `two ECUs answering a broadcast are still concatenated`() {
        // ATSH7DF 로 나간 010C 에 두 ECU가 답하면 두 줄이 온다.
        assertEquals(
            "410C0D2A410C0D2A",
            ObdResponseParser.normalizeElm327Response("410C0D2A\r410C0D2A\r>"),
        )
    }
}

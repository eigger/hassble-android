package dev.eigger.hassble.decode

import dev.eigger.hassble.config.DataType
import dev.eigger.hassble.config.DecodeConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * OBD 응답의 뒤쪽 바이트를 offset으로 읽는 경로.
 * formula 변수(a~t)는 앞 20바이트까지만 닿으므로, 현대 21 03 블록의
 * byte 57~60 총주행거리 같은 값은 decode로만 읽을 수 있다.
 */
class ObdDecodeConfigTest {

    /** 현대 21 03 응답 모양: 62바이트 payload에 오도미터 3종을 심는다. */
    private fun hyundai2103Payload(
        odoAtRegenMeters: Long,
        distSinceRegenMeters: Long,
        odoMeters: Long,
    ): ByteArray {
        val data = ByteArray(62)
        fun put(offset: Int, v: Long) {
            for (i in 0 until 4) data[offset + i] = ((v shr (8 * (3 - i))) and 0xFF).toByte()
        }
        put(49, odoAtRegenMeters)
        put(53, distSinceRegenMeters)
        put(57, odoMeters)
        return data
    }

    private val odometer = DecodeConfig(
        offset = 57, length = 4, type = DataType.uint32, scale = 0.001,
    )

    @Test
    fun `reads the odometer from byte 57`() {
        val data = hyundai2103Payload(
            odoAtRegenMeters = 186_000_000,
            distSinceRegenMeters = 500_000,
            odoMeters = 186_500_000,
        )
        assertEquals(186_500.0, Decoder.decodeStructured(data, odometer) as Double, 0.001)
    }

    @Test
    fun `the three odometer fields stay independent`() {
        val data = hyundai2103Payload(186_000_000, 500_000, 186_500_000)
        val atRegen = DecodeConfig(offset = 49, length = 4, type = DataType.uint32, scale = 0.001)
        val since = DecodeConfig(offset = 53, length = 4, type = DataType.uint32, scale = 0.001)

        val a = Decoder.decodeStructured(data, atRegen) as Double
        val s = Decoder.decodeStructured(data, since) as Double
        val o = Decoder.decodeStructured(data, odometer) as Double

        assertEquals(186_000.0, a, 0.001)
        assertEquals(500.0, s, 0.001)
        // 재생 시점 주행거리 + 재생 후 거리 = 현재 주행거리
        assertEquals(o, a + s, 0.001)
    }

    @Test
    fun `short response yields null instead of a wrong value`() {
        // 가솔린처럼 21 03 프레임이 짧으면 byte 57에 닿지 못한다.
        val short = ByteArray(40)
        assertNull(Decoder.decodeStructured(short, odometer))
    }

    @Test
    fun `full 32-bit range is unsigned`() {
        val data = ByteArray(62) { 0 }
        for (i in 57..60) data[i] = 0xFF.toByte()
        assertEquals(4_294_967.295, Decoder.decodeStructured(data, odometer) as Double, 0.001)
    }
}

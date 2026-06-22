package dev.eigger.hassble.decode

import dev.eigger.hassble.config.DataType
import dev.eigger.hassble.config.DecodeConfig
import dev.eigger.hassble.config.Endian
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class JaaleeDecodeTest {

    /** 실기기 DF:06:0F:CF:7C:33 manufacturer payload (company ID 제외). */
    private val manufacturerHex = "0215ebefd08370a247c89837e7b5634df52567ed9280cc4d"

    @Test
    fun `jaalee temperature humidity battery decode from manufacturer data`() {
        val bytes = Decoder.hexToBytes(manufacturerHex)!!
        assertEquals(24, bytes.size)

        val temp = Decoder.decodeStructured(
            bytes,
            DecodeConfig(
                offset = 18,
                length = 2,
                type = DataType.uint16,
                endian = Endian.big,
                scale = 0.002670288,
                offsetValue = -45.0,
            ),
        ) as Double
        val humidity = Decoder.decodeStructured(
            bytes,
            DecodeConfig(
                offset = 20,
                length = 2,
                type = DataType.uint16,
                endian = Endian.big,
                scale = 0.001524504,
            ),
        ) as Double
        val battery = Decoder.decodeStructured(
            bytes,
            DecodeConfig(offset = 23, length = 1, type = DataType.uint8),
        )

        assertNotNull(battery)
        // 0x67ED=26605 → (26605/65535)*175-45 ≈ 26.0°C
        assertEquals(26.0, temp, 0.5)
        // 0x9280=37504 → (37504/65535)*100 ≈ 57.2%
        assertEquals(57.2, humidity, 1.0)
        assertEquals(77.0, (battery as Number).toDouble(), 0.01)
    }

    /** 사용자가 HA에서 캡처한 실광고 manufacturer payload (company ID 0x4C 제외). */
    private val liveManufacturerHex = "0215ebefd08370a247c89837e7b5634df5256abaffffcc64"

    @Test
    fun `live advertisement decodes temperature humidity battery`() {
        val bytes = Decoder.hexToBytes(liveManufacturerHex)!!
        assertEquals(24, bytes.size)
        // device 시그니처: bytes[16..17] == 0xF525
        assertEquals(0xF525, ((bytes[16].toInt() and 0xFF) shl 8) or (bytes[17].toInt() and 0xFF))

        val temp = Decoder.decodeStructured(
            bytes,
            DecodeConfig(offset = 18, length = 2, type = DataType.uint16, endian = Endian.big, scale = 0.002670288, offsetValue = -45.0),
        ) as Double
        val humidity = Decoder.decodeStructured(
            bytes,
            DecodeConfig(offset = 20, length = 2, type = DataType.uint16, endian = Endian.big, scale = 0.001524504),
        ) as Double
        val battery = Decoder.decodeStructured(
            bytes,
            DecodeConfig(offset = 23, length = 1, type = DataType.uint8),
        )

        // 0x6ABA=27322 → 27322*0.002670288-45 ≈ 27.95°C
        assertEquals(27.95, temp, 0.1)
        // 0xFFFF=65535 → 65535*0.001524504 ≈ 99.9%
        assertEquals(99.9, humidity, 0.2)
        // 0x64 = 100%
        assertEquals(100.0, (battery as Number).toDouble(), 0.01)
    }
}

package dev.eigger.hassble.ble

import dev.eigger.hassble.config.MatchConfig
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdvertisementMatcherTest {

    private val jaaleeManufacturer = hexToBytes(
        "0215ebefd08370a247c89837e7b5634df52567ed9280cc4d",
    )

    @Test
    fun `jaalee requires both service uuid and manufacturer id`() {
        val match = MatchConfig(serviceDataUuid = "F525", manufacturerId = 0x004C)
        assertTrue(
            AdvertisementMatcher.matches(
                match,
                "AA:BB:CC:DD:EE:FF",
                "",
                hasServiceUuid = { it.equals("F525", ignoreCase = true) },
                manufacturerPayload = { if (it == 0x004C) jaaleeManufacturer else null },
            ),
        )
    }

    @Test
    fun `apple manufacturer alone does not match jaalee`() {
        val match = MatchConfig(serviceDataUuid = "F525", manufacturerId = 0x004C)
        assertFalse(
            AdvertisementMatcher.matches(
                match,
                "AA:BB:CC:DD:EE:FF",
                "",
                hasServiceUuid = { false },
                manufacturerPayload = { if (it == 0x004C) jaaleeManufacturer else null },
            ),
        )
    }

    @Test
    fun `f525 service alone does not match jaalee`() {
        val match = MatchConfig(serviceDataUuid = "F525", manufacturerId = 0x004C)
        assertFalse(
            AdvertisementMatcher.matches(
                match,
                "AA:BB:CC:DD:EE:FF",
                "",
                hasServiceUuid = { true },
                manufacturerPayload = { null },
            ),
        )
    }

    @Test
    fun `f51c service with jaalee manufacturer matches`() {
        val match = MatchConfig(serviceDataUuid = "F51C", manufacturerId = 0x004C)
        assertTrue(
            AdvertisementMatcher.matches(
                match,
                "DF:06:0F:CF:7C:33",
                "",
                hasServiceUuid = { it.equals("F51C", ignoreCase = true) },
                manufacturerPayload = { if (it == 0x004C) jaaleeManufacturer else null },
            ),
        )
    }

    @Test
    fun `jaalee fingerprint without service uuid`() {
        val match = MatchConfig(
            manufacturerId = 0x004C,
            manufacturerHexPrefix = "0215EBEF",
            manufacturerMinLength = 24,
        )
        assertTrue(
            AdvertisementMatcher.matches(
                match,
                "DF:06:0F:CF:7C:33",
                "",
                hasServiceUuid = { false },
                manufacturerPayload = { if (it == 0x004C) jaaleeManufacturer else null },
            ),
        )
        assertFalse(
            AdvertisementMatcher.matches(
                match,
                "DF:06:0F:CF:7C:33",
                "",
                hasServiceUuid = { false },
                manufacturerPayload = { if (it == 0x004C) byteArrayOf(0x02, 0x15, 0x01) else null },
            ),
        )
    }

    @Test
    fun `live jaalee advertisement matches full config`() {
        // 사용자가 HA에서 캡처한 실광고: manufacturer 0x4C payload + service_data F51C
        val liveManufacturer = hexToBytes("0215ebefd08370a247c89837e7b5634df5256abaffffcc64")
        val match = MatchConfig(
            serviceDataUuid = "F51C",
            manufacturerId = 0x004C,
            manufacturerHexPrefix = "0215EBEF",
            manufacturerMinLength = 24,
        )
        assertTrue(
            AdvertisementMatcher.matches(
                match,
                "D2:81:A6:4A:50:DD",
                "",
                hasServiceUuid = { it.equals("F51C", ignoreCase = true) },
                manufacturerPayload = { if (it == 0x004C) liveManufacturer else null },
            ),
        )
    }

    @Test
    fun `single manufacturer criterion`() {
        val match = MatchConfig(manufacturerId = 0x035D)
        assertTrue(
            AdvertisementMatcher.matches(
                match,
                "AA:BB:CC:DD:EE:FF",
                "",
                hasServiceUuid = { false },
                manufacturerPayload = { if (it == 0x035D) byteArrayOf(1, 2, 3) else null },
            ),
        )
    }

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}

package dev.eigger.hassble.ble

import dev.eigger.hassble.config.MatchConfig
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdvertisementMatcherTest {

    @Test
    fun `jaalee requires both service uuid and manufacturer id`() {
        val match = MatchConfig(serviceDataUuid = "F525", manufacturerId = 0x004C)
        assertTrue(
            AdvertisementMatcher.matches(
                match,
                "AA:BB:CC:DD:EE:FF",
                "",
                hasServiceUuid = { it.equals("F525", ignoreCase = true) },
                hasManufacturerId = { it == 0x004C },
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
                hasManufacturerId = { it == 0x004C },
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
                hasManufacturerId = { false },
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
                hasManufacturerId = { it == 0x035D },
            ),
        )
    }
}

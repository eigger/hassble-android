package dev.eigger.hassble.config

import dev.eigger.hassble.ble.AdvertisementCapture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class AdvDeviceBuilderTest {

    @Test
    fun `suggestMatch uses manufacturer and service uuid`() {
        val capture = AdvertisementCapture.CapturedAdvertisement(
            name = "Jaalee",
            address = "AA:BB:CC:DD:EE:FF",
            rssi = -60,
            manufacturerId = 76,
            manufacturerHex = "0215" + "00".repeat(22),
            serviceUuid = "F51C",
            serviceDataHex = "0102",
            fullScanHex = null,
        )
        val match = AdvDeviceBuilder.suggestMatch(capture, useNamePrefix = true)
        assertEquals(76, match.manufacturerId)
        assertEquals("F51C", match.serviceDataUuid)
        assertEquals("Jaalee", match.namePrefix)
        assertNotNull(match.manufacturerMinLength)
    }

    @Test
    fun `build device includes sensors`() {
        val device = AdvDeviceBuilder.build(
            id = "test_dev",
            name = "Test",
            match = MatchConfig(manufacturerId = 76),
            sensors = listOf(
                AdvDeviceBuilder.SensorDraft(
                    key = "temp",
                    sourceField = SourceField.manufacturer_data,
                    decode = DecodeConfig(offset = 0, length = 2, type = DataType.int16, scale = 0.1),
                    unit = "°C",
                    deviceClass = "temperature",
                ),
            ),
        )
        assertEquals("test_dev", device.id)
        assertEquals(1, device.sensors.size)
    }

    @Test
    fun `build text_sensor omits unit and state_class`() {
        val device = AdvDeviceBuilder.build(
            id = "str_dev",
            name = "String Dev",
            match = MatchConfig(manufacturerId = 76),
            sensors = listOf(
                AdvDeviceBuilder.SensorDraft(
                    key = "code",
                    platform = "text_sensor",
                    sourceField = SourceField.manufacturer_data,
                    decode = DecodeConfig(offset = 0, length = 2, type = DataType.string),
                    exactLength = 24,
                ),
            ),
        )
        val s = device.sensors.single()
        assertEquals("text_sensor", s.platform)
        assertEquals(null, s.unit)
        assertEquals(null, s.stateClass)
        assertEquals(24, s.length)
        assertEquals(2, s.minLength)
    }
}

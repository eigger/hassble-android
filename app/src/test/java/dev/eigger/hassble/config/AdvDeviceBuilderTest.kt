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
        assertEquals(2, device.sensors[0].minLength)
    }
}

package dev.eigger.hassble.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdvertisementFormatGrouperTest {

    private fun sample(mac: String, rssi: Int, mfrHex: String) = AdvertisementCapture.CapturedAdvertisement(
        name = "Dev-$mac",
        address = mac,
        rssi = rssi,
        manufacturerId = 0x004C,
        manufacturerHex = mfrHex,
        serviceUuid = "F51C",
        serviceDataHex = "0102",
        fullScanHex = null,
    )

    @Test
    fun `groups same format and picks best rssi representative`() {
        val a = sample("AA:BB:CC:DD:EE:01", -55, "0215" + "AA".repeat(20))
        val b = sample("AA:BB:CC:DD:EE:02", -72, "0215" + "AA".repeat(20))
        val groups = AdvertisementFormatGrouper.groupByFormat(listOf(a, b))
        assertEquals(1, groups.size)
        assertEquals(-55, groups[0].representative.rssi)
        assertEquals(2, groups[0].deviceCount)
    }

    @Test
    fun `nearby filter keeps strong signals`() {
        val strong = sample("AA:01", -50, "0215")
        val weak = sample("AA:02", -80, "0215")
        val nearby = AdvertisementFormatGrouper.filterNearby(listOf(strong, weak), marginDb = 12)
        assertEquals(1, nearby.size)
        assertTrue(nearby.first().rssi >= -62)
    }
}

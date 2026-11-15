package dev.eigger.hassble.ble

import dev.eigger.hassble.config.ConfigTemplates
import dev.eigger.hassble.config.DeviceConfig
import dev.eigger.hassble.config.GattConfig
import dev.eigger.hassble.config.ObdConfig
import dev.eigger.hassble.config.Source
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BindScanFilterTest {
    private fun hex(s: String) = AdvertisementMatcherHex.parse(s)

    private val templates by lazy { ConfigTemplates.fromYaml(File("src/main/assets/templates.yaml").readText()) }
    private val bm7 by lazy { templates.find("ancel_bm7")!!.device }
    private val bm6 by lazy { templates.find("ancel_bm6")!!.device }

    // 참조 HA 통합 manifest의 iBeacon manufacturer_data_start (Apple 0x004C)
    private val ibeacon = hex("02153BA29CD9A42C894856BADAF2606EF777116400" + "00CD")
    private val noMfr: (Int) -> ByteArray? = { null }

    @Test
    fun `bm templates match ibeacon advertisement regardless of name`() {
        val mfr: (Int) -> ByteArray? = { if (it == 76) ibeacon else null }
        assertTrue(BindScanFilter.matches(bm7, "E0:4E:7A:00:00:01", "Unknown", emptyList(), mfr))
        assertTrue(BindScanFilter.matches(bm6, "E0:4E:7A:00:00:02", "Unknown", emptyList(), mfr))
    }

    @Test
    fun `bm templates reject other ibeacons`() {
        val other = hex("0215" + "11".repeat(16) + "000000C5")
        val mfr: (Int) -> ByteArray? = { if (it == 76) other else null }
        assertFalse(BindScanFilter.matches(bm7, "AA:BB:CC:DD:EE:FF", "Unknown", emptyList(), mfr))
    }

    @Test
    fun `bm7 matches by advertised name or fff0 service uuid`() {
        assertTrue(BindScanFilter.matches(bm7, "A", "BM300 Pro", emptyList(), noMfr))
        assertTrue(BindScanFilter.matches(bm7, "A", "Unknown", listOf("0000fff0-0000-1000-8000-00805f9b34fb"), noMfr))
        assertFalse(BindScanFilter.matches(bm7, "A", "Phone", listOf("0000180F-0000-1000-8000-00805F9B34FB"), noMfr))
    }

    @Test
    fun `gatt device without match keeps old uuid or name behaviour`() {
        val d = DeviceConfig(
            id = "m", name = "Meter", source = Source.gatt_notify,
            gatt = GattConfig(serviceUuid = "ffe0", notifyCharUuid = "ffe1"),
        )
        assertTrue(BindScanFilter.matches(d, "A", "x", listOf("0000FFE0-0000-1000-8000-00805F9B34FB"), noMfr))
        assertTrue(BindScanFilter.matches(d, "A", "My Meter 2", emptyList(), noMfr))
        assertFalse(BindScanFilter.matches(d, "A", "Other", emptyList(), noMfr))
    }

    @Test
    fun `obd device uses obd service uuid`() {
        val d = DeviceConfig(id = "car", name = "Car", source = Source.obd, obd = ObdConfig(serviceUuid = "18F0"))
        assertTrue(BindScanFilter.matches(d, "A", "vLinker", listOf("000018F0-0000-1000-8000-00805F9B34FB"), noMfr))
        assertFalse(BindScanFilter.matches(d, "A", "vLinker", emptyList(), noMfr))
    }

    @Test
    fun `no uuid and no match shows everything`() {
        val d = DeviceConfig(id = "x", name = "X", source = Source.advertisement)
        assertTrue(BindScanFilter.matches(d, "A", "anything", emptyList(), noMfr))
    }
}

private object AdvertisementMatcherHex {
    fun parse(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}

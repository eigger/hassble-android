package dev.eigger.hassble.ble

import dev.eigger.hassble.config.DeviceConfig
import dev.eigger.hassble.config.GatewayConfig
import dev.eigger.hassble.config.GattConfig
import dev.eigger.hassble.config.ObdConfig
import dev.eigger.hassble.config.Source
import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectionWaitMacsTest {
    private val obd = DeviceConfig(id = "car", name = "car", source = Source.obd, obd = ObdConfig(mac = "aa-bb-cc-dd-ee-01"))
    private val gatt = DeviceConfig(
        id = "lock", name = "lock", source = Source.gatt_notify,
        gatt = GattConfig(mac = "AA:BB:CC:DD:EE:02", serviceUuid = "FFF0", notifyCharUuid = "FFF1"),
    )
    private val adv = DeviceConfig(id = "door", name = "door", source = Source.advertisement)
    private val config = GatewayConfig(devices = listOf(obd, gatt, adv))

    @Test
    fun `obd and gatt macs are normalized and advertisement devices are ignored`() {
        assertEquals(
            setOf("AA:BB:CC:DD:EE:01", "AA:BB:CC:DD:EE:02"),
            ConnectionWaitMacs.of(config, emptyMap(), emptySet()),
        )
    }

    @Test
    fun `bound mac overrides the configured one`() {
        assertEquals(
            setOf("11:22:33:44:55:66", "AA:BB:CC:DD:EE:02"),
            ConnectionWaitMacs.of(config, mapOf("car" to "11:22:33:44:55:66"), emptySet()),
        )
    }

    @Test
    fun `auto-connect disabled devices and blank macs are skipped`() {
        assertEquals(setOf("AA:BB:CC:DD:EE:02"), ConnectionWaitMacs.of(config, emptyMap(), setOf("car")))
        val noMac = config.copy(devices = listOf(obd.copy(obd = ObdConfig(mac = null))))
        assertEquals(emptySet<String>(), ConnectionWaitMacs.of(noMac, emptyMap(), emptySet()))
    }
}

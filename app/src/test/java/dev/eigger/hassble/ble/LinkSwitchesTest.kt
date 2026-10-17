package dev.eigger.hassble.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkSwitchesTest {
    @Test
    fun `unique ids round trip through parse`() {
        assertEquals("my_obd" to LinkSwitches.Kind.Connection, LinkSwitches.parse(LinkSwitches.connectionUid("my_obd")))
        assertEquals("my_obd" to LinkSwitches.Kind.AutoConnect, LinkSwitches.parse(LinkSwitches.autoConnectUid("my_obd")))
    }

    @Test
    fun `auto_connect is not mistaken for connection and non switch ids are ignored`() {
        assertEquals("car" to LinkSwitches.Kind.AutoConnect, LinkSwitches.parse("car_auto_connect"))
        assertNull(LinkSwitches.parse("car_rpm"))
        assertNull(LinkSwitches.parse("_connection"))
    }

    @Test
    fun `connection switch is on while connecting so automations can cancel`() {
        for (s in listOf(DeviceLinkState.Scanning, DeviceLinkState.Connecting, DeviceLinkState.Connected, DeviceLinkState.Polling)) {
            assertTrue("$s", LinkSwitches.isActive(s))
        }
        for (s in listOf(DeviceLinkState.Disconnected, DeviceLinkState.Error, null)) {
            assertFalse("$s", LinkSwitches.isActive(s))
        }
    }
}

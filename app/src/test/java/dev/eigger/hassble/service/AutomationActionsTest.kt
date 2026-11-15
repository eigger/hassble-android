package dev.eigger.hassble.service

import dev.eigger.hassble.ble.DeviceLinkState
import dev.eigger.hassble.ble.DeviceLinkStatus
import dev.eigger.hassble.config.BleScanModeOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AutomationActionsTest {

    @Test
    fun `parseScanMode accepts enum names case-insensitively`() {
        assertEquals(BleScanModeOption.LOW_POWER, AutomationActions.parseScanMode("LOW_POWER"))
        assertEquals(BleScanModeOption.BALANCED, AutomationActions.parseScanMode("balanced"))
        assertEquals(BleScanModeOption.LOW_LATENCY, AutomationActions.parseScanMode(" Low_Latency "))
    }

    @Test
    fun `parseScanMode accepts aliases and separators`() {
        assertEquals(BleScanModeOption.LOW_POWER, AutomationActions.parseScanMode("power_saving"))
        assertEquals(BleScanModeOption.LOW_LATENCY, AutomationActions.parseScanMode("high_performance"))
        assertEquals(BleScanModeOption.LOW_LATENCY, AutomationActions.parseScanMode("low-latency"))
        assertEquals(BleScanModeOption.LOW_POWER, AutomationActions.parseScanMode("low power"))
    }

    @Test
    fun `parseScanMode rejects unknown or empty`() {
        assertNull(AutomationActions.parseScanMode(null))
        assertNull(AutomationActions.parseScanMode(""))
        assertNull(AutomationActions.parseScanMode("turbo"))
    }

    @Test
    fun `manifest declares every public action on AutomationReceiver`() {
        val content = File("src/main/AndroidManifest.xml").readText()
        assertTrue(content.contains(".service.AutomationReceiver"))
        AutomationActions.ALL.forEach { assertTrue("missing $it", content.contains("\"$it\"")) }
    }

    private val id = "bm7"
    private fun st(state: DeviceLinkState, last: Long? = null, err: String? = null) =
        DeviceLinkStatus(id, state, "AA:BB", last, err)

    @Test
    fun `poll finished on fresh Idle success`() {
        assertTrue(isPollFinished(st(DeviceLinkState.Idle, 2000), null, 1000))
    }

    @Test
    fun `stale Idle from before the request is ignored`() {
        val old = st(DeviceLinkState.Idle, 500)
        assertFalse(isPollFinished(old, old, 1000))
        assertFalse(isPollFinished(st(DeviceLinkState.Idle, 500), null, 1000))
    }

    @Test
    fun `notify device finishes on first data`() {
        assertTrue(isPollFinished(st(DeviceLinkState.Polling, 1500), null, 1000))
        assertFalse(isPollFinished(st(DeviceLinkState.Connecting), null, 1000))
    }

    @Test
    fun `failure with error message finishes but plain disconnect does not`() {
        assertTrue(isPollFinished(st(DeviceLinkState.Disconnected, err = "no response"), null, 1000))
        assertFalse(isPollFinished(st(DeviceLinkState.Disconnected), null, 1000))
        assertFalse(isPollFinished(null, null, 1000))
    }
}

package dev.eigger.hassble.service

import dev.eigger.hassble.config.BleScanModeOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskerSettingTest {
    private fun roundTrip(s: TaskerSetting): TaskerSetting? {
        val v = s.toValues()
        return TaskerSetting.fromValues(
            version = v[TaskerSetting.KEY_VERSION] as Int?,
            command = v[TaskerSetting.KEY_COMMAND] as String?,
            mode = v[TaskerSetting.KEY_MODE] as String?,
            deviceId = v[TaskerSetting.KEY_DEVICE_ID] as String?,
            stopAfter = v[TaskerSetting.KEY_STOP_AFTER] as Boolean? ?: false,
        )
    }

    @Test
    fun roundTripAllCommands() {
        val all = listOf(
            TaskerSetting(TaskerCommand.START_GATEWAY),
            TaskerSetting(TaskerCommand.STOP_GATEWAY),
            TaskerSetting(TaskerCommand.SET_SCAN_MODE, mode = BleScanModeOption.LOW_POWER),
            TaskerSetting(TaskerCommand.POLL_DEVICE, deviceId = "my_device", stopAfter = true),
            TaskerSetting(TaskerCommand.POLL_DEVICE, deviceId = "other"),
        )
        all.forEach { assertEquals(it, roundTrip(it)) }
    }

    @Test
    fun valuesContainVersionAndOnlyRelevantKeys() {
        val v = TaskerSetting(TaskerCommand.START_GATEWAY).toValues()
        assertEquals(setOf(TaskerSetting.KEY_VERSION, TaskerSetting.KEY_COMMAND), v.keys)
        assertEquals(TaskerSetting.CURRENT_VERSION, v[TaskerSetting.KEY_VERSION])
    }

    @Test
    fun rejectsMissingOrFutureVersion() {
        assertNull(TaskerSetting.fromValues(null, "start_gateway", null, null, false))
        assertNull(TaskerSetting.fromValues(0, "start_gateway", null, null, false))
        assertNull(TaskerSetting.fromValues(TaskerSetting.CURRENT_VERSION + 1, "start_gateway", null, null, false))
    }

    @Test
    fun rejectsUnknownCommand() {
        assertNull(TaskerSetting.fromValues(1, "reboot", null, null, false))
        assertNull(TaskerSetting.fromValues(1, null, null, null, false))
    }

    @Test
    fun scanModeRequiresValidMode() {
        assertNull(TaskerSetting.fromValues(1, "set_scan_mode", null, null, false))
        assertNull(TaskerSetting.fromValues(1, "set_scan_mode", "TURBO", null, false))
        assertEquals(
            BleScanModeOption.LOW_LATENCY,
            TaskerSetting.fromValues(1, "set_scan_mode", "low-latency", null, false)?.mode,
        )
    }

    @Test
    fun pollRequiresDeviceIdAndTrims() {
        assertNull(TaskerSetting.fromValues(1, "poll_device", null, null, true))
        assertNull(TaskerSetting.fromValues(1, "poll_device", null, "   ", true))
        val s = TaskerSetting.fromValues(1, "poll_device", null, "  dev1 ", true)
        assertEquals("dev1", s?.deviceId)
        assertTrue(s?.stopAfter == true)
    }

    @Test
    fun toRequestMapsToPublicActions() {
        val r = TaskerSetting(TaskerCommand.POLL_DEVICE, deviceId = "d", stopAfter = true).toRequest()
        assertEquals(AutomationActions.ACTION_POLL_DEVICE, r.action)
        assertEquals("d", r.deviceId)
        assertTrue(r.stopAfter)
        val m = TaskerSetting(TaskerCommand.SET_SCAN_MODE, mode = BleScanModeOption.BALANCED).toRequest()
        assertEquals(AutomationActions.ACTION_SET_SCAN_MODE, m.action)
        assertEquals("BALANCED", m.mode)
        assertEquals(AutomationActions.ACTION_START_GATEWAY, TaskerSetting(TaskerCommand.START_GATEWAY).toRequest().action)
        assertEquals(AutomationActions.ACTION_STOP_GATEWAY, TaskerSetting(TaskerCommand.STOP_GATEWAY).toRequest().action)
    }

    @Test
    fun blurbText() {
        val cmd = { c: TaskerCommand ->
            when (c) {
                TaskerCommand.START_GATEWAY -> "Start gateway"
                TaskerCommand.STOP_GATEWAY -> "Stop gateway"
                TaskerCommand.SET_SCAN_MODE -> "Set scan mode"
                TaskerCommand.POLL_DEVICE -> "Poll"
            }
        }
        val mode = { m: BleScanModeOption -> m.label }
        assertEquals("Start gateway", TaskerSetting(TaskerCommand.START_GATEWAY).blurb(cmd, mode, "stop after"))
        assertEquals("Stop gateway", TaskerSetting(TaskerCommand.STOP_GATEWAY).blurb(cmd, mode, "stop after"))
        assertEquals(
            "Set scan mode: Balanced",
            TaskerSetting(TaskerCommand.SET_SCAN_MODE, mode = BleScanModeOption.BALANCED).blurb(cmd, mode, "stop after"),
        )
        assertEquals(
            "Poll: my_device (stop after)",
            TaskerSetting(TaskerCommand.POLL_DEVICE, deviceId = "my_device", stopAfter = true).blurb(cmd, mode, "stop after"),
        )
        assertEquals(
            "Poll: my_device",
            TaskerSetting(TaskerCommand.POLL_DEVICE, deviceId = "my_device").blurb(cmd, mode, "stop after"),
        )
    }

    @Test
    fun blurbIsTruncated() {
        val long = "x".repeat(500)
        val b = TaskerSetting(TaskerCommand.POLL_DEVICE, deviceId = long).blurb({ "Poll" }, { it.label }, "s")
        assertEquals(120, b.length)
    }

    @Test
    fun requestedTimeouts() {
        assertEquals(15_000, TaskerSetting(TaskerCommand.START_GATEWAY).requestedTimeoutMs())
        assertEquals(90_000, TaskerSetting(TaskerCommand.POLL_DEVICE, deviceId = "d").requestedTimeoutMs())
        assertEquals(0, TaskerSetting(TaskerCommand.STOP_GATEWAY).requestedTimeoutMs())
        assertEquals(0, TaskerSetting(TaskerCommand.SET_SCAN_MODE, mode = BleScanModeOption.LOW_POWER).requestedTimeoutMs())
        // Tasker 상한(3599s)을 넘지 않는다.
        assertTrue(TaskerSetting.POLL_TIMEOUT_MS <= TaskerPlugin.REQUESTED_TIMEOUT_MS_MAX)
        // 서비스/실행기 대기 합(시작 대기 + poll 상한)보다 길어야 Tasker가 먼저 포기하지 않는다.
        assertTrue(TaskerSetting.POLL_TIMEOUT_MS > AutomationExecutor.RUNTIME_WAIT_TIMEOUT_MS + AutomationActions.STOP_AFTER_TIMEOUT_MS)
        assertTrue(TaskerSetting.START_TIMEOUT_MS > AutomationExecutor.START_READY_TIMEOUT_MS)
    }

    @Test
    fun taskerConstantsMatchOfficialLibrary() {
        assertEquals("net.dinglisch.android.tasker.extras.REQUESTED_TIMEOUT", TaskerPlugin.EXTRA_REQUESTED_TIMEOUT)
        assertEquals("net.dinglisch.android.tasker.extras.COMPLETION_INTENT", TaskerPlugin.EXTRA_COMPLETION_INTENT)
        assertEquals("net.dinglisch.android.tasker.extras.RESULT_CODE", TaskerPlugin.EXTRA_RESULT_CODE)
        assertEquals("net.dinglisch.android.tasker.extras.HOST_CAPABILITIES", TaskerPlugin.EXTRA_HOST_CAPABILITIES)
        assertEquals("net.dinglisch.android.tasker.extras.VARIABLES", TaskerPlugin.EXTRA_VARIABLES_BUNDLE)
        assertEquals(-1, TaskerPlugin.RESULT_CODE_OK)
        assertEquals(2, TaskerPlugin.RESULT_CODE_FAILED)
        assertEquals(3, TaskerPlugin.RESULT_CODE_PENDING)
    }

    @Test
    fun hostCapabilityFlags() {
        val sync = TaskerPlugin.CAPABILITY_SETTING_SYNCHRONOUS_EXECUTION
        assertTrue(TaskerPlugin.hostSupportsFlags(sync or 2, sync))
        assertFalse(TaskerPlugin.hostSupportsFlags(2 or 4 or 8 or 16, sync))
        assertFalse(TaskerPlugin.hostSupportsFlags(0, sync))
    }

    @Test
    fun resultCodesAndVariables() {
        assertEquals(TaskerPlugin.RESULT_CODE_OK, TaskerPlugin.resultCodeFor(true))
        assertEquals(TaskerPlugin.RESULT_CODE_FAILED, TaskerPlugin.resultCodeFor(false))
        val ok = TaskerPlugin.variablesFor(AutomationResult(true, "Poll finished"))
        assertEquals(mapOf("%hb_result" to "Poll finished"), ok)
        val bad = TaskerPlugin.variablesFor(AutomationResult(false, "Gateway is not running"))
        assertEquals("Gateway is not running", bad["%errmsg"])
        assertEquals("Gateway is not running", bad["%hb_result"])
    }
}

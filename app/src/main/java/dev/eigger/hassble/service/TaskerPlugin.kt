package dev.eigger.hassble.service

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import dev.eigger.hassble.config.BleScanModeOption
import java.net.URISyntaxException

/**
 * Locale/Tasker 플러그인 API 상수와 동기 완료 헬퍼. 서드파티 라이브러리 없이 필요한 부분만 옮겼다.
 * 값은 Tasker 공식 TaskerPlugin.java(joaoapps) 기준.
 */
internal object TaskerPlugin {
    // Locale 공통
    const val ACTION_EDIT_SETTING = "com.twofortyfouram.locale.intent.action.EDIT_SETTING"
    const val ACTION_FIRE_SETTING = "com.twofortyfouram.locale.intent.action.FIRE_SETTING"
    const val EXTRA_BUNDLE = "com.twofortyfouram.locale.intent.extra.BUNDLE"
    const val EXTRA_STRING_BLURB = "com.twofortyfouram.locale.intent.extra.BLURB"

    // Tasker 확장
    private const val EXTRAS_PREFIX = "net.dinglisch.android.tasker.extras."
    const val EXTRA_HOST_CAPABILITIES = EXTRAS_PREFIX + "HOST_CAPABILITIES"
    const val EXTRA_REQUESTED_TIMEOUT = EXTRAS_PREFIX + "REQUESTED_TIMEOUT"
    const val EXTRA_COMPLETION_INTENT = EXTRAS_PREFIX + "COMPLETION_INTENT"
    const val EXTRA_RESULT_CODE = EXTRAS_PREFIX + "RESULT_CODE"
    const val EXTRA_VARIABLES_BUNDLE = EXTRAS_PREFIX + "VARIABLES"

    const val CAPABILITY_SETTING_RETURN_VARIABLES = 2
    const val CAPABILITY_SETTING_SYNCHRONOUS_EXECUTION = 32

    const val RESULT_CODE_OK = Activity.RESULT_OK
    const val RESULT_CODE_FAILED = Activity.RESULT_FIRST_USER + 1
    const val RESULT_CODE_PENDING = Activity.RESULT_FIRST_USER + 2

    const val REQUESTED_TIMEOUT_MS_MAX = 3_599_000

    const val VAR_RESULT = "%hb_result"
    const val VAR_ERROR_MESSAGE = "%errmsg"

    private const val TAG = "HassBleTasker"

    fun hostSupports(extras: Bundle?, flag: Int): Boolean {
        if (extras == null || !extras.containsKey(EXTRA_HOST_CAPABILITIES)) return false
        return hostSupportsFlags(extras.getInt(EXTRA_HOST_CAPABILITIES), flag)
    }

    /** 순수 비트 검사(테스트용). */
    fun hostSupportsFlags(flags: Int, flag: Int): Boolean = (flags and flag) > 0

    fun hostSupportsSynchronousExecution(extras: Bundle?): Boolean = hostSupports(extras, CAPABILITY_SETTING_SYNCHRONOUS_EXECUTION)

    fun hostSupportsVariableReturn(extras: Bundle?): Boolean = hostSupports(extras, CAPABILITY_SETTING_RETURN_VARIABLES)

    fun resultCodeFor(ok: Boolean): Int = if (ok) RESULT_CODE_OK else RESULT_CODE_FAILED

    /** 완료 시 Tasker에 돌려줄 변수. 항상 %hb_result, 실패면 %errmsg도. */
    fun variablesFor(result: AutomationResult): Map<String, String> =
        if (result.ok) mapOf(VAR_RESULT to result.message)
        else mapOf(VAR_RESULT to result.message, VAR_ERROR_MESSAGE to result.message)

    /** Tasker가 이 플러그인 실행 완료를 기다리고 있는지(편집 시 타임아웃을 요청했을 때만 completion intent가 온다). */
    fun hasCompletionIntent(fireIntent: Intent): Boolean = !fireIntent.getStringExtra(EXTRA_COMPLETION_INTENT).isNullOrEmpty()

    /**
     * 공식 `TaskerPlugin.Setting.signalFinish` 동등 구현. 실행이 끝났음을 Tasker에 알린다.
     * [fireIntent]는 FireReceiver가 받은 원본 intent. completion intent가 없으면 false.
     */
    fun signalFinish(context: Context, fireIntent: Intent, ok: Boolean, vars: Map<String, String>? = null): Boolean {
        val uri = fireIntent.getStringExtra(EXTRA_COMPLETION_INTENT) ?: return false
        val completion = try {
            Intent.parseUri(uri, Intent.URI_INTENT_SCHEME)
        } catch (e: URISyntaxException) {
            Log.w(TAG, "signalFinish: bad completion URI")
            return false
        }
        completion.putExtra(EXTRA_RESULT_CODE, resultCodeFor(ok))
        if (!vars.isNullOrEmpty()) {
            completion.putExtra(EXTRA_VARIABLES_BUNDLE, Bundle().apply { vars.forEach { (k, v) -> putString(k, v) } })
        }
        return runCatching { context.sendBroadcast(completion) }
            .onFailure { Log.w(TAG, "signalFinish: failed to send", it) }
            .isSuccess
    }
}

/** 플러그인이 지원하는 명령. [key]는 번들에 저장되는 값이라 바꾸면 기존 Tasker 설정이 깨진다. */
internal enum class TaskerCommand(val key: String, val action: String) {
    START_GATEWAY("start_gateway", AutomationActions.ACTION_START_GATEWAY),
    STOP_GATEWAY("stop_gateway", AutomationActions.ACTION_STOP_GATEWAY),
    SET_SCAN_MODE("set_scan_mode", AutomationActions.ACTION_SET_SCAN_MODE),
    POLL_DEVICE("poll_device", AutomationActions.ACTION_POLL_DEVICE),
    ;

    companion object {
        fun fromKey(key: String?): TaskerCommand? = entries.firstOrNull { it.key == key }
    }
}

/** Tasker 액션 하나의 설정. 번들 입출력·검증·요약 문구를 순수 로직으로 둔다. */
internal data class TaskerSetting(
    val command: TaskerCommand,
    val mode: BleScanModeOption? = null,
    val deviceId: String? = null,
    val stopAfter: Boolean = false,
) {
    fun toRequest(): AutomationRequest = AutomationRequest(
        action = command.action,
        mode = mode?.name,
        deviceId = deviceId,
        stopAfter = stopAfter,
    )

    /** 번들에 넣을 값. 프리미티브만 쓴다(Tasker 요구). */
    fun toValues(): Map<String, Any> = buildMap {
        put(KEY_VERSION, CURRENT_VERSION)
        put(KEY_COMMAND, command.key)
        when (command) {
            TaskerCommand.SET_SCAN_MODE -> put(KEY_MODE, requireNotNull(mode).name)
            TaskerCommand.POLL_DEVICE -> {
                put(KEY_DEVICE_ID, requireNotNull(deviceId))
                put(KEY_STOP_AFTER, stopAfter)
            }
            else -> Unit
        }
    }

    fun toBundle(): Bundle = Bundle().also { b ->
        toValues().forEach { (k, v) ->
            when (v) {
                is Int -> b.putInt(k, v)
                is Boolean -> b.putBoolean(k, v)
                is String -> b.putString(k, v)
            }
        }
    }

    /** Tasker가 동기 실행에서 기다려 줄 시간(ms). 기다릴 필요가 없으면 0. */
    fun requestedTimeoutMs(): Int = when (command) {
        TaskerCommand.START_GATEWAY -> START_TIMEOUT_MS
        TaskerCommand.POLL_DEVICE -> POLL_TIMEOUT_MS
        else -> 0
    }

    /**
     * 편집 화면 요약(blurb). [commandLabel]은 명령 이름(현지화), [modeLabel]은 스캔 모드 이름, [stopAfterLabel]은 "stop after".
     * 예) "Poll: my_device (stop after)".
     */
    fun blurb(commandLabel: (TaskerCommand) -> String, modeLabel: (BleScanModeOption) -> String, stopAfterLabel: String): String {
        val head = commandLabel(command)
        return when (command) {
            TaskerCommand.SET_SCAN_MODE -> "$head: ${mode?.let(modeLabel).orEmpty()}"
            TaskerCommand.POLL_DEVICE -> "$head: ${deviceId.orEmpty()}" + if (stopAfter) " ($stopAfterLabel)" else ""
            else -> head
        }.take(MAX_BLURB_LENGTH)
    }

    companion object {
        const val CURRENT_VERSION = 1
        const val KEY_VERSION = "version"
        const val KEY_COMMAND = "command"
        const val KEY_MODE = "mode"
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_STOP_AFTER = "stop_after"

        const val START_TIMEOUT_MS = 15_000

        // 게이트웨이 시작 대기(15s) + poll 상한(60s) + 여유.
        const val POLL_TIMEOUT_MS = 90_000
        private const val MAX_BLURB_LENGTH = 120

        /** 값 검증. 버전이 없거나 미래 버전이거나, 명령/필수 값이 올바르지 않으면 null. */
        fun fromValues(version: Int?, command: String?, mode: String?, deviceId: String?, stopAfter: Boolean): TaskerSetting? {
            if (version == null || version < 1 || version > CURRENT_VERSION) return null
            val cmd = TaskerCommand.fromKey(command) ?: return null
            return when (cmd) {
                TaskerCommand.START_GATEWAY, TaskerCommand.STOP_GATEWAY -> TaskerSetting(cmd)
                TaskerCommand.SET_SCAN_MODE -> {
                    val parsed = AutomationActions.parseScanMode(mode) ?: return null
                    TaskerSetting(cmd, mode = parsed)
                }
                TaskerCommand.POLL_DEVICE -> {
                    val id = deviceId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
                    TaskerSetting(cmd, deviceId = id, stopAfter = stopAfter)
                }
            }
        }

        fun fromBundle(bundle: Bundle?): TaskerSetting? {
            if (bundle == null) return null
            return runCatching {
                fromValues(
                    version = if (bundle.containsKey(KEY_VERSION)) bundle.getInt(KEY_VERSION) else null,
                    command = bundle.getString(KEY_COMMAND),
                    mode = bundle.getString(KEY_MODE),
                    deviceId = bundle.getString(KEY_DEVICE_ID),
                    stopAfter = bundle.getBoolean(KEY_STOP_AFTER, false),
                )
            }.getOrNull()
        }
    }
}

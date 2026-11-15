package dev.eigger.hassble.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Tasker/Locale "Plugin > HassBle" 액션 실행 수신기. 기존 [AutomationReceiver]와 같은 [AutomationExecutor]를 쓴다.
 *
 * Tasker가 완료를 기다리는 중이면(completion intent가 있으면) 결과 코드를 PENDING으로 돌려주고,
 * goAsync 없이 앱 스코프에서 끝까지 실행한 뒤 [TaskerPlugin.signalFinish]로 알린다.
 */
class TaskerFireReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TaskerPlugin.ACTION_FIRE_SETTING) return
        val setting = TaskerSetting.fromBundle(intent.getBundleExtra(TaskerPlugin.EXTRA_BUNDLE))
        val waiting = TaskerPlugin.hasCompletionIntent(intent)
        val appContext = context.applicationContext
        if (setting == null) {
            Log.w(TAG, "Ignored FIRE_SETTING: invalid or unsupported bundle")
            LiveEventLogger.log(LogType.LINK, "Automation Tasker plugin: invalid or unsupported action configuration - re-open the action in Tasker and save it")
            if (isOrderedBroadcast) resultCode = TaskerPlugin.RESULT_CODE_FAILED
            if (waiting) {
                TaskerPlugin.signalFinish(appContext, intent, false, mapOf(TaskerPlugin.VAR_ERROR_MESSAGE to "Invalid action configuration"))
            }
            return
        }
        val request = setting.toRequest()
        val returnVars = TaskerPlugin.hostSupportsVariableReturn(intent.extras)
        if (waiting && isOrderedBroadcast) {
            resultCode = TaskerPlugin.RESULT_CODE_PENDING
            AutomationExecutor.launchHoldingReceiver(null) {
                val result = AutomationExecutor.execute(appContext, request)
                TaskerPlugin.signalFinish(appContext, intent, result.ok, if (returnVars) TaskerPlugin.variablesFor(result) else null)
            }
        } else {
            AutomationExecutor.launchHoldingReceiver(goAsync()) {
                val result = AutomationExecutor.execute(appContext, request)
                if (waiting) {
                    TaskerPlugin.signalFinish(appContext, intent, result.ok, if (returnVars) TaskerPlugin.variablesFor(result) else null)
                }
            }
        }
    }

    private companion object {
        const val TAG = "HassBleTasker"
    }
}

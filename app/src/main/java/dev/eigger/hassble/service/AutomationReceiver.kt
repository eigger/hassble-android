package dev.eigger.hassble.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Tasker 등 자동화 앱용 공개 브로드캐스트 수신기. 설정의 "Allow automation apps"가 켜져 있을 때만 동작한다.
 * HA URL/토큰은 intent로 받지 않고 항상 저장된 설정을 쓴다. 실제 처리는 [AutomationExecutor].
 */
class AutomationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action !in AutomationActions.ALL) return
        // goAsync 이후에도 안전하도록 필요한 값을 먼저 꺼낸다.
        val request = AutomationRequest(
            action = action,
            mode = intent.getStringExtra(AutomationActions.EXTRA_MODE),
            deviceId = intent.getStringExtra(AutomationActions.EXTRA_DEVICE_ID),
            stopAfter = intent.getBooleanExtra(AutomationActions.EXTRA_STOP_AFTER, false),
        )
        val appContext = context.applicationContext
        AutomationExecutor.launchHoldingReceiver(goAsync()) {
            AutomationExecutor.execute(appContext, request)
        }
    }
}

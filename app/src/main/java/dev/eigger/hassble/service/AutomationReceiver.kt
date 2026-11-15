package dev.eigger.hassble.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.util.Log
import dev.eigger.hassble.config.HassSettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "HassBleAutomation"
private const val AUTOMATION_RECEIVER_TIMEOUT_MS = 10_000L

/**
 * Tasker 등 자동화 앱용 공개 브로드캐스트 수신기. 설정의 "Allow automation apps"가 켜져 있을 때만 동작한다.
 * HA URL/토큰은 intent로 받지 않고 항상 저장된 설정을 쓴다.
 */
class AutomationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action !in AutomationActions.ALL) return
        // goAsync 이후에도 안전하도록 필요한 값을 먼저 꺼낸다.
        val mode = intent.getStringExtra(AutomationActions.EXTRA_MODE)
        val deviceId = intent.getStringExtra(AutomationActions.EXTRA_DEVICE_ID)
        val stopAfter = intent.getBooleanExtra(AutomationActions.EXTRA_STOP_AFTER, false)

        val appContext = context.applicationContext
        val repository = HassSettingsRepository(appContext)
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                withTimeoutOrNull(AUTOMATION_RECEIVER_TIMEOUT_MS) {
                    if (!repository.allowAutomation.first()) {
                        warn("Ignored $action: automation is disabled (Settings > Allow automation apps)")
                        return@withTimeoutOrNull
                    }
                    handle(appContext, repository, action, mode, deviceId, stopAfter)
                } ?: Log.w(TAG, "Timed out handling $action")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to handle $action", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun handle(
        context: Context,
        repository: HassSettingsRepository,
        action: String,
        modeRaw: String?,
        deviceId: String?,
        stopAfter: Boolean,
    ) {
        when (action) {
            AutomationActions.ACTION_START_GATEWAY -> {
                if (BleGatewayService.isServiceRunning.value) {
                    info("START_GATEWAY: already running")
                    return
                }
                when (val outcome = startGatewayFromSavedSettings(context, repository)) {
                    SavedStartOutcome.Started -> info("START_GATEWAY: starting gateway")
                    SavedStartOutcome.SettingsIncomplete ->
                        warn("START_GATEWAY: Home Assistant settings are incomplete - open the app and configure first")
                    is SavedStartOutcome.Failed -> {
                        val ignoring = isIgnoringBatteryOptimizations(context)
                        warn(
                            "START_GATEWAY failed: ${outcome.error.javaClass.simpleName}. Starting a foreground service from the " +
                                "background requires battery optimization exemption (currently exempt: $ignoring) - " +
                                "set HassBle to Unrestricted / Don't optimize and location to Allow all the time",
                        )
                    }
                }
            }
            AutomationActions.ACTION_STOP_GATEWAY -> {
                if (!BleGatewayService.isServiceRunning.value) {
                    info("STOP_GATEWAY: gateway is not running")
                    return
                }
                info("STOP_GATEWAY: stopping gateway")
                BleGatewayService.stop(context)
            }
            AutomationActions.ACTION_SET_SCAN_MODE -> {
                val mode = AutomationActions.parseScanMode(modeRaw)
                if (mode == null) {
                    warn("SET_SCAN_MODE: unknown mode '$modeRaw' (use LOW_POWER, BALANCED, LOW_LATENCY)")
                    return
                }
                // 실행 중인 서비스는 저장소 flow를 구독하므로 저장만으로 즉시 반영된다.
                repository.saveScanMode(mode)
                info("SET_SCAN_MODE: ${mode.name}")
            }
            AutomationActions.ACTION_POLL_DEVICE -> {
                if (deviceId.isNullOrBlank()) {
                    warn("POLL_DEVICE: missing device_id extra")
                    return
                }
                if (!BleGatewayService.isServiceRunning.value) {
                    warn("POLL_DEVICE ignored: gateway is not running (send START_GATEWAY first)")
                    return
                }
                info("POLL_DEVICE: device=$deviceId, stop_after=$stopAfter")
                BleGatewayService.pollDevice(context, deviceId, stopAfter)
            }
        }
    }

    private fun isIgnoringBatteryOptimizations(context: Context): Boolean =
        runCatching {
            (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
                .isIgnoringBatteryOptimizations(context.packageName)
        }.getOrDefault(false)

    private fun info(message: String) {
        Log.i(TAG, message)
        LiveEventLogger.log(LogType.LINK, "Automation $message")
    }

    private fun warn(message: String) {
        Log.w(TAG, message)
        LiveEventLogger.log(LogType.LINK, "Automation $message")
    }
}

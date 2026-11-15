package dev.eigger.hassble.service

import android.content.BroadcastReceiver
import android.content.Context
import android.os.PowerManager
import android.util.Log
import dev.eigger.hassble.config.HassSettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "HassBleAutomation"

/** 자동화 요청 하나(원시 intent·Tasker 플러그인 공통). */
internal data class AutomationRequest(
    val action: String,
    val mode: String? = null,
    val deviceId: String? = null,
    val stopAfter: Boolean = false,
)

/** [ok]=false면 거절/실패/시간 초과. [message]는 로그·Tasker 변수(%hb_result)용 영문 문장. */
internal data class AutomationResult(val ok: Boolean, val message: String)

/**
 * 원시 브로드캐스트 수신기([AutomationReceiver])와 Tasker 플러그인([TaskerFireReceiver])이 공유하는 실행기.
 *
 * 완료까지 기다리는 suspend 함수라 호출자는 [scope]에서 실행해야 한다(수신기는 goAsync 제한 ~10초를 넘길 수 있다).
 */
internal object AutomationExecutor {
    /** 수신기 수명과 무관하게 작업을 이어가기 위한 앱 단위 스코프. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** START: 서비스가 떠서 BleRuntime이 준비될 때까지 기다리는 상한. */
    const val START_READY_TIMEOUT_MS = 12_000L

    /** POLL: 게이트웨이가 시작 중이면 BleRuntime을 기다리는 상한. */
    const val RUNTIME_WAIT_TIMEOUT_MS = 15_000L

    /** POLL: 서비스가 결과를 내지 못할 때를 위한 안전 상한(서비스 쪽 상한 + 여유). */
    private const val POLL_RESULT_TIMEOUT_MS = AutomationActions.STOP_AFTER_TIMEOUT_MS + 5_000L

    /** goAsync 한도(~10초) 직전에 receiver를 풀어주는 시간. */
    private const val RECEIVER_RELEASE_MS = 9_000L

    suspend fun execute(context: Context, request: AutomationRequest): AutomationResult {
        val appContext = context.applicationContext
        val repository = HassSettingsRepository(appContext)
        return try {
            if (!repository.allowAutomation.first()) {
                warn("Ignored ${request.action}: automation is disabled (Settings > Allow automation apps)")
                return AutomationResult(false, "Automation is disabled in HassBle settings")
            }
            when (request.action) {
                AutomationActions.ACTION_START_GATEWAY -> start(appContext, repository)
                AutomationActions.ACTION_STOP_GATEWAY -> stop(appContext)
                AutomationActions.ACTION_SET_SCAN_MODE -> setScanMode(repository, request.mode)
                AutomationActions.ACTION_POLL_DEVICE -> poll(appContext, request.deviceId, request.stopAfter)
                else -> AutomationResult(false, "Unknown action ${request.action}")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle ${request.action}", e)
            AutomationResult(false, "Failed: ${e.javaClass.simpleName}")
        }
    }

    /**
     * 브로드캐스트 수신기에서 [block]을 앱 스코프로 돌린다. [pending]은 끝나거나 ~9초가 지나면(먼저 오는 쪽) 풀어준다.
     * 9초가 지나도 [block]은 계속 돌아간다(START/POLL이 더 오래 걸릴 수 있다).
     */
    fun launchHoldingReceiver(pending: BroadcastReceiver.PendingResult?, block: suspend () -> Unit) {
        val released = AtomicBoolean(false)
        fun release() {
            if (pending != null && released.compareAndSet(false, true)) runCatching { pending.finish() }
        }
        scope.launch {
            try {
                block()
            } finally {
                release()
            }
        }
        if (pending != null) scope.launch {
            delay(RECEIVER_RELEASE_MS)
            release()
        }
    }

    private suspend fun start(context: Context, repository: HassSettingsRepository): AutomationResult {
        if (BleGatewayService.isServiceRunning.value) {
            if (BleGatewayService.runtimeReady.value) {
                info("START_GATEWAY: already running")
                return AutomationResult(true, "Gateway already running")
            }
            info("START_GATEWAY: already starting")
        } else {
            when (val outcome = startGatewayFromSavedSettings(context, repository)) {
                SavedStartOutcome.Started -> info("START_GATEWAY: starting gateway")
                SavedStartOutcome.SettingsIncomplete -> {
                    warn("START_GATEWAY: Home Assistant settings are incomplete - open the app and configure first")
                    return AutomationResult(false, "Home Assistant settings are incomplete")
                }
                is SavedStartOutcome.Failed -> {
                    val ignoring = isIgnoringBatteryOptimizations(context)
                    warn(
                        "START_GATEWAY failed: ${outcome.error.javaClass.simpleName}. Starting a foreground service from the " +
                            "background requires battery optimization exemption (currently exempt: $ignoring) - " +
                            "set HassBle to Unrestricted / Don't optimize and location to Allow all the time",
                    )
                    return AutomationResult(false, "Start blocked by the system (battery optimization exemption required)")
                }
            }
        }
        val ready = withTimeoutOrNull(START_READY_TIMEOUT_MS) { BleGatewayService.runtimeReady.first { it } } != null
        return if (ready) {
            info("START_GATEWAY: gateway ready")
            AutomationResult(true, "Gateway started")
        } else {
            warn("START_GATEWAY: gateway not ready after ${START_READY_TIMEOUT_MS / 1000}s")
            AutomationResult(false, "Gateway not ready after ${START_READY_TIMEOUT_MS / 1000}s")
        }
    }

    private fun stop(context: Context): AutomationResult {
        if (!BleGatewayService.isServiceRunning.value) {
            info("STOP_GATEWAY: gateway is not running")
            return AutomationResult(true, "Gateway was not running")
        }
        info("STOP_GATEWAY: stopping gateway")
        BleGatewayService.stop(context)
        return AutomationResult(true, "Gateway stopped")
    }

    private suspend fun setScanMode(repository: HassSettingsRepository, modeRaw: String?): AutomationResult {
        val mode = AutomationActions.parseScanMode(modeRaw)
        if (mode == null) {
            warn("SET_SCAN_MODE: unknown mode '$modeRaw' (use LOW_POWER, BALANCED, LOW_LATENCY)")
            return AutomationResult(false, "Unknown scan mode '$modeRaw'")
        }
        // 실행 중인 서비스는 저장소 flow를 구독하므로 저장만으로 즉시 반영된다.
        repository.saveScanMode(mode)
        info("SET_SCAN_MODE: ${mode.name}")
        return AutomationResult(true, "Scan mode ${mode.name}")
    }

    private suspend fun poll(context: Context, deviceId: String?, stopAfter: Boolean): AutomationResult {
        if (deviceId.isNullOrBlank()) {
            warn("POLL_DEVICE: missing device_id extra")
            return AutomationResult(false, "Missing device id")
        }
        if (!BleGatewayService.isServiceRunning.value) {
            warn("POLL_DEVICE ignored: gateway is not running (send START_GATEWAY first)")
            return AutomationResult(false, "Gateway is not running")
        }
        if (!BleGatewayService.runtimeReady.value) {
            info("POLL_DEVICE: gateway is starting, waiting up to ${RUNTIME_WAIT_TIMEOUT_MS / 1000}s")
            val ready = withTimeoutOrNull(RUNTIME_WAIT_TIMEOUT_MS) {
                BleGatewayService.runtimeReady.first { it }
            } != null
            if (!ready) {
                warn("POLL_DEVICE: gateway not ready after ${RUNTIME_WAIT_TIMEOUT_MS / 1000}s")
                return AutomationResult(false, "Gateway not ready")
            }
        }
        info("POLL_DEVICE: device=$deviceId, stop_after=$stopAfter")
        val requestedAt = System.currentTimeMillis()
        val outcome = coroutineScope {
            // 요청을 보내기 전에 구독을 시작해 결과를 놓치지 않는다.
            val waiter = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeoutOrNull(POLL_RESULT_TIMEOUT_MS) {
                    BleGatewayService.pollOutcomes.first { it.deviceId == deviceId && it.requestedAtMs >= requestedAt }
                }
            }
            BleGatewayService.pollDevice(context, deviceId, stopAfter)
            waiter.await()
        }
        return when {
            outcome == null -> AutomationResult(false, "Poll timed out")
            !outcome.accepted -> AutomationResult(false, "Device cannot be polled (unknown device or not a GATT/OBD device)")
            !outcome.finished -> AutomationResult(false, "Poll timed out")
            else -> AutomationResult(true, "Poll finished")
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

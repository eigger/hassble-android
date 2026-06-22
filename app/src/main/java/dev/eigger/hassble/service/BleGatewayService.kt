package dev.eigger.hassble.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.IBinder
import androidx.core.app.NotificationCompat
import dev.eigger.hassble.R
import dev.eigger.hassble.ble.BleRuntime
import dev.eigger.hassble.config.ConfigLoader
import dev.eigger.hassble.config.GatewayConfig
import dev.eigger.hassble.config.HassSettingsRepository
import dev.eigger.hassble.config.ObdPresetStore
import dev.eigger.hassble.net.ConnectionState
import dev.eigger.hassble.net.DeviceRef
import dev.eigger.hassble.net.EntityMsg
import dev.eigger.hassble.net.HaWsClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * 상시 게이트웨이 Foreground Service (스마트 앱, Companion 유사).
 *
 * 책임:
 *  - 고정 알림으로 백그라운드 BLE 유지
 *  - git에서 설정 로드(ConfigLoader) → 사용자가 켠 센서로 BleRuntime.apply
 *  - HA WebSocket 연결(HA URL + 토큰), 엔티티 선언/상태 push/명령 수신
 *  - 스마트폰 자체의 기본 센서 생성 및 주기적 전송
 */
class BleGatewayService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var ws: HaWsClient? = null
    private var runtime: BleRuntime? = null
    private var currentBatteryLevel: Int = -1

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.let {
                val level = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = it.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                if (level >= 0 && scale > 0) {
                    val batteryPercent = (level * 100f / scale).toInt()
                    currentBatteryLevel = batteryPercent
                    publishGatewayStates(ws)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        _isServiceRunning.value = true
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        startForeground(NOTIF_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val haUrl = intent?.getStringExtra(EXTRA_HA_URL) ?: return START_NOT_STICKY
        val token = intent.getStringExtra(EXTRA_TOKEN) ?: return START_NOT_STICKY
        val gitUrl = intent.getStringExtra(EXTRA_GIT_URL) ?: return START_NOT_STICKY
        val gitToken = intent.getStringExtra(EXTRA_GIT_TOKEN)

        val client = HaWsClient(haUrl, token, gatewayId(), android.os.Build.MODEL, scope)
            .also {
                it.connect()
                ws = it
            }

        scope.launch {
            client.connectionState.collect { state ->
                _serviceConnectionState.value = state
                if (state == ConnectionState.Connected) {
                    declareGatewayEntities(client)
                    publishGatewayStates(client)
                }
            }
        }

        // Heartbeat publisher
        scope.launch {
            while (true) {
                delay(300000) // 5 minutes
                publishGatewayStates(ws)
            }
        }

        val repository = HassSettingsRepository(applicationContext)

        scope.launch {
            val presets = ObdPresetStore.fromYaml(
                assets.open("obd_presets.yaml").bufferedReader().readText()
            )
            val loader = ConfigLoader(File(filesDir, "config.yaml"), presets)
            val config = loader.load(gitUrl, gitToken).getOrNull() ?: loader.loadCache() ?: return@launch

            val enabled = defaultEnabled(config)

            val scanner = dev.eigger.hassble.ble.NordicAdvertisementScanner(this@BleGatewayService)
            val gattSource = dev.eigger.hassble.ble.NordicGattNotifySource(this@BleGatewayService, this)
            val obdSource = dev.eigger.hassble.ble.NordicElm327Source(this@BleGatewayService, this)
            
            runtime = BleRuntime(scope, client, scanner, gattSource, obdSource).also {
                it.start()
            }

            repository.boundDevices.collect { boundMap ->
                runtime?.apply(config, enabled, boundMap)
            }
        }
        return START_STICKY
    }

    private fun defaultEnabled(config: GatewayConfig): Set<String> =
        config.devices.flatMap { d -> d.sensors.map { "${d.id}/${it.key}" } }.toSet()

    /** 게이트웨이(폰) 고유 식별자. HA에서 이 폰의 디바이스로 묶인다. */
    @android.annotation.SuppressLint("HardwareIds")
    private fun gatewayId(): String =
        android.provider.Settings.Secure.getString(contentResolver, android.provider.Settings.Secure.ANDROID_ID)
            ?: "hassble"

    private fun declareGatewayEntities(client: HaWsClient) {
        val phoneDevice = DeviceRef(gatewayId(), android.os.Build.MODEL)
        
        client.declareEntity(EntityMsg(
            id = 0,
            uniqueId = "${gatewayId()}_connection",
            platform = "sensor",
            name = "Connection State",
            device = phoneDevice,
        ))

        client.declareEntity(EntityMsg(
            id = 0,
            uniqueId = "${gatewayId()}_battery",
            platform = "sensor",
            name = "Battery Level",
            device = phoneDevice,
            deviceClass = "battery",
            unit = "%",
            stateClass = "measurement",
        ))

        client.declareEntity(EntityMsg(
            id = 0,
            uniqueId = "${gatewayId()}_service_status",
            platform = "sensor",
            name = "Service Status",
            device = phoneDevice,
        ))
    }

    private fun publishGatewayStates(client: HaWsClient?) {
        val c = client ?: return
        if (c.connectionState.value != ConnectionState.Connected) return
        val states = listOf(
            "${gatewayId()}_connection" to "online",
            "${gatewayId()}_battery" to if (currentBatteryLevel != -1) currentBatteryLevel else 100,
            "${gatewayId()}_service_status" to "running"
        )
        c.sendStates(states)
    }

    override fun onDestroy() {
        ws?.let { c ->
            val finalStates = listOf(
                "${gatewayId()}_connection" to "offline",
                "${gatewayId()}_service_status" to "stopped"
            )
            c.sendStates(finalStates)
        }
        unregisterReceiver(batteryReceiver)
        runtime?.stop()
        ws?.close()
        _isServiceRunning.value = false
        _serviceConnectionState.value = ConnectionState.Disconnected
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        val mgr = getSystemService(NotificationManager::class.java)
        if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "HassBle Gateway", NotificationManager.IMPORTANCE_LOW)
            )
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("HassBle")
            .setContentText(getString(R.string.sending_ble_data_notif))
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "ble_gateway"
        private const val NOTIF_ID = 1
        const val EXTRA_HA_URL = "ha_url"
        const val EXTRA_TOKEN = "token"
        const val EXTRA_GIT_URL = "git_url"
        const val EXTRA_GIT_TOKEN = "git_token"

        private val _serviceConnectionState = MutableStateFlow(ConnectionState.Disconnected)
        val serviceConnectionState: StateFlow<ConnectionState> = _serviceConnectionState.asStateFlow()

        private val _isServiceRunning = MutableStateFlow(false)
        val isServiceRunning: StateFlow<Boolean> = _isServiceRunning.asStateFlow()

        fun start(context: Context, haUrl: String, token: String, gitUrl: String, gitToken: String?) {
            val i = Intent(context, BleGatewayService::class.java)
                .putExtra(EXTRA_HA_URL, haUrl)
                .putExtra(EXTRA_TOKEN, token)
                .putExtra(EXTRA_GIT_URL, gitUrl)
                .putExtra(EXTRA_GIT_TOKEN, gitToken)
            context.startForegroundService(i)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, BleGatewayService::class.java))
        }
    }
}


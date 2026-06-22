package dev.eigger.hassble.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
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
import dev.eigger.hassble.ble.DeviceLinkStatus
import dev.eigger.hassble.ble.DiscoveredAdvInstance
import dev.eigger.hassble.ble.SensorLastValue
import dev.eigger.hassble.config.ConfigLoader
import dev.eigger.hassble.config.GatewayConfig
import dev.eigger.hassble.config.HassSettingsRepository
import dev.eigger.hassble.config.ObdPresetStore
import dev.eigger.hassble.net.ConnectionIssue
import dev.eigger.hassble.net.ConnectionState
import dev.eigger.hassble.net.DeviceRef
import dev.eigger.hassble.net.EntityMsg
import dev.eigger.hassble.net.HaWsClient
import dev.eigger.hassble.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.io.File

class BleGatewayService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var ws: HaWsClient? = null
    private var runtime: BleRuntime? = null
    private var configJob: Job? = null
    private var wsStateJob: Job? = null
    private var heartbeatJob: Job? = null
    private var settingsJob: Job? = null
    private var currentBatteryLevel: Int = -1
    private var currentGitUrl: String = ""
    private var currentGitToken: String? = null
    private var currentConfig: GatewayConfig? = null

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.let {
                val level = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = it.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                if (level >= 0 && scale > 0) {
                    currentBatteryLevel = (level * 100f / scale).toInt()
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
        if (intent?.action == ACTION_RELOAD_CONFIG) {
            intent.getStringExtra(EXTRA_GIT_URL)?.let { currentGitUrl = it }
            if (intent.hasExtra(EXTRA_GIT_TOKEN)) {
                currentGitToken = intent.getStringExtra(EXTRA_GIT_TOKEN)
            }
            reloadConfig()
            return START_STICKY
        }

        val haUrl = intent?.getStringExtra(EXTRA_HA_URL) ?: return START_NOT_STICKY
        val token = intent.getStringExtra(EXTRA_TOKEN) ?: return START_NOT_STICKY
        currentGitUrl = intent.getStringExtra(EXTRA_GIT_URL) ?: return START_NOT_STICKY
        currentGitToken = intent.getStringExtra(EXTRA_GIT_TOKEN)

        if (ws == null) {
            setupWebSocket(haUrl, token)
        }
        reloadConfig()
        return START_STICKY
    }

    private fun setupWebSocket(haUrl: String, token: String) {
        val client = HaWsClient(haUrl, token, gatewayId(), android.os.Build.MODEL, scope).also {
            it.connect()
            ws = it
        }

        wsStateJob?.cancel()
        wsStateJob = scope.launch {
            combine(client.connectionState, client.connectionIssue) { state, issue ->
                state to issue
            }.collect { (state, issue) ->
                _serviceConnectionState.value = state
                _connectionIssue.value = issue
                updateNotification()
                if (state == ConnectionState.Connected) {
                    declareGatewayEntities(client)
                    publishGatewayStates(client)
                    runtime?.redeclareEntities()
                }
            }
        }

        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (true) {
                delay(300_000)
                publishGatewayStates(ws)
            }
        }
    }

    private fun reloadConfig() {
        configJob?.cancel()
        settingsJob?.cancel()
        _serviceError.value = null
        _usingCachedConfig.value = false

        configJob = scope.launch {
            val presets = ObdPresetStore.fromYaml(
                assets.open("obd_presets.yaml").bufferedReader().readText(),
            )
            val loader = ConfigLoader(File(filesDir, "config_cache"), presets)
            val fetch = loader.load(currentGitUrl, currentGitToken)
            val config = if (fetch.isSuccess) {
                fetch.getOrNull()
            } else {
                _usingCachedConfig.value = true
                loader.loadCache(currentGitUrl)
            }

            if (config == null) {
                _serviceError.value = fetch.exceptionOrNull()?.localizedMessage
                    ?: getString(R.string.service_config_load_failed)
                updateNotification()
                return@launch
            }

            currentConfig = config
            val defaultEnabled = defaultEnabled(config)
            val repository = HassSettingsRepository(applicationContext)

            if (runtime == null) {
                val onLinkStatus: (DeviceLinkStatus) -> Unit = { status ->
                    _deviceLinkStatuses.value = _deviceLinkStatuses.value
                        .filter { it.profileId != status.profileId } + status
                    LiveEventLogger.log(LogType.LINK, "device=${status.profileId}, state=${status.state}")
                }
                val scanner = dev.eigger.hassble.ble.NordicAdvertisementScanner(this@BleGatewayService)
                val gattSource = dev.eigger.hassble.ble.NordicGattNotifySource(
                    this@BleGatewayService, scope, onLinkStatus,
                )
                val obdSource = dev.eigger.hassble.ble.NordicElm327Source(
                    this@BleGatewayService, scope, onLinkStatus,
                )
                runtime = BleRuntime(
                    scope,
                    ws!!,
                    scanner,
                    gattSource,
                    obdSource,
                    onDiscoveredAdvChanged = { _discoveredAdvInstances.value = it },
                    onSensorValuesChanged = { _sensorLastValues.value = it },
                    onLinkDataReceived = { profileId, ts ->
                        val cur = _deviceLinkStatuses.value.firstOrNull { it.profileId == profileId }
                        if (cur != null) {
                            onLinkStatus(cur.copy(state = dev.eigger.hassble.ble.DeviceLinkState.Polling, lastDataMs = ts))
                        }
                    },
                ).also { it.start() }
            }

            settingsJob = scope.launch {
                combine(
                    repository.boundDevices,
                    repository.enabledSensors,
                    repository.enabledSensorsInitialized,
                ) { boundMap, enabledSensors, initialized ->
                    val effectiveEnabled = if (!initialized) defaultEnabled else enabledSensors
                    boundMap to effectiveEnabled
                }.collect { (boundMap, effectiveEnabled) ->
                    runtime?.apply(config, effectiveEnabled, boundMap)
                }
            }
            updateNotification()
        }
    }

    private fun defaultEnabled(config: GatewayConfig): Set<String> = config.allSensorKeys()

    @android.annotation.SuppressLint("HardwareIds")
    private fun gatewayId(): String =
        android.provider.Settings.Secure.getString(contentResolver, android.provider.Settings.Secure.ANDROID_ID)
            ?: "hassble"

    private fun declareGatewayEntities(client: HaWsClient) {
        val phoneDevice = DeviceRef(gatewayId(), android.os.Build.MODEL)
        client.declareEntity(EntityMsg(
            id = 0, uniqueId = "${gatewayId()}_connection", platform = "sensor",
            name = "Connection State", device = phoneDevice,
        ))
        client.declareEntity(EntityMsg(
            id = 0, uniqueId = "${gatewayId()}_battery", platform = "sensor",
            name = "Battery Level", device = phoneDevice,
            deviceClass = "battery", unit = "%", stateClass = "measurement",
        ))
        client.declareEntity(EntityMsg(
            id = 0, uniqueId = "${gatewayId()}_service_status", platform = "sensor",
            name = "Service Status", device = phoneDevice,
        ))
    }

    private fun publishGatewayStates(client: HaWsClient?) {
        val c = client ?: return
        if (c.connectionState.value != ConnectionState.Connected) return
        c.sendStates(listOf(
            "${gatewayId()}_connection" to "online",
            "${gatewayId()}_battery" to if (currentBatteryLevel != -1) currentBatteryLevel else 100,
            "${gatewayId()}_service_status" to "running",
        ))
    }

    override fun onDestroy() {
        ws?.let { c ->
            c.sendStates(listOf(
                "${gatewayId()}_connection" to "offline",
                "${gatewayId()}_service_status" to "stopped",
            ))
        }
        unregisterReceiver(batteryReceiver)
        configJob?.cancel()
        settingsJob?.cancel()
        wsStateJob?.cancel()
        heartbeatJob?.cancel()
        runtime?.stop()
        ws?.close()
        runtime = null
        ws = null
        _isServiceRunning.value = false
        _serviceConnectionState.value = ConnectionState.Disconnected
        _connectionIssue.value = ConnectionIssue.None
        _discoveredAdvInstances.value = emptyList()
        _sensorLastValues.value = emptyList()
        _deviceLinkStatuses.value = emptyList()
        _serviceError.value = null
        _usingCachedConfig.value = false
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun updateNotification() {
        val mgr = getSystemService(NotificationManager::class.java)
        mgr.notify(NOTIF_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val mgr = getSystemService(NotificationManager::class.java)
        if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "HassBle Gateway", NotificationManager.IMPORTANCE_LOW),
            )
        }
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val sensorCount = _sensorLastValues.value.size
        val contentText = when {
            _serviceError.value != null -> getString(R.string.notif_config_error)
            _connectionIssue.value == ConnectionIssue.AuthFailed -> getString(R.string.notif_auth_failed)
            _connectionIssue.value == ConnectionIssue.BridgeNotResponding -> getString(R.string.notif_bridge_timeout)
            _serviceConnectionState.value == ConnectionState.Connected ->
                getString(R.string.notif_connected_sensors, sensorCount)
            _serviceConnectionState.value == ConnectionState.Connecting -> getString(R.string.status_connecting)
            else -> getString(R.string.sending_ble_data_notif)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("HassBle")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentIntent(openIntent)
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
        private const val ACTION_RELOAD_CONFIG = "dev.eigger.hassble.RELOAD_CONFIG"

        private val _serviceConnectionState = MutableStateFlow(ConnectionState.Disconnected)
        val serviceConnectionState: StateFlow<ConnectionState> = _serviceConnectionState.asStateFlow()

        private val _connectionIssue = MutableStateFlow(ConnectionIssue.None)
        val connectionIssue: StateFlow<ConnectionIssue> = _connectionIssue.asStateFlow()

        private val _isServiceRunning = MutableStateFlow(false)
        val isServiceRunning: StateFlow<Boolean> = _isServiceRunning.asStateFlow()

        private val _discoveredAdvInstances = MutableStateFlow<List<DiscoveredAdvInstance>>(emptyList())
        val discoveredAdvInstances: StateFlow<List<DiscoveredAdvInstance>> = _discoveredAdvInstances.asStateFlow()

        private val _sensorLastValues = MutableStateFlow<List<SensorLastValue>>(emptyList())
        val sensorLastValues: StateFlow<List<SensorLastValue>> = _sensorLastValues.asStateFlow()

        private val _deviceLinkStatuses = MutableStateFlow<List<DeviceLinkStatus>>(emptyList())
        val deviceLinkStatuses: StateFlow<List<DeviceLinkStatus>> = _deviceLinkStatuses.asStateFlow()

        private val _serviceError = MutableStateFlow<String?>(null)
        val serviceError: StateFlow<String?> = _serviceError.asStateFlow()

        private val _usingCachedConfig = MutableStateFlow(false)
        val usingCachedConfig: StateFlow<Boolean> = _usingCachedConfig.asStateFlow()

        fun start(context: Context, haUrl: String, token: String, gitUrl: String, gitToken: String?) {
            val i = Intent(context, BleGatewayService::class.java)
                .putExtra(EXTRA_HA_URL, haUrl)
                .putExtra(EXTRA_TOKEN, token)
                .putExtra(EXTRA_GIT_URL, gitUrl)
                .putExtra(EXTRA_GIT_TOKEN, gitToken)
            context.startForegroundService(i)
        }

        fun reloadConfig(context: Context, gitUrl: String? = null, gitToken: String? = null) {
            val i = Intent(context, BleGatewayService::class.java)
                .setAction(ACTION_RELOAD_CONFIG)
            gitUrl?.let { i.putExtra(EXTRA_GIT_URL, it) }
            if (gitToken != null) {
                i.putExtra(EXTRA_GIT_TOKEN, gitToken)
            }
            context.startService(i)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, BleGatewayService::class.java))
        }
    }
}

package dev.eigger.hassble.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import dev.eigger.hassble.R
import dev.eigger.hassble.ble.BleRuntime
import dev.eigger.hassble.ble.DeviceLinkStatus
import dev.eigger.hassble.ble.DiscoveredAdvInstance
import dev.eigger.hassble.ble.SensorLastValue
import dev.eigger.hassble.config.ConfigLoader
import dev.eigger.hassble.config.ConfigValidator
import dev.eigger.hassble.config.GatewayConfig
import dev.eigger.hassble.config.HassSettingsRepository
import dev.eigger.hassble.config.ObdPresetStore
import dev.eigger.hassble.net.ConnectionIssue
import dev.eigger.hassble.net.ConnectionState
import dev.eigger.hassble.net.DeviceRef
import dev.eigger.hassble.net.EntityMsg
import dev.eigger.hassble.net.HaAuthHelper
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
    private var networkCallback: android.net.ConnectivityManager.NetworkCallback? = null
    private var configJob: Job? = null
    private var wsStateJob: Job? = null
    private var heartbeatJob: Job? = null
    private var settingsJob: Job? = null
    private var currentGitUrl: String = ""
    private var currentGitToken: String? = null
    private var currentConfig: GatewayConfig? = null
    @Volatile private var pendingEntityCleanupDeviceIds: Set<String> = emptySet()

    override fun onCreate() {
        super.onCreate()
        _isServiceRunning.value = true
        registerNetworkCallback()
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

        if (intent?.action == ACTION_DISABLE_DEVICE) {
            val deviceId = intent.getStringExtra(EXTRA_DEVICE_ID) ?: return START_STICKY
            scope.launch {
                val repository = HassSettingsRepository(this@BleGatewayService)
                repository.setDeviceDisabled(deviceId, true)
                val uniqueIds = runtime?.entityUniqueIdsForDevice(deviceId) ?: emptyList()
                ws?.removeEntitiesByUniqueIds(uniqueIds)
            }
            return START_STICKY
        }

        if (intent?.action == ACTION_ENABLE_DEVICE) {
            val deviceId = intent.getStringExtra(EXTRA_DEVICE_ID) ?: return START_STICKY
            scope.launch { HassSettingsRepository(this@BleGatewayService).setDeviceDisabled(deviceId, false) }
            return START_STICKY
        }

        if (intent?.action == ACTION_SET_AUTO_CONNECT) {
            val deviceId = intent.getStringExtra(EXTRA_DEVICE_ID) ?: return START_STICKY
            val enabled = intent.getBooleanExtra(EXTRA_AUTO_CONNECT, true)
            scope.launch { HassSettingsRepository(this@BleGatewayService).setAutoConnectDisabled(deviceId, !enabled) }
            return START_STICKY
        }

        if (intent?.action == ACTION_CONNECT_DEVICE) {
            val deviceId = intent.getStringExtra(EXTRA_DEVICE_ID) ?: return START_STICKY
            runtime?.connectDevice(deviceId)
            return START_STICKY
        }

        if (intent?.action == ACTION_DISCONNECT_DEVICE) {
            val deviceId = intent.getStringExtra(EXTRA_DEVICE_ID) ?: return START_STICKY
            runtime?.disconnectDevice(deviceId)
            return START_STICKY
        }

        val haUrl = intent?.getStringExtra(EXTRA_HA_URL) ?: return START_NOT_STICKY
        val token = intent.getStringExtra(EXTRA_TOKEN) ?: return START_NOT_STICKY
        val refreshToken = intent.getStringExtra(EXTRA_REFRESH_TOKEN)
        currentGitUrl = intent.getStringExtra(EXTRA_GIT_URL) ?: return START_NOT_STICKY
        currentGitToken = intent.getStringExtra(EXTRA_GIT_TOKEN)
 
        if (ws == null) {
            scope.launch {
                val activeToken = maybeRefreshToken(haUrl, token, refreshToken)
                setupWebSocket(haUrl, activeToken, refreshToken)
                reloadConfig()
            }
        } else {
            reloadConfig()
        }
        return START_STICKY
    }

    private suspend fun maybeRefreshToken(haUrl: String, token: String, refreshToken: String?): String {
        if (refreshToken.isNullOrBlank()) return token
        if (!HaAuthHelper.isTokenExpiringSoon(token)) return token
        val result = HaAuthHelper.refreshAccessToken(haUrl, refreshToken)
        return if (result.isSuccess) {
            val newToken = result.getOrThrow()
            HassSettingsRepository(applicationContext).saveHaSettings(haUrl, newToken)
            newToken
        } else {
            token
        }
    }

    private fun setupWebSocket(haUrl: String, token: String, refreshToken: String?) {
        val client = HaWsClient(
            baseUrl = haUrl,
            token = token,
            gatewayId = gatewayId(),
            gatewayName = android.os.Build.MODEL,
            scope = scope,
            refreshToken = refreshToken,
            onTokenRefreshed = { newToken ->
                HassSettingsRepository(applicationContext).saveHaSettings(haUrl, newToken)
            }
        ).also {
            it.connect()
            ws = it
        }

        wsStateJob?.cancel()
        var lastIssue: ConnectionIssue = ConnectionIssue.None
        wsStateJob = scope.launch {
            launch {
                client.bridgeConnected.collect {
                    declareGatewayEntities(client)
                    publishGatewayStates(client)
                    // 구조적으로 변경된 디바이스의 HA 엔티티를 먼저 제거 후 재선언
                    val cleanupIds = pendingEntityCleanupDeviceIds
                    if (cleanupIds.isNotEmpty()) {
                        pendingEntityCleanupDeviceIds = emptySet()
                        for (deviceId in cleanupIds) {
                            runCatching { ws?.removeEntitiesByDeviceIdPrefix(deviceId) }
                        }
                        LiveEventLogger.log(LogType.LINK,
                            "Refreshed HA entities for: ${cleanupIds.joinToString()}")
                    }
                    runtime?.redeclareEntities()
                }
            }
            combine(client.connectionState, client.connectionIssue) { state, issue ->
                state to issue
            }.collect { (state, issue) ->
                _serviceConnectionState.value = state
                _connectionIssue.value = issue
                updateNotification()
                if (issue == ConnectionIssue.AuthFailed && lastIssue != ConnectionIssue.AuthFailed) {
                    showAuthExpiredNotification()
                }
                lastIssue = issue
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
            HassSettingsRepository(this@BleGatewayService).clearDisabledDevices()
            val presets = ObdPresetStore.fromYaml(
                assets.open("obd_presets.yaml").bufferedReader().readText(),
            )
            val loader = ConfigLoader(File(filesDir, "config_cache"), presets)
            val cachedConfig = runCatching { loader.loadCache(currentGitUrl) }.getOrNull()
            val fetch = loader.load(currentGitUrl, currentGitToken)
            val config = if (fetch.isSuccess) {
                fetch.getOrNull()
            } else {
                _usingCachedConfig.value = true
                cachedConfig
            }

            if (config == null) {
                _serviceError.value = fetch.exceptionOrNull()?.localizedMessage
                    ?: getString(R.string.service_config_load_failed)
                updateNotification()
                return@launch
            }

            // ─── 엔티티 선언 버전 migration ────────────────────────────────────────
            // 앱의 선언 동작이 바뀌었을 때 기존 HA 엔티티를 한 번 삭제 후 재선언
            val prefs = getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
            val lastDeclVersion = prefs.getInt(PREF_DECL_VERSION, 0)
            if (lastDeclVersion < ENTITY_DECLARATION_VERSION) {
                val allDeviceIds = config.devices.map { it.id }.toSet()
                pendingEntityCleanupDeviceIds = allDeviceIds
                prefs.edit().putInt(PREF_DECL_VERSION, ENTITY_DECLARATION_VERSION).apply()
                LiveEventLogger.log(LogType.LINK,
                    "Entity declaration version migrated ($lastDeclVersion → $ENTITY_DECLARATION_VERSION) — HA entities will be refreshed for: ${allDeviceIds.joinToString()}")
            } else if (fetch.isSuccess && cachedConfig != null && cachedConfig != config) {
                // 새로 다운로드한 config가 캐시와 다른 경우, 구조적 변경 감지
                val changed = ConfigValidator.structurallyChangedDeviceIds(cachedConfig, config)
                if (changed.isNotEmpty()) {
                    pendingEntityCleanupDeviceIds = pendingEntityCleanupDeviceIds + changed
                    LiveEventLogger.log(LogType.LINK,
                        "Config structural change detected for: ${changed.joinToString()} — HA entities will be refreshed")
                }
            }

            currentConfig = config
            val defaultEnabled = defaultEnabled(config)
            val repository = HassSettingsRepository(applicationContext)

            val newConfigIds = config.devices.map { it.id }.toSet()
            val removedIds = repository.getRemovedDeviceIds(newConfigIds)
            if (removedIds.isNotEmpty()) {
                LiveEventLogger.log(LogType.LINK, "Config 변경: 삭제된 기기 HA 정리 중 (${removedIds.joinToString()})")
                removedIds.forEach { deviceId ->
                    ws?.removeEntitiesByDeviceIdPrefix(deviceId)
                    repository.unbindDevice(deviceId)
                }
            }
            repository.updateKnownDeviceIds(newConfigIds)
            repository.initAutoConnectFromConfig(config.devices)

            if (runtime == null) {
                val onLinkStatus: (DeviceLinkStatus) -> Unit = { status ->
                    _deviceLinkStatuses.value = _deviceLinkStatuses.value
                        .filter { it.profileId != status.profileId } + status
                    LiveEventLogger.log(LogType.LINK, "device=${status.profileId}, state=${status.state}")
                    ws?.let { client ->
                        if (client.connectionState.value == ConnectionState.Connected) {
                            val isConnected = status.state == dev.eigger.hassble.ble.DeviceLinkState.Connected || status.state == dev.eigger.hassble.ble.DeviceLinkState.Polling
                            client.sendStates(listOf(
                                "${status.profileId}_link_status" to if (isConnected) "on" else "off",
                            ))
                        }
                    }
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
                    onLinkStatus = onLinkStatus,
                ).also { it.start() }
            }

            settingsJob = scope.launch {
                combine(
                    repository.boundDevices,
                    repository.enabledSensors,
                    repository.enabledSensorsInitialized,
                    repository.scanMode,
                    repository.disabledDevices,
                    repository.autoConnectDisabled,
                ) { args ->
                    val boundMap = args[0] as Map<*, *>
                    val enabledSensors = args[1] as Set<*>
                    val initialized = args[2] as Boolean
                    val scanMode = args[3] as dev.eigger.hassble.config.BleScanModeOption
                    val disabledDevices = args[4] as Set<*>
                    val autoConnectDisabled = args[5] as Set<*>
                    val effectiveEnabled = if (!initialized) defaultEnabled else enabledSensors.filterIsInstance<String>().toSet()
                    Triple(
                        Triple(boundMap.entries.associate { it.key.toString() to it.value.toString() }, effectiveEnabled, scanMode),
                        disabledDevices.filterIsInstance<String>().toSet(),
                        autoConnectDisabled.filterIsInstance<String>().toSet(),
                    )
                }.collect { (triple, disabledDevices, autoConnectDisabled) ->
                    val (boundMap, effectiveEnabled, scanMode) = triple
                    runtime?.apply(config, effectiveEnabled, boundMap, scanMode, disabledDevices, autoConnectDisabled)
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
            id = 0, uniqueId = "${gatewayId()}_connection", platform = "binary_sensor",
            name = "Connection State", device = phoneDevice,
            deviceClass = "connectivity", entityCategory = "diagnostic",
        ))
        client.declareEntity(EntityMsg(
            id = 0, uniqueId = "${gatewayId()}_service_status", platform = "binary_sensor",
            name = "Service Status", device = phoneDevice,
            deviceClass = "running", entityCategory = "diagnostic",
        ))
    }

    private fun publishGatewayStates(client: HaWsClient?) {
        val c = client ?: return
        if (c.connectionState.value != ConnectionState.Connected) return
        c.sendStates(listOf(
            "${gatewayId()}_connection" to "on",
            "${gatewayId()}_service_status" to "on",
        ))
    }

    override fun onDestroy() {
        ws?.let { c ->
            c.sendStates(listOf(
                "${gatewayId()}_connection" to "off",
                "${gatewayId()}_service_status" to "off",
            ))
        }
        unregisterNetworkCallback()
        configJob?.cancel()
        settingsJob?.cancel()
        wsStateJob?.cancel()
        heartbeatJob?.cancel()
        runtime?.stop()
        ws?.close()
        runtime = null
        ws = null
        pendingEntityCleanupDeviceIds = emptySet()
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

    private fun showAuthExpiredNotification() {
        val mgr = getSystemService(NotificationManager::class.java) ?: return
        val warnChannelId = "ble_gateway_warning"
        if (mgr.getNotificationChannel(warnChannelId) == null) {
            val channel = NotificationChannel(warnChannelId, getString(R.string.notif_warn_channel_name), NotificationManager.IMPORTANCE_HIGH).apply {
                description = getString(R.string.notif_warn_channel_desc)
                enableVibration(true)
                enableLights(true)
            }
            mgr.createNotificationChannel(channel)
        }
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notif = NotificationCompat.Builder(this, warnChannelId)
            .setContentTitle(getString(R.string.oauth_expired_notif_title))
            .setContentText(getString(R.string.oauth_expired_notif_text))
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentIntent(openIntent)
            .setAutoCancel(true)
            .setDefaults(Notification.DEFAULT_ALL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        mgr.notify(2, notif)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun registerNetworkCallback() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager ?: return
        val cb = object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) {
                ws?.reconnectImmediately()
            }
        }
        try {
            val request = android.net.NetworkRequest.Builder()
                .addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            networkCallback = cb
            cm.registerNetworkCallback(request, cb)
        } catch (e: Exception) {
            android.util.Log.w("BleGatewayService", "Failed to register network callback: ${e.message}")
        }
    }

    private fun unregisterNetworkCallback() {
        val cb = networkCallback ?: return
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager ?: return
        cm.unregisterNetworkCallback(cb)
        networkCallback = null
    }

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
        const val EXTRA_REFRESH_TOKEN = "refresh_token"
        const val EXTRA_GIT_URL = "git_url"
        const val EXTRA_GIT_TOKEN = "git_token"
        private const val EXTRA_DEVICE_ID = "device_id"
        private const val ACTION_RELOAD_CONFIG = "dev.eigger.hassble.RELOAD_CONFIG"
        private const val ACTION_DISABLE_DEVICE = "dev.eigger.hassble.DISABLE_DEVICE"
        private const val ACTION_ENABLE_DEVICE = "dev.eigger.hassble.ENABLE_DEVICE"
        private const val ACTION_SET_AUTO_CONNECT = "dev.eigger.hassble.SET_AUTO_CONNECT"
        private const val EXTRA_AUTO_CONNECT = "auto_connect"
        private const val ACTION_CONNECT_DEVICE = "dev.eigger.hassble.CONNECT_DEVICE"
        private const val ACTION_DISCONNECT_DEVICE = "dev.eigger.hassble.DISCONNECT_DEVICE"
        // 앱의 HA 엔티티 선언 방식이 바뀔 때 이 값을 올리면 기존 HA 엔티티를 한 번 삭제 후 재선언 (one-time migration)
        private const val ENTITY_DECLARATION_VERSION = 2
        private const val PREF_DECL_VERSION = "entity_decl_version"
        private const val PREF_FILE = "hassble_decl"

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

        fun start(context: Context, haUrl: String, token: String, refreshToken: String?, gitUrl: String, gitToken: String?) {
            val i = Intent(context, BleGatewayService::class.java)
                .putExtra(EXTRA_HA_URL, haUrl)
                .putExtra(EXTRA_TOKEN, token)
                .putExtra(EXTRA_REFRESH_TOKEN, refreshToken)
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

        fun disableDevice(context: Context, deviceId: String) {
            context.startService(Intent(context, BleGatewayService::class.java)
                .setAction(ACTION_DISABLE_DEVICE).putExtra(EXTRA_DEVICE_ID, deviceId))
        }

        fun enableDevice(context: Context, deviceId: String) {
            context.startService(Intent(context, BleGatewayService::class.java)
                .setAction(ACTION_ENABLE_DEVICE).putExtra(EXTRA_DEVICE_ID, deviceId))
        }

        fun setAutoConnect(context: Context, deviceId: String, enabled: Boolean) {
            context.startService(Intent(context, BleGatewayService::class.java)
                .setAction(ACTION_SET_AUTO_CONNECT)
                .putExtra(EXTRA_DEVICE_ID, deviceId)
                .putExtra(EXTRA_AUTO_CONNECT, enabled))
        }

        fun connectDevice(context: Context, deviceId: String) {
            context.startService(Intent(context, BleGatewayService::class.java)
                .setAction(ACTION_CONNECT_DEVICE)
                .putExtra(EXTRA_DEVICE_ID, deviceId))
        }

        fun disconnectDevice(context: Context, deviceId: String) {
            context.startService(Intent(context, BleGatewayService::class.java)
                .setAction(ACTION_DISCONNECT_DEVICE)
                .putExtra(EXTRA_DEVICE_ID, deviceId))
        }
    }
}

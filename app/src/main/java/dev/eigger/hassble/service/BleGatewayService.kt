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
import dev.eigger.hassble.ble.haRemoveModeForDevice
import dev.eigger.hassble.ble.DiscoveredAdvInstance
import dev.eigger.hassble.ble.SensorLastValue
import dev.eigger.hassble.config.ConfigLoader
import dev.eigger.hassble.config.ConfigMerger
import dev.eigger.hassble.config.ConfigValidator
import dev.eigger.hassble.config.GatewayConfig
import dev.eigger.hassble.config.HassSettingsRepository
import dev.eigger.hassble.config.ObdPresetStore
import dev.eigger.hassble.net.ConnectionIssue
import dev.eigger.hassble.net.ConnectionState
import dev.eigger.hassble.net.DeviceRef
import dev.eigger.hassble.net.EntityMsg
import dev.eigger.hassble.net.HaAuthHelper
import dev.eigger.hassble.net.HaRemoveMode
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File

private data class SettingsSnapshot(
    val boundMap: Map<String, String>,
    val enabledSensors: Set<String>,
    val scanMode: dev.eigger.hassble.config.BleScanModeOption,
    val autoConnectDisabled: Set<String>,
    val unfilteredScan: Boolean,
)

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

        if (intent?.action == ACTION_REMOVE_DEVICE) {
            val deviceId = intent.getStringExtra(EXTRA_DEVICE_ID) ?: return START_STICKY
            val mode = haRemoveModeFor(deviceId)
            // config 재로드를 기다리지 않고 BLE 연결/스캔을 즉시 정리한다.
            runtime?.stopDeviceNow(deviceId)
            scope.launch {
                val repository = HassSettingsRepository(this@BleGatewayService)
                repository.queueHaEntityRemoval(setOf(deviceId), mapOf(deviceId to mode))
                pendingEntityCleanupDeviceIds = pendingEntityCleanupDeviceIds + deviceId
                runCatching { ws?.removeDevice(deviceId, mode) }
            }
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
                    val repository = HassSettingsRepository(this@BleGatewayService)
                    val pendingFromStore = repository.consumePendingHaRemovals()
                    // 선언 내용이 바뀐 device의 HA 엔티티를 먼저 제거 후 재선언
                    val cleanupIds = pendingEntityCleanupDeviceIds + pendingFromStore.keys
                    if (cleanupIds.isNotEmpty()) {
                        pendingEntityCleanupDeviceIds = emptySet()
                        val failed = mutableMapOf<String, HaRemoveMode>()
                        for (deviceId in cleanupIds) {
                            val client = ws
                            val mode = pendingFromStore[deviceId]
                                ?: runtime?.haRemoveModeForDeviceId(deviceId)
                                ?: haRemoveModeFor(deviceId)
                            val ok = client != null &&
                                runCatching { client.removeDevice(deviceId, mode) }.isSuccess
                            if (!ok) failed[deviceId] = mode
                        }
                        // 실패분은 다음 연결에서 다시 시도하도록 대기열에 되돌린다.
                        if (failed.isNotEmpty()) repository.queueHaEntityRemoval(failed.keys, failed)
                        val done = cleanupIds - failed.keys
                        if (done.isNotEmpty()) {
                            LiveEventLogger.log(LogType.LINK,
                                "Refreshed HA entities for: ${done.joinToString()}")
                        }
                    }
                    runtime?.redeclareEntities()
                    // 성공적으로 선언 완료 → fingerprint 저장
                    currentConfig?.let { cfg ->
                        runCatching {
                            HassSettingsRepository(this@BleGatewayService).saveEntityFingerprints(cfg)
                        }.onFailure { e ->
                            LiveEventLogger.log(LogType.LINK, "[Warning] Failed to save entity fingerprints: ${e.message}")
                        }
                    }
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
            val presets = ObdPresetStore.fromYaml(
                assets.open("obd_presets.yaml").bufferedReader().readText(),
            )
            val loader = ConfigLoader(File(filesDir, "config_cache"), presets)
            val cachedConfig = runCatching { loader.loadCache(currentGitUrl) }.getOrNull()
            val fetch = loader.load(currentGitUrl, currentGitToken)
            val repository = HassSettingsRepository(applicationContext)
            val draftDevices = repository.loadDraftDevices()
            val baseConfig = if (fetch.isSuccess) {
                fetch.getOrNull()
            } else {
                _usingCachedConfig.value = true
                cachedConfig
            }
            // remote config를 신뢰할 수 있을 때(fetch 성공)만 excluded 목록을 정리한다.
            // 네트워크 실패로 baseConfig가 캐시/null이면 prune을 건너뛰어 삭제 기록 손실을 막는다.
            if (fetch.isSuccess && baseConfig != null) {
                val remoteIds = baseConfig.devices.map { it.id }.toSet()
                repository.pruneExcludedDeviceIds(remoteIds)
            }
            val excludedIds = repository.excludedDevices.first()
            val config = when {
                baseConfig != null && draftDevices.isNotEmpty() ->
                    ConfigMerger.merge(baseConfig, draftDevices)
                baseConfig != null -> baseConfig
                draftDevices.isNotEmpty() -> GatewayConfig(devices = draftDevices)
                else -> null
            }?.let { cfg ->
                cfg.copy(devices = cfg.devices.filter { it.id !in excludedIds })
            }

            if (config == null) {
                _serviceError.value = fetch.exceptionOrNull()?.localizedMessage
                    ?: getString(R.string.service_config_load_failed)
                updateNotification()
                return@launch
            }

            currentConfig = config
            val defaultEnabled = defaultEnabled(config)

            // ─── HA 엔티티 fingerprint 비교 ────────────────────────────────────────
            // config 변경 또는 앱 선언 방식 변경 시 해당 device의 HA 엔티티 자동 cleanup
            val changed = repository.getChangedDeviceIds(config)
            if (changed.isNotEmpty()) {
                pendingEntityCleanupDeviceIds = pendingEntityCleanupDeviceIds + changed
                LiveEventLogger.log(LogType.LINK,
                    "Entity declaration changed for: ${changed.joinToString()} — HA entities will be refreshed")
            }

            val newConfigIds = config.devices.map { it.id }.toSet()
            val removedIds = repository.getRemovedDeviceIds(newConfigIds)
            if (removedIds.isNotEmpty()) {
                val removalModes = removedIds.associateWith { haRemoveModeFor(it) }
                repository.queueHaEntityRemoval(removedIds, removalModes)
                pendingEntityCleanupDeviceIds = pendingEntityCleanupDeviceIds + removedIds
                LiveEventLogger.log(LogType.LINK, "Config 변경: 삭제된 기기 HA 정리 중 (${removedIds.joinToString()})")
                removedIds.forEach { deviceId ->
                    runCatching { ws?.removeDevice(deviceId, removalModes.getValue(deviceId)) }
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
                    this@BleGatewayService, onLinkStatus,
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
                    repository.autoConnectDisabled,
                    LiveEventLogger.includeAdvLogsFlow,
                ) { args ->
                    val boundMap = args[0] as Map<*, *>
                    val enabledSensors = args[1] as Set<*>
                    val initialized = args[2] as Boolean
                    val scanMode = args[3] as dev.eigger.hassble.config.BleScanModeOption
                    val autoConnectDisabled = args[4] as Set<*>
                    val unfilteredScan = args[5] as Boolean
                    val effectiveEnabled = if (!initialized) defaultEnabled else enabledSensors.filterIsInstance<String>().toSet()
                    SettingsSnapshot(
                        boundMap = boundMap.entries.associate { it.key.toString() to it.value.toString() },
                        enabledSensors = effectiveEnabled,
                        scanMode = scanMode,
                        autoConnectDisabled = autoConnectDisabled.filterIsInstance<String>().toSet(),
                        unfilteredScan = unfilteredScan,
                    )
                }.collect { snapshot ->
                    runtime?.apply(
                        config,
                        snapshot.enabledSensors,
                        snapshot.boundMap,
                        snapshot.scanMode,
                        snapshot.autoConnectDisabled,
                        snapshot.unfilteredScan,
                    )
                }
            }
            updateNotification()
        }
    }

    private fun defaultEnabled(config: GatewayConfig): Set<String> = config.allSensorKeys()

    private fun haRemoveModeFor(deviceId: String): HaRemoveMode =
        runtime?.haRemoveModeForDeviceId(deviceId)
            ?: haRemoveModeForDevice(currentConfig?.devices?.firstOrNull { it.id == deviceId })
            ?: HaRemoveMode.EXACT

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
        private const val ACTION_REMOVE_DEVICE = "dev.eigger.hassble.REMOVE_DEVICE"
        private const val ACTION_SET_AUTO_CONNECT = "dev.eigger.hassble.SET_AUTO_CONNECT"
        private const val EXTRA_AUTO_CONNECT = "auto_connect"
        private const val ACTION_CONNECT_DEVICE = "dev.eigger.hassble.CONNECT_DEVICE"
        private const val ACTION_DISCONNECT_DEVICE = "dev.eigger.hassble.DISCONNECT_DEVICE"

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

        fun removeDevice(context: Context, deviceId: String) {
            context.startService(Intent(context, BleGatewayService::class.java)
                .setAction(ACTION_REMOVE_DEVICE).putExtra(EXTRA_DEVICE_ID, deviceId))
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

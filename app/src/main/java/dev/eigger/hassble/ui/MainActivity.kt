package dev.eigger.hassble.ui

import android.Manifest
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Close
import dev.eigger.hassble.service.LiveEventLogger
import dev.eigger.hassble.service.LogEntry
import dev.eigger.hassble.service.LogType
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import dev.eigger.hassble.BuildConfig
import dev.eigger.hassble.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import android.os.PowerManager
import android.provider.Settings
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import dev.eigger.hassble.ble.DiscoveredAdvInstance
import dev.eigger.hassble.ble.SensorLastValue
import dev.eigger.hassble.config.AdvertisementInstanceMode
import dev.eigger.hassble.config.ConfigLoader
import dev.eigger.hassble.config.DeviceConfig
import dev.eigger.hassble.config.GatewayConfig
import dev.eigger.hassble.config.HassSettingsRepository
import dev.eigger.hassble.config.ObdPresetStore
import dev.eigger.hassble.config.Source
import dev.eigger.hassble.ble.DeviceLinkState
import dev.eigger.hassble.ble.DeviceLinkStatus
import dev.eigger.hassble.net.ConnectionIssue
import dev.eigger.hassble.net.ConnectionState
import dev.eigger.hassble.net.GitHubHelper
import dev.eigger.hassble.net.HaConnectionTester
import dev.eigger.hassble.service.BleGatewayService
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.runtime.mutableIntStateOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import androidx.lifecycle.lifecycleScope

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleDeepLink(intent)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF9E86FF),
                    onPrimary = Color.Black,
                    secondary = Color(0xFF00E676),
                    background = Color(0xFF121214),
                    surface = Color(0xFF1E1E22),
                    error = Color(0xFFFF5252)
                )
            ) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    HomeScreen()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDeepLink(intent)
    }

    private fun handleDeepLink(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme == "hassble" && data.host == "oauth-callback") {
            val code = data.getQueryParameter("code")
            val returnedState = data.getQueryParameter("state")
            if (code != null) {
                lifecycleScope.launch {
                    val repository = HassSettingsRepository(applicationContext)
                    val savedState = repository.haAuthState.first()
                    if (savedState.isBlank() || savedState != returnedState) {
                        Toast.makeText(applicationContext, getString(R.string.oauth_csrf_failed_toast), Toast.LENGTH_LONG).show()
                        repository.clearHaAuthState()
                        return@launch
                    }
                    repository.clearHaAuthState()
                    val haUrl = repository.haUrl.first()
                    if (haUrl.isNotBlank() && haUrl != "https://") {
                        Toast.makeText(applicationContext, getString(R.string.oauth_fetching_token_toast), Toast.LENGTH_SHORT).show()
                        val result = withContext(Dispatchers.IO) {
                            dev.eigger.hassble.net.HaAuthHelper.exchangeCodeForTokens(haUrl, code)
                        }
                        if (result.isSuccess) {
                            val tokens = result.getOrThrow()
                            repository.saveHaSettings(haUrl, tokens.accessToken)
                            repository.saveHaRefreshToken(tokens.refreshToken ?: "")
                            repository.saveHaTokenLastRefreshed(System.currentTimeMillis())
                            Toast.makeText(applicationContext, getString(R.string.oauth_login_success_toast), Toast.LENGTH_LONG).show()
                        } else {
                            Toast.makeText(applicationContext, getString(R.string.oauth_token_fetch_failed_toast, result.exceptionOrNull()?.localizedMessage), Toast.LENGTH_LONG).show()
                        }
                    } else {
                        Toast.makeText(applicationContext, getString(R.string.validate_url_required_msg), Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }
}

data class ScannedDevice(val name: String, val address: String, val rssi: Int)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun HomeScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = remember { HassSettingsRepository(context) }

    val savedHaUrl by repository.haUrl.collectAsState(initial = "")
    val savedHaToken by repository.haToken.collectAsState(initial = "")
    val savedHaRefreshToken by repository.haRefreshToken.collectAsState(initial = "")
    val haTokenLastRefreshed by repository.haTokenLastRefreshed.collectAsState(initial = 0L)
    val savedGitUrl by repository.gitUrl.collectAsState(initial = "")
    val savedGitToken by repository.gitToken.collectAsState(initial = "")
    val boundDevices by repository.boundDevices.collectAsState(initial = emptyMap())
    val enabledSensors by repository.enabledSensors.collectAsState(initial = emptySet())
    val enabledSensorsInitialized by repository.enabledSensorsInitialized.collectAsState(initial = false)
    val startOnBoot by repository.startOnBoot.collectAsState(initial = true)
    val scanMode by repository.scanMode.collectAsState(initial = dev.eigger.hassble.config.BleScanModeOption.LOW_LATENCY)
    val onboardingComplete by repository.onboardingComplete.collectAsState(initial = false)

    val powerManager = remember { context.getSystemService(Context.POWER_SERVICE) as PowerManager }
    var isBatteryIgnored by remember { mutableStateOf(powerManager.isIgnoringBatteryOptimizations(context.packageName)) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isBatteryIgnored = powerManager.isIgnoringBatteryOptimizations(context.packageName)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    var urlInput by remember { mutableStateOf("") }
    var tokenInput by remember { mutableStateOf("") }
    var gitRepoInput by remember { mutableStateOf("") }
    var gitFileInput by remember { mutableStateOf("") }
    var gitTokenInput by remember { mutableStateOf("") }

    val gitUrlInput = GitHubHelper.buildRawUrl(gitRepoInput, gitFileInput)

    LaunchedEffect(savedHaUrl, savedHaToken) {
        urlInput = savedHaUrl
        tokenInput = savedHaToken
    }
    LaunchedEffect(savedGitUrl, savedGitToken) {
        val parsed = GitHubHelper.parseGitUrl(savedGitUrl)
        gitRepoInput = parsed?.repoShort ?: savedGitUrl
        gitFileInput = parsed?.file ?: ""
        gitTokenInput = savedGitToken ?: ""
    }

    LaunchedEffect(urlInput, tokenInput) {
        delay(1000)
        if (urlInput.isNotBlank()) {
            var correctedUrl = urlInput.trim()
            if (correctedUrl != "https://" && correctedUrl != "http://") {
                if (!correctedUrl.startsWith("http://") && !correctedUrl.startsWith("https://")) {
                    correctedUrl = "http://$correctedUrl"
                }
                if (correctedUrl.endsWith("/")) {
                    correctedUrl = correctedUrl.trimEnd('/')
                }
                if (correctedUrl != urlInput) {
                    urlInput = correctedUrl
                }
            }
            repository.saveHaSettings(correctedUrl, tokenInput)
        }
    }
    LaunchedEffect(gitUrlInput, gitTokenInput) {
        delay(1000)
        if (gitUrlInput.isNotBlank()) {
            repository.saveGitSettings(gitUrlInput, gitTokenInput.ifBlank { null })
        }
    }

    val presets = remember {
        ObdPresetStore.fromYaml(context.assets.open("obd_presets.yaml").bufferedReader().readText())
    }
    val loader = remember { ConfigLoader(File(context.filesDir, "config_cache"), presets) }
    val isRunning by BleGatewayService.isServiceRunning.collectAsState()
    val connState by BleGatewayService.serviceConnectionState.collectAsState()
    val connectionIssue by BleGatewayService.connectionIssue.collectAsState()
    val serviceError by BleGatewayService.serviceError.collectAsState()
    val usingCachedConfigService by BleGatewayService.usingCachedConfig.collectAsState()
    val deviceLinkStatuses by BleGatewayService.deviceLinkStatuses.collectAsState()
    val disabledDeviceIds by repository.disabledDevices.collectAsState(initial = emptySet())
    val autoConnectDisabledIds by repository.autoConnectDisabled.collectAsState(initial = emptySet())
    val discoveredAdv by BleGatewayService.discoveredAdvInstances.collectAsState()
    val sensorLastValues by BleGatewayService.sensorLastValues.collectAsState()

    var loadedConfig by remember { mutableStateOf<GatewayConfig?>(null) }
    var configError by remember { mutableStateOf<String?>(null) }
    var isConfigLoading by remember { mutableStateOf(false) }
    var reloadTrigger by remember { mutableStateOf(0) }
    var usingCachedConfig by remember { mutableStateOf(false) }
    var showOnboarding by remember { mutableStateOf(false) }
    var missingPermCount by remember { mutableStateOf(0) }

    LaunchedEffect(onboardingComplete) {
        if (!onboardingComplete) showOnboarding = true
    }

    LaunchedEffect(gitUrlInput, gitTokenInput, reloadTrigger) {
        if (gitUrlInput.isNotBlank()) {
            isConfigLoading = true
            configError = null
            val res = loader.load(gitUrlInput, gitTokenInput.ifBlank { null })
            isConfigLoading = false
            if (res.isSuccess) {
                loadedConfig = res.getOrNull()
                usingCachedConfig = false
            } else {
                configError = ConfigErrorMapper.message(context, res.exceptionOrNull())
                loadedConfig = loader.loadCache(gitUrlInput)
                usingCachedConfig = loadedConfig != null
                if (usingCachedConfig) {
                    Toast.makeText(context, context.getString(R.string.config_cache_toast), Toast.LENGTH_LONG).show()
                }
            }
        } else {
            loadedConfig = loader.loadCache(gitUrlInput)
            usingCachedConfig = false
        }
    }

    LaunchedEffect(reloadTrigger, isRunning) {
        if (isRunning && reloadTrigger > 0) {
            BleGatewayService.reloadConfig(context, gitUrlInput, gitTokenInput.ifBlank { null })
        }
    }

    LaunchedEffect(loadedConfig) {
        val config = loadedConfig ?: return@LaunchedEffect
        repository.syncEnabledSensors(config)
    }

    val effectiveEnabledSensors = remember(loadedConfig, enabledSensors, enabledSensorsInitialized) {
        val configKeys = loadedConfig?.allSensorKeys().orEmpty()
        when {
            configKeys.isEmpty() -> enabledSensors
            !enabledSensorsInitialized || enabledSensors.isEmpty() -> configKeys
            else -> enabledSensors
        }
    }

    val permissionsToRequest = mutableListOf<String>().apply {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    fun refreshPermissions() {
        missingPermCount = permissionsToRequest.count {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
    }

    val launcher = rememberLauncherForActivityResult(contract = ActivityResultContracts.RequestMultiplePermissions()) {
        refreshPermissions()
    }
    LaunchedEffect(Unit) {
        refreshPermissions()
        val ungranted = permissionsToRequest.filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
        if (ungranted.isNotEmpty()) launcher.launch(ungranted.toTypedArray())
    }
    DisposableEffect(lifecycleOwner) {
        val permObserver = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshPermissions()
        }
        lifecycleOwner.lifecycle.addObserver(permObserver)
        onDispose { lifecycleOwner.lifecycle.removeObserver(permObserver) }
    }

    // BLE 데이터 수신 속도 측정 → 고양이 달리기 속도
    var bleDataRate by remember { mutableIntStateOf(0) }
    var bleDataCounter by remember { mutableIntStateOf(0) }
    LaunchedEffect(sensorLastValues) { bleDataCounter++ }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            bleDataRate = bleDataCounter
            bleDataCounter = 0
        }
    }

    var scanningDevice by remember { mutableStateOf<DeviceConfig?>(null) }
    var selectedTab by remember { mutableStateOf(0) }

    LaunchedEffect(selectedTab) {
        if (selectedTab != 2) {
            LiveEventLogger.isLiveActive = false
        }
    }

    Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
        if (missingPermCount > 0) {
            PermissionBanner(missingCount = missingPermCount)
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(text = stringResource(R.string.app_name), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(text = "v${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.labelSmall, color = Color.Gray, modifier = Modifier.padding(bottom = 4.dp))
                }
                Text(text = stringResource(R.string.app_sub_title), style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            }
            StatusBadge(isRunning = isRunning, connState = connState, connectionIssue = connectionIssue)
        }

        val showCacheBanner = usingCachedConfig || usingCachedConfigService
        if (showCacheBanner) {
            InfoBanner(
                text = stringResource(R.string.config_cache_banner),
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        serviceError?.let { err ->
            WarningBanner(
                text = stringResource(R.string.service_error_banner, err),
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when (selectedTab) {
                0 -> GatewayTabContent(
                    isRunning = isRunning,
                    connState = connState,
                    connectionIssue = connectionIssue,
                    urlInput = urlInput,
                    onUrlChange = { urlInput = it },
                    tokenInput = tokenInput,
                    onTokenChange = {
                        tokenInput = it
                        scope.launch { repository.clearHaRefreshToken() }
                    },
                    haRefreshToken = savedHaRefreshToken,
                    haTokenLastRefreshed = haTokenLastRefreshed,
                    onClearOAuth = {
                        scope.launch {
                            repository.clearHaRefreshToken()
                            repository.saveHaSettings(urlInput, "")
                            tokenInput = ""
                        }
                    },
                    startOnBoot = startOnBoot,
                    onStartOnBootChange = { scope.launch { repository.saveStartOnBoot(it) } },
                    scanMode = scanMode,
                    onScanModeChange = { scope.launch { repository.saveScanMode(it) } },
                    isBatteryIgnored = isBatteryIgnored,
                    onShowOnboarding = { showOnboarding = true },
                    onStartGateway = {
                        when {
                            urlInput.isBlank() || (tokenInput.isBlank() && savedHaRefreshToken.isBlank()) ->
                                Toast.makeText(context, context.getString(R.string.fill_all_fields_toast), Toast.LENGTH_SHORT).show()
                            gitUrlInput.isBlank() ->
                                Toast.makeText(context, context.getString(R.string.config_not_set_toast), Toast.LENGTH_SHORT).show()
                            else -> scope.launch {
                                repository.saveHaSettings(urlInput, tokenInput)
                                repository.saveGitSettings(gitUrlInput, gitTokenInput.ifBlank { null })
                                val refreshToken = repository.haRefreshToken.first()
                                BleGatewayService.start(context, urlInput, tokenInput, refreshToken, gitUrlInput, gitTokenInput.ifBlank { null })
                            }
                        }
                    },
                    onStopGateway = { BleGatewayService.stop(context) },
                )
                1 -> SensorsTabContent(
                    isRunning = isRunning,
                    isConfigLoading = isConfigLoading,
                    configError = configError,
                    loadedConfig = loadedConfig,
                    boundDevices = boundDevices,
                    enabledSensors = effectiveEnabledSensors,
                    disabledDeviceIds = disabledDeviceIds,
                    autoConnectDisabledIds = autoConnectDisabledIds,
                    discoveredAdv = discoveredAdv,
                    sensorLastValues = sensorLastValues,
                    deviceLinkStatuses = deviceLinkStatuses,
                    gitRepoInput = gitRepoInput,
                    gitFileInput = gitFileInput,
                    gitTokenInput = gitTokenInput,
                    onGitRepoChange = { gitRepoInput = it },
                    onGitFileChange = { gitFileInput = it },
                    onGitTokenChange = { gitTokenInput = it },
                    onReloadConfig = { reloadTrigger++ },
                    onBindClick = { scanningDevice = it },
                    onUnbindClick = { deviceId -> scope.launch { repository.unbindDevice(deviceId) } },
                    onSensorToggle = { key, enabled ->
                        scope.launch { repository.setSensorEnabled(key, enabled) }
                    },
                    onSensorsBulkToggle = { keys, enabled ->
                        scope.launch { repository.setSensorsEnabled(keys, enabled) }
                    },
                    onDisableDevice = { deviceId -> BleGatewayService.disableDevice(context, deviceId) },
                    onEnableDevice = { deviceId -> BleGatewayService.enableDevice(context, deviceId) },
                    onSetAutoConnect = { deviceId, enabled -> BleGatewayService.setAutoConnect(context, deviceId, enabled) },
                )
                2 -> LogsTabContent()
            }
        }

        CatRunOverlay(dataRate = bleDataRate)

        NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
            NavigationBarItem(
                selected = selectedTab == 0,
                onClick = { selectedTab = 0 },
                icon = { Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.tab_gateway)) },
                label = { Text(stringResource(R.string.tab_gateway)) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.primary,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                ),
            )
            NavigationBarItem(
                selected = selectedTab == 1,
                onClick = { selectedTab = 1 },
                icon = { Icon(Icons.Default.Info, contentDescription = stringResource(R.string.tab_sensors)) },
                label = { Text(stringResource(R.string.tab_sensors)) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.primary,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                ),
            )
            NavigationBarItem(
                selected = selectedTab == 2,
                onClick = { selectedTab = 2 },
                icon = { Icon(Icons.Default.List, contentDescription = stringResource(R.string.tab_logs)) },
                label = { Text(stringResource(R.string.tab_logs)) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.primary,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                ),
            )
        }
    }

    scanningDevice?.let { device ->
        BleScanDialog(
            deviceConfig = device,
            onDismiss = { scanningDevice = null },
            onDeviceSelected = { mac ->
                scope.launch {
                    repository.bindDevice(device.id, mac)
                    scanningDevice = null
                }
            }
        )
    }

    if (showOnboarding) {
        OnboardingDialog(onDismiss = {
            showOnboarding = false
            scope.launch { repository.setOnboardingComplete(true) }
        })
    }
}



@Composable
private fun GatewayTabContent(
    isRunning: Boolean,
    connState: ConnectionState,
    connectionIssue: ConnectionIssue,
    urlInput: String,
    onUrlChange: (String) -> Unit,
    tokenInput: String,
    onTokenChange: (String) -> Unit,
    haRefreshToken: String,
    haTokenLastRefreshed: Long,
    onClearOAuth: () -> Unit,
    startOnBoot: Boolean,
    onStartOnBootChange: (Boolean) -> Unit,
    scanMode: dev.eigger.hassble.config.BleScanModeOption,
    onScanModeChange: (dev.eigger.hassble.config.BleScanModeOption) -> Unit,
    isBatteryIgnored: Boolean,
    onShowOnboarding: () -> Unit,
    onStartGateway: () -> Unit,
    onStopGateway: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val inputsEnabled = !isRunning
    var isTestingConnection by remember { mutableStateOf(false) }
    var showClearOAuthDialog by remember { mutableStateOf(false) }
    val issueMessage = connectionIssueMessage(connectionIssue)
    val gatewayId = remember {
        android.provider.Settings.Secure.getString(context.contentResolver, android.provider.Settings.Secure.ANDROID_ID) ?: "hassble"
    }

    if (showClearOAuthDialog) {
        Dialog(onDismissRequest = { showClearOAuthDialog = false }) {
            Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(stringResource(R.string.oauth_clear_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(stringResource(R.string.oauth_clear_body), color = Color.Gray, fontSize = 13.sp)
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { showClearOAuthDialog = false }) {
                            Text(stringResource(R.string.cancel))
                        }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = { showClearOAuthDialog = false; onClearOAuth() },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                            shape = RoundedCornerShape(8.dp),
                        ) {
                            Text(stringResource(R.string.oauth_clear_confirm))
                        }
                    }
                }
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(imageVector = Icons.Default.Info, contentDescription = "Status", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = stringResource(R.string.system_monitor), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                }
                Spacer(modifier = Modifier.height(12.dp))
                StatusRow(label = stringResource(R.string.service_running), value = if (isRunning) stringResource(R.string.status_running) else stringResource(R.string.status_stopped), isHighlighted = isRunning)
                StatusRow(label = stringResource(R.string.connection_status), value = when (connState) {
                    ConnectionState.Connected -> stringResource(R.string.status_connected)
                    ConnectionState.Connecting -> stringResource(R.string.status_connecting)
                    ConnectionState.Disconnected -> stringResource(R.string.status_disconnected)
                }, isHighlighted = connState == ConnectionState.Connected)
                StatusRow(label = stringResource(R.string.gateway_model), value = Build.MODEL, isHighlighted = false)
                StatusRow(
                    label = stringResource(R.string.gateway_id_click_to_copy),
                    value = gatewayId,
                    isHighlighted = false,
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        val clip = android.content.ClipData.newPlainText("Gateway ID", gatewayId)
                        clipboard.setPrimaryClip(clip)
                        Toast.makeText(context, context.getString(R.string.gateway_id_copied), Toast.LENGTH_SHORT).show()
                    }
                )
                if (issueMessage != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(text = issueMessage, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(imageVector = Icons.Default.Settings, contentDescription = "Settings", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = stringResource(R.string.server_integration_settings), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    }
                    TextButton(onClick = onShowOnboarding) {
                        Text(stringResource(R.string.show_onboarding_again), fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                    }
                }
                val repository = remember { HassSettingsRepository(context) }
                var urlError by remember { mutableStateOf<String?>(null) }
                LaunchedEffect(urlInput) {
                    urlError = validateHaUrl(context, urlInput)
                }
 
                if (haRefreshToken.isNotBlank()) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f), RoundedCornerShape(8.dp))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = "OAuth Active",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = stringResource(R.string.oauth_active_chip), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Text(text = stringResource(R.string.oauth_last_refreshed, formatLastRefreshed(context, haTokenLastRefreshed)), color = Color.Gray, fontSize = 10.sp)
                        }
                        TextButton(onClick = { showClearOAuthDialog = true }, enabled = inputsEnabled) {
                            Text(stringResource(R.string.oauth_logout_btn), color = Color.Gray, fontSize = 11.sp)
                        }
                    }
                }
 
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedTextField(
                    value = urlInput,
                    onValueChange = {
                        onUrlChange(it)
                        urlError = validateHaUrl(context, it)
                    },
                    label = { Text("HA URL") },
                    enabled = inputsEnabled,
                    isError = urlError != null,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )
                urlError?.let {
                    Text(
                        text = it,
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(start = 4.dp, top = 2.dp)
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = {
                        if (urlInput.isBlank() || urlInput == "https://" || urlInput == "http://") {
                            Toast.makeText(context, context.getString(R.string.ha_url_required_toast), Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                        val state = java.util.UUID.randomUUID().toString()
                        scope.launch {
                            repository.saveHaAuthState(state)
                        }
                        val authUrl = dev.eigger.hassble.net.HaAuthHelper.getAuthorizeUrl(urlInput, state)
                        try {
                            val customTabsIntent = androidx.browser.customtabs.CustomTabsIntent.Builder().build()
                            customTabsIntent.launchUrl(context, Uri.parse(authUrl))
                        } catch (e: Exception) {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(authUrl)))
                        }
                    },
                    enabled = inputsEnabled && urlInput.isNotBlank() && urlInput != "https://" && urlInput != "http://",
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.oauth_login_btn), color = Color.Black, fontWeight = FontWeight.Bold)
                }
                if (haRefreshToken.isBlank()) {
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(value = tokenInput, onValueChange = onTokenChange, label = { Text(stringResource(R.string.long_lived_token)) }, enabled = inputsEnabled, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp))
                }
                Spacer(modifier = Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (haRefreshToken.isNotBlank()) {
                        Text(
                            stringResource(R.string.test_connection_oauth_hint),
                            color = Color.Gray,
                            fontSize = 11.sp,
                            modifier = Modifier.weight(1f),
                        )
                    } else {
                        Button(
                            onClick = {
                                if (urlInput.isBlank() || tokenInput.isBlank()) {
                                    Toast.makeText(context, context.getString(R.string.fill_all_fields_toast), Toast.LENGTH_SHORT).show()
                                    return@Button
                                }
                                scope.launch {
                                    isTestingConnection = true
                                    when (val result = HaConnectionTester.test(urlInput, tokenInput)) {
                                        is HaConnectionTester.Result.Ok ->
                                            Toast.makeText(context, context.getString(R.string.test_connection_ok), Toast.LENGTH_SHORT).show()
                                        is HaConnectionTester.Result.AuthFailed ->
                                            Toast.makeText(context, context.getString(R.string.test_connection_auth_failed, result.code), Toast.LENGTH_LONG).show()
                                        is HaConnectionTester.Result.NetworkError ->
                                            Toast.makeText(context, context.getString(R.string.test_connection_failed, result.message), Toast.LENGTH_LONG).show()
                                    }
                                    isTestingConnection = false
                                }
                            },
                            enabled = !isTestingConnection,
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f), contentColor = MaterialTheme.colorScheme.primary),
                            shape = RoundedCornerShape(8.dp),
                        ) {
                            if (isTestingConnection) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            } else {
                                Text(stringResource(R.string.test_connection_btn), fontSize = 12.sp)
                            }
                        }
                    }
                    Button(
                        onClick = {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(context.getString(R.string.ws_bridge_url))))
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.08f), contentColor = Color.White),
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Text(stringResource(R.string.open_ws_bridge_repo), fontSize = 11.sp)
                    }
                }
            }
        }

        GatewayControlButton(
            isRunning = isRunning,
            onStart = onStartGateway,
            onStop = onStopGateway,
        )

        Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(imageVector = Icons.Default.Settings, contentDescription = "Settings", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = stringResource(R.string.background_battery_settings), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                }
                Spacer(modifier = Modifier.height(16.dp))

                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = stringResource(R.string.start_on_boot), fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = Color.White)
                    }
                    Switch(
                        checked = startOnBoot,
                        onCheckedChange = onStartOnBootChange,
                        colors = SwitchDefaults.colors(checkedThumbColor = Color.Black, checkedTrackColor = MaterialTheme.colorScheme.primary),
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(text = stringResource(R.string.ble_scan_mode), fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = Color.White)
                    Text(
                        text = when (scanMode) {
                            dev.eigger.hassble.config.BleScanModeOption.LOW_POWER -> stringResource(R.string.scan_mode_low_power)
                            dev.eigger.hassble.config.BleScanModeOption.BALANCED -> stringResource(R.string.scan_mode_balanced)
                            dev.eigger.hassble.config.BleScanModeOption.LOW_LATENCY -> stringResource(R.string.scan_mode_low_latency)
                        },
                        fontSize = 11.sp, color = Color.Gray,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        dev.eigger.hassble.config.BleScanModeOption.entries.forEach { mode ->
                            val selected = scanMode == mode
                            val btnTextId = when (mode) {
                                dev.eigger.hassble.config.BleScanModeOption.LOW_POWER -> R.string.scan_mode_low_power_btn
                                dev.eigger.hassble.config.BleScanModeOption.BALANCED -> R.string.scan_mode_balanced_btn
                                dev.eigger.hassble.config.BleScanModeOption.LOW_LATENCY -> R.string.scan_mode_low_latency_btn
                            }
                            Button(
                                onClick = { onScanModeChange(mode) },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (selected) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.08f),
                                    contentColor = if (selected) Color.Black else Color.White,
                                ),
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            ) {
                                Text(stringResource(btnTextId), fontSize = 11.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = stringResource(R.string.battery_opt_status), fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = Color.White)
                        Text(
                            text = if (isBatteryIgnored) stringResource(R.string.battery_opt_ignored) else stringResource(R.string.battery_opt_active),
                            fontSize = 12.sp,
                            color = if (isBatteryIgnored) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error,
                        )
                    }
                    if (!isBatteryIgnored) {
                        Button(
                            onClick = {
                                try {
                                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                        data = Uri.parse("package:${context.packageName}")
                                    }
                                    context.startActivity(intent)
                                } catch (e: Exception) {
                                    context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                            shape = RoundedCornerShape(8.dp),
                        ) {
                            Text(text = stringResource(R.string.request_battery_ignore_btn), color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = stringResource(R.string.hide_notif_icon_btn), fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = Color.White)
                        Text(text = stringResource(R.string.hide_notif_icon_desc), fontSize = 11.sp, color = Color.Gray)
                    }
                    Button(
                        onClick = {
                            val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
                                    putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                    putExtra(Settings.EXTRA_CHANNEL_ID, "ble_gateway")
                                }
                            } else {
                                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                                    putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                }
                            }
                            context.startActivity(intent)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.1f), contentColor = Color.White),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.padding(start = 8.dp),
                    ) {
                        Text(text = stringResource(R.string.go_to_settings_btn), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
    }
}


@Composable
private fun SensorsTabContent(
    isRunning: Boolean,
    isConfigLoading: Boolean,
    configError: String?,
    loadedConfig: GatewayConfig?,
    boundDevices: Map<String, String>,
    enabledSensors: Set<String>,
    disabledDeviceIds: Set<String>,
    autoConnectDisabledIds: Set<String>,
    discoveredAdv: List<DiscoveredAdvInstance>,
    sensorLastValues: List<SensorLastValue>,
    deviceLinkStatuses: List<DeviceLinkStatus>,
    gitRepoInput: String,
    gitFileInput: String,
    gitTokenInput: String,
    onGitRepoChange: (String) -> Unit,
    onGitFileChange: (String) -> Unit,
    onGitTokenChange: (String) -> Unit,
    onReloadConfig: () -> Unit,
    onBindClick: (DeviceConfig) -> Unit,
    onUnbindClick: (String) -> Unit,
    onSensorToggle: (String, Boolean) -> Unit,
    onSensorsBulkToggle: (Set<String>, Boolean) -> Unit,
    onDisableDevice: (String) -> Unit,
    onEnableDevice: (String) -> Unit,
    onSetAutoConnect: (String, Boolean) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            GitConfigSection(
                repoInput = gitRepoInput,
                fileInput = gitFileInput,
                tokenInput = gitTokenInput,
                onRepoChange = onGitRepoChange,
                onFileChange = onGitFileChange,
                onTokenChange = onGitTokenChange,
            )
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.devices_adapters_list),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                )
                Button(
                    onClick = onReloadConfig,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f), contentColor = MaterialTheme.colorScheme.primary),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)),
                ) {
                    Text(text = stringResource(R.string.reload_config_btn), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        if (isRunning) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = stringResource(R.string.reload_config_running_hint),
                        color = Color.Gray,
                        fontSize = 11.sp,
                    )
                    Text(
                        text = stringResource(R.string.sensor_edit_locked_running),
                        color = Color.Gray,
                        fontSize = 11.sp,
                    )
                    Text(
                        text = stringResource(R.string.bind_locked_running),
                        color = Color.Gray,
                        fontSize = 11.sp,
                    )
                }
            }
        }

        if (configError != null && loadedConfig != null) {
            item { WarningBanner(text = stringResource(R.string.config_cache_banner)) }
        }

        if (isConfigLoading) {
            item {
                Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            }
        } else if (configError != null && loadedConfig == null) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.1f)),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.3f)),
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(imageVector = Icons.Default.Warning, contentDescription = "Error", tint = MaterialTheme.colorScheme.error)
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = stringResource(R.string.config_load_error, configError),
                                color = MaterialTheme.colorScheme.error,
                                fontSize = 14.sp,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        Button(
                            onClick = onReloadConfig,
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.align(Alignment.End),
                        ) {
                            Text(text = stringResource(R.string.retry_btn), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        } else if (loadedConfig == null || loadedConfig.devices.isEmpty()) {
            item {
                Text(
                    text = stringResource(R.string.no_devices_configured),
                    color = Color.Gray,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
            }
        } else {
            items(loadedConfig.devices, key = { it.id }) { device ->
                DeviceConfigCard(
                    device = device,
                    boundMac = boundDevices[device.id],
                    enabledSensors = enabledSensors,
                    isRunning = isRunning,
                    isDisabled = device.id in disabledDeviceIds,
                    autoConnect = device.id !in autoConnectDisabledIds,
                    discoveredInstances = discoveredAdv.filter { it.profileId == device.id },
                    sensorLastValues = sensorLastValues.filter { it.profileId == device.id },
                    linkStatus = deviceLinkStatuses.firstOrNull { it.profileId == device.id },
                    onSensorToggle = onSensorToggle,
                    onSensorsBulkToggle = onSensorsBulkToggle,
                    onBindClick = { onBindClick(device) },
                    onUnbindClick = { onUnbindClick(device.id) },
                    onDisable = { onDisableDevice(device.id) },
                    onEnable = { onEnableDevice(device.id) },
                    onSetAutoConnect = { enabled -> onSetAutoConnect(device.id, enabled) },
                )
            }
        }

        item { Spacer(modifier = Modifier.height(4.dp)) }
    }
}

@Composable
private fun GatewayControlButton(isRunning: Boolean, onStart: () -> Unit, onStop: () -> Unit) {
    val buttonBrush = if (isRunning) {
        Brush.horizontalGradient(listOf(Color(0xFFFF5252), Color(0xFFFF1744)))
    } else {
        Brush.horizontalGradient(listOf(Color(0xFF9E86FF), Color(0xFF651FFF)))
    }
    Box(modifier = Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(28.dp)).background(buttonBrush)) {
        Button(
            onClick = { if (isRunning) onStop() else onStart() },
            modifier = Modifier.fillMaxSize(),
            colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
            shape = RoundedCornerShape(28.dp),
        ) {
            Text(
                text = if (isRunning) stringResource(R.string.stop_gateway) else stringResource(R.string.start_gateway),
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DeviceConfigCard(
    device: DeviceConfig,
    boundMac: String?,
    enabledSensors: Set<String>,
    isRunning: Boolean,
    isDisabled: Boolean,
    autoConnect: Boolean,
    discoveredInstances: List<DiscoveredAdvInstance>,
    sensorLastValues: List<SensorLastValue>,
    linkStatus: DeviceLinkStatus?,
    onSensorToggle: (String, Boolean) -> Unit,
    onSensorsBulkToggle: (Set<String>, Boolean) -> Unit,
    onBindClick: () -> Unit,
    onUnbindClick: () -> Unit,
    onDisable: () -> Unit,
    onEnable: () -> Unit,
    onSetAutoConnect: (Boolean) -> Unit,
) {
    val deviceSensorKeys = remember(device.id, device.sensors) {
        device.sensors.map { "${device.id}/${it.key}" }
    }
    val enabledCount = deviceSensorKeys.count { it in enabledSensors }
    var expanded by remember(device.id) { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isDisabled) MaterialTheme.colorScheme.surface.copy(alpha = 0.5f)
            else MaterialTheme.colorScheme.surface
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = device.name,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            color = if (isDisabled) Color.Gray else Color.White,
                        )
                        if (isDisabled) {
                            Text(
                                text = stringResource(R.string.device_disabled),
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier
                                    .background(MaterialTheme.colorScheme.error.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }
                    Text(text = "ID: ${device.id}", fontSize = 12.sp, color = Color.Gray)
                    if (device.sensors.isNotEmpty()) {
                        Text(
                            text = stringResource(R.string.sensors_enabled_summary, enabledCount, device.sensors.size),
                            fontSize = 11.sp,
                            color = if (enabledCount > 0) MaterialTheme.colorScheme.secondary else Color.Gray,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    if (device.source == Source.advertisement) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            val badgeColor = Color(0xFF02B3E4)
                            SourceBadge(
                                text = stringResource(R.string.source_advertisement),
                                color = badgeColor,
                            )
                            val reg = advertisementRegistrationKind(device)
                            SourceBadge(text = reg.label, color = reg.color)
                        }
                    }
                }
                if (device.source != Source.advertisement) {
                    Column(horizontalAlignment = Alignment.End) {
                        val badgeColor = when (device.source) { Source.gatt_notify -> Color(0xFF9E86FF); Source.obd -> Color(0xFFFF9100); else -> Color.Gray }
                        val badgeText = when (device.source) {
                            Source.gatt_notify -> stringResource(R.string.source_gatt_notify)
                            Source.obd -> stringResource(R.string.source_obd)
                            else -> ""
                        }
                        SourceBadge(text = badgeText, color = badgeColor)
                    }
                }
                Icon(
                    imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    tint = Color.Gray,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }

            if (expanded) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Spacer(modifier = Modifier.height(12.dp))
                    val showPerSensorValues = isRunning

                    if (device.sensors.isNotEmpty()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = stringResource(R.string.enabled_sensors),
                                fontSize = 12.sp,
                                color = Color.Gray,
                                fontWeight = FontWeight.SemiBold,
                            )
                            if (!isRunning) {
                                Row {
                                    TextButton(
                                        onClick = { onSensorsBulkToggle(deviceSensorKeys.toSet(), true) },
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                    ) {
                                        Text(
                                            text = stringResource(R.string.sensors_enable_all),
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                    TextButton(
                                        onClick = { onSensorsBulkToggle(deviceSensorKeys.toSet(), false) },
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                    ) {
                                        Text(
                                            text = stringResource(R.string.sensors_disable_all),
                                            fontSize = 11.sp,
                                            color = Color.Gray,
                                        )
                                    }
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        device.sensors.forEach { sensor ->
                            val sensorKey = "${device.id}/${sensor.key}"
                            val checked = sensorKey in enabledSensors
                            val latest = sensorLastValues
                                .filter { it.sensorKey == sensor.key }
                                .maxByOrNull { it.updatedAtMs }
                            Column(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = sensorDisplayLabel(sensor),
                                        color = if (checked) Color.White else Color.Gray,
                                        fontSize = 12.sp,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Switch(
                                        checked = checked,
                                        onCheckedChange = { onSensorToggle(sensorKey, it) },
                                        enabled = !isRunning,
                                        colors = SwitchDefaults.colors(
                                            checkedThumbColor = Color.Black,
                                            checkedTrackColor = MaterialTheme.colorScheme.primary,
                                        ),
                                    )
                                }
                                if (showPerSensorValues && checked) {
                                    Text(
                                        text = if (latest != null) {
                                            stringResource(
                                                R.string.sensor_value_updated,
                                                latest.value,
                                                lastSeenText(latest.updatedAtMs),
                                            )
                                        } else {
                                            stringResource(R.string.sensor_no_data)
                                        },
                                        color = if (latest != null) MaterialTheme.colorScheme.secondary else Color.Gray,
                                        fontSize = 11.sp,
                                        modifier = Modifier.padding(start = 2.dp, top = 2.dp),
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                    }

                    if (device.controls.isNotEmpty()) {
                        Text(
                            text = stringResource(R.string.ha_controls),
                            fontSize = 12.sp,
                            color = Color.Gray,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        device.controls.forEach { control ->
                            val label = control.name ?: control.key.replace('_', ' ').replaceFirstChar { it.uppercase() }
                            Text(
                                text = "$label · ${controlTypeLabel(control.type)}",
                                color = Color.White,
                                fontSize = 12.sp,
                            )
                            Text(
                                text = stringResource(R.string.controls_ha_only_hint, controlTypeLabel(control.type)),
                                color = Color.Gray,
                                fontSize = 10.sp,
                                modifier = Modifier.padding(bottom = 4.dp),
                            )
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.Black.copy(alpha = 0.2f))
                            .padding(12.dp),
                    ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                when (device.source) {
                    Source.advertisement -> {
                        val reg = advertisementRegistrationKind(device)
                        if (!isRunning) {
                            Text(text = reg.hint, color = Color.Gray, fontSize = 11.sp)
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                        when {
                            !isRunning -> {
                                Text(
                                    text = stringResource(R.string.discovered_devices_waiting),
                                    color = Color.Gray,
                                    fontSize = 12.sp,
                                )
                            }
                            discoveredInstances.isEmpty() -> {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(modifier = Modifier.size(6.dp).background(Color(0xFF00E676), CircleShape))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = stringResource(R.string.auto_detecting),
                                        color = Color(0xFF00E676),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                }
                            }
                            else -> {
                                Text(
                                    text = stringResource(R.string.discovered_devices_count, discoveredInstances.size),
                                    color = Color(0xFF00E676),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                discoveredInstances.forEachIndexed { index, inst ->
                                    if (index > 0) {
                                        HorizontalDivider(
                                            modifier = Modifier.padding(vertical = 8.dp),
                                            color = Color.Gray.copy(alpha = 0.25f),
                                        )
                                    }
                                    val macBased = reg.macBasedPerInstance
                                    Column(modifier = Modifier.fillMaxWidth()) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                        ) {
                                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                                Box(modifier = Modifier.size(6.dp).background(Color(0xFF00E676), CircleShape))
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Text(
                                                    text = inst.deviceName ?: stringResource(R.string.unknown_device),
                                                    color = Color.White,
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.Medium,
                                                )
                                            }
                                            MacBasedChip(macBased = macBased)
                                        }
                                        Text(
                                            text = inst.mac,
                                            color = Color.Gray,
                                            fontSize = 11.sp,
                                            modifier = Modifier.padding(start = 14.dp),
                                        )
                                        Text(
                                            text = stringResource(R.string.adv_instance_entity_id, inst.instanceId),
                                            color = Color.Gray,
                                            fontSize = 10.sp,
                                            modifier = Modifier.padding(start = 14.dp),
                                        )
                                        Text(
                                            text = lastSeenText(inst.lastSeenMs),
                                            color = Color.Gray,
                                            fontSize = 10.sp,
                                            modifier = Modifier.padding(start = 14.dp),
                                        )
                                        val instValues = sensorLastValues.filter { it.instanceId == inst.instanceId }
                                        if (instValues.isNotEmpty()) {
                                            instValues.forEach { v ->
                                                Text(
                                                    text = stringResource(
                                                        R.string.sensor_value_updated,
                                                        "${v.sensorKey}: ${v.value}",
                                                        lastSeenText(v.updatedAtMs),
                                                    ),
                                                    color = MaterialTheme.colorScheme.secondary,
                                                    fontSize = 10.sp,
                                                    modifier = Modifier.padding(start = 14.dp, top = 2.dp),
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    Source.gatt_notify, Source.obd -> {
                        if (device.source == Source.obd && isRunning &&
                            linkStatus?.state == DeviceLinkState.Error
                        ) {
                            Text(
                                text = stringResource(R.string.obd_reconnecting_hint),
                                color = Color(0xFFFF9100),
                                fontSize = 10.sp,
                                modifier = Modifier.padding(bottom = 6.dp),
                            )
                        }
                        val hardcodedMac = if (device.source == Source.gatt_notify) device.gatt?.mac else device.obd?.mac
                        if (!hardcodedMac.isNullOrBlank()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(modifier = Modifier.size(6.dp).background(Color.Gray, CircleShape))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(text = stringResource(R.string.bound_in_config, hardcodedMac), color = Color.LightGray, fontSize = 12.sp)
                            }
                            if (isRunning && linkStatus != null) {
                                DeviceLinkStatusRow(linkStatus)
                            }
                        } else if (!boundMac.isNullOrBlank()) {
                            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                    Box(modifier = Modifier.size(6.dp).background(Color(0xFF00E676), CircleShape))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(text = stringResource(R.string.connected_mac, boundMac), color = Color(0xFF00E676), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                }
                                Row {
                                    Text(text = stringResource(R.string.rebind), color = MaterialTheme.colorScheme.primary, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clickable { onBindClick() }.padding(horizontal = 8.dp, vertical = 4.dp))
                                    Text(text = stringResource(R.string.unbind), color = MaterialTheme.colorScheme.error, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clickable { onUnbindClick() }.padding(horizontal = 8.dp, vertical = 4.dp))
                                }
                            }
                            if (isRunning && linkStatus != null) {
                                DeviceLinkStatusRow(linkStatus)
                            }
                        } else {
                            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(modifier = Modifier.size(6.dp).background(MaterialTheme.colorScheme.error, CircleShape))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(text = stringResource(R.string.connection_needed), color = MaterialTheme.colorScheme.error, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                }
                                Box(modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.primary).clickable { onBindClick() }.padding(horizontal = 12.dp, vertical = 6.dp)) {
                                    Text(text = stringResource(R.string.bind_connect), color = Color.Black, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
                }
                    }

                    ConfigDetailSection(
                        device = device,
                        discoveredInstances = discoveredInstances,
                    )

                    val isConnected = linkStatus?.state == DeviceLinkState.Connected ||
                            linkStatus?.state == DeviceLinkState.Polling

                    if (isRunning && (device.source == Source.gatt_notify || device.source == Source.obd)) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(stringResource(R.string.auto_connect_label), fontSize = 12.sp, color = if (isConnected) Color.Gray.copy(alpha = 0.5f) else Color.Gray)
                            Switch(
                                checked = autoConnect,
                                onCheckedChange = { onSetAutoConnect(it) },
                                enabled = !isConnected,
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.Black,
                                    checkedTrackColor = MaterialTheme.colorScheme.primary,
                                ),
                            )
                        }
                    }

                    if (isRunning) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            if (isDisabled) {
                                Button(
                                    onClick = onEnable,
                                    enabled = !isConnected,
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                                        contentColor = MaterialTheme.colorScheme.primary,
                                    ),
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                ) {
                                    Text(stringResource(R.string.device_enable_btn), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            } else {
                                Button(
                                    onClick = onDisable,
                                    enabled = !isConnected,
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.12f),
                                        contentColor = MaterialTheme.colorScheme.error,
                                    ),
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                ) {
                                    Text(stringResource(R.string.device_disable_ha_delete_btn), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConfigDetailSection(
    device: DeviceConfig,
    discoveredInstances: List<DiscoveredAdvInstance>,
) {
    var open by remember(device.id) { mutableStateOf(false) }
    Spacer(modifier = Modifier.height(8.dp))
    Text(
        text = stringResource(if (open) R.string.config_detail_hide else R.string.config_detail_show),
        color = MaterialTheme.colorScheme.primary,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .clickable { open = !open }
            .padding(vertical = 4.dp),
    )
    if (!open) return

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.Black.copy(alpha = 0.25f))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        device.match?.let { m ->
            val criteria = buildList {
                m.mac?.let { add("mac=$it") }
                m.serviceDataUuid?.let { add("service_uuid=$it") }
                m.manufacturerId?.let { add("manufacturer_id=0x%04X".format(it)) }
                m.manufacturerHexPrefix?.let { add("hex_prefix=$it") }
                m.manufacturerMinLength?.let { add("min_len=$it") }
                m.namePrefix?.let { add("name_prefix=$it") }
            }
            if (criteria.isNotEmpty()) {
                CfgLabel(stringResource(R.string.cfg_match))
                criteria.forEach { CfgMono(it) }
            }
        }

        val decodeSensors = device.sensors.filter { it.decode != null }
        if (decodeSensors.isNotEmpty()) {
            CfgLabel(stringResource(R.string.cfg_decode))
            decodeSensors.forEach { s ->
                val dec = s.decode!!
                val scalePart = buildString {
                    if (dec.scale != 1.0) append("×${dec.scale}")
                    if (dec.offsetValue != 0.0) append(" ${if (dec.offsetValue >= 0) "+" else ""}${dec.offsetValue}")
                }.trim()
                CfgMono(
                    stringResource(
                        R.string.cfg_decode_line,
                        s.key,
                        s.sourceField.name,
                        dec.offset,
                        dec.length,
                        "${dec.type.name}/${dec.endian.name}",
                        scalePart,
                    ),
                )
            }
        }

        val latest = discoveredInstances.maxByOrNull { it.lastSeenMs }
        CfgLabel(stringResource(R.string.cfg_raw_manufacturer))
        CfgMono(latest?.manufacturerHex ?: stringResource(R.string.cfg_raw_waiting))
        if (device.match?.serviceDataUuid != null || latest?.serviceDataHex != null) {
            CfgLabel(stringResource(R.string.cfg_raw_service))
            CfgMono(latest?.serviceDataHex ?: stringResource(R.string.cfg_raw_waiting))
        }
    }
}

@Composable
private fun CfgLabel(text: String) {
    Text(text = text, color = Color.Gray, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun CfgMono(text: String) {
    Text(
        text = text,
        color = Color.LightGray,
        fontSize = 11.sp,
        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
        modifier = Modifier.padding(start = 6.dp),
    )
}

private enum class AdvRegistrationKind { MacPerDevice, FixedMac, Shared }

private data class AdvRegistrationInfo(
    val kind: AdvRegistrationKind,
    val label: String,
    val hint: String,
    val color: Color,
    val macBasedPerInstance: Boolean,
)

@Composable
private fun advertisementRegistrationKind(device: DeviceConfig): AdvRegistrationInfo {
    val fixedMac = device.match?.mac?.takeIf { it.isNotBlank() }
    return when {
        device.instanceMode == AdvertisementInstanceMode.shared -> AdvRegistrationInfo(
            kind = AdvRegistrationKind.Shared,
            label = stringResource(R.string.adv_reg_shared),
            hint = stringResource(R.string.adv_reg_shared_hint),
            color = Color(0xFFFF9100),
            macBasedPerInstance = false,
        )
        fixedMac != null -> AdvRegistrationInfo(
            kind = AdvRegistrationKind.FixedMac,
            label = stringResource(R.string.adv_reg_fixed_mac, fixedMac),
            hint = stringResource(R.string.adv_reg_fixed_mac_hint),
            color = Color(0xFF9E9E9E),
            macBasedPerInstance = false,
        )
        else -> AdvRegistrationInfo(
            kind = AdvRegistrationKind.MacPerDevice,
            label = stringResource(R.string.adv_reg_mac_per_device),
            hint = stringResource(R.string.adv_reg_mac_per_device_hint),
            color = Color(0xFF02B3E4),
            macBasedPerInstance = true,
        )
    }
}

@Composable
private fun SourceBadge(text: String, color: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.15f))
            .border(1.dp, color.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(text = text, color = color, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun MacBasedChip(macBased: Boolean) {
    val color = if (macBased) Color(0xFF02B3E4) else Color(0xFFFF9100)
    val label = if (macBased) {
        stringResource(R.string.adv_instance_mac_based)
    } else {
        stringResource(R.string.adv_instance_not_mac_based)
    }
    SourceBadge(text = label, color = color)
}

@Composable
private fun BleScanDialog(deviceConfig: DeviceConfig, onDismiss: () -> Unit, onDeviceSelected: (String) -> Unit) {
    val context = LocalContext.current
    val manager = remember { context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager }
    val adapter = remember { manager.adapter }
    val scanner = remember { adapter?.bluetoothLeScanner }
    val devicesList = remember { mutableStateListOf<ScannedDevice>() }
    var isScanning by remember { mutableStateOf(false) }
    var applyFilter by remember { mutableStateOf(true) }
    val targetServiceUuid = if (deviceConfig.source == Source.gatt_notify) deviceConfig.gatt?.serviceUuid else if (deviceConfig.source == Source.obd) deviceConfig.obd?.serviceUuid else null
    val scanCallback = remember {
        object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = result.device.name ?: context.getString(R.string.unknown_device)
                val address = result.device.address
                val rssi = result.rssi
                val uuids = result.scanRecord?.serviceUuids?.map { it.uuid.toString().uppercase() } ?: emptyList()
                val matchesFilter = !applyFilter || targetServiceUuid == null || uuids.any { it.contains(targetServiceUuid.uppercase()) } || name.contains(deviceConfig.name, ignoreCase = true)
                if (matchesFilter) {
                    val existingIndex = devicesList.indexOfFirst { it.address == address }
                    if (existingIndex != -1) devicesList[existingIndex] = ScannedDevice(name, address, rssi) else devicesList.add(ScannedDevice(name, address, rssi))
                }
            }
        }
    }
    LaunchedEffect(applyFilter) {
        if (scanner != null && ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
            if (adapter.isEnabled) {
                devicesList.clear()
                isScanning = true
                scanner.startScan(scanCallback)
            } else Toast.makeText(context, context.getString(R.string.enable_bluetooth_toast), Toast.LENGTH_SHORT).show()
        }
    }
    DisposableEffect(Unit) { onDispose { if (scanner != null && ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) scanner.stopScan(scanCallback) } }
    Dialog(onDismissRequest = onDismiss) {
        Card(modifier = Modifier.fillMaxWidth().height(450.dp), shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(modifier = Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text(text = stringResource(R.string.search_bluetooth_device), fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Color.White)
                        Text(text = stringResource(R.string.find_target_for, deviceConfig.name), fontSize = 12.sp, color = Color.Gray)
                    }
                    if (isScanning) CircularProgressIndicator(modifier = Modifier.size(20.dp), color = MaterialTheme.colorScheme.primary, strokeWidth = 2.dp)
                }
                if (targetServiceUuid != null) {
                    Row(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color.Black.copy(alpha = 0.15f)).padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Column {
                            Text(text = stringResource(R.string.service_match_filter), fontSize = 13.sp, color = Color.White, fontWeight = FontWeight.SemiBold)
                            Text(text = "UUID: ${targetServiceUuid.take(8)}...", fontSize = 11.sp, color = Color.Gray)
                        }
                        Switch(checked = applyFilter, onCheckedChange = { applyFilter = it }, colors = SwitchDefaults.colors(checkedThumbColor = Color.Black, checkedTrackColor = MaterialTheme.colorScheme.primary))
                    }
                }
                Box(modifier = Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color.Black.copy(alpha = 0.2f)).padding(8.dp)) {
                    if (devicesList.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(text = stringResource(R.string.no_devices_found), color = Color.Gray, fontSize = 14.sp) }
                    } else {
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(devicesList) { scanned ->
                                Row(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { onDeviceSelected(scanned.address) }.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                    Column {
                                        Text(text = scanned.name, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = Color.White)
                                        Text(text = scanned.address, fontSize = 12.sp, color = Color.Gray)
                                    }
                                    Text(text = "${scanned.rssi} dBm", color = MaterialTheme.colorScheme.primary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Button(onClick = onDismiss, colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.1f), contentColor = Color.White), shape = RoundedCornerShape(16.dp)) { Text(text = stringResource(R.string.cancel), fontWeight = FontWeight.Bold) }
                }
            }
        }
    }
}

@Composable
private fun StatusRow(label: String, value: String, isHighlighted: Boolean, onClick: (() -> Unit)? = null) {
    val modifier = if (onClick != null) {
        Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { onClick() }
    } else {
        Modifier.fillMaxWidth().padding(vertical = 4.dp)
    }
    Row(modifier = modifier, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(text = label, color = Color.Gray, fontSize = 14.sp)
        Text(text = value, color = if (onClick != null) MaterialTheme.colorScheme.primary else if (isHighlighted) MaterialTheme.colorScheme.secondary else Color.White, fontWeight = if (isHighlighted || onClick != null) FontWeight.Bold else FontWeight.Normal, fontSize = 14.sp)
    }
}

@Composable
private fun StatusBadge(isRunning: Boolean, connState: ConnectionState, connectionIssue: ConnectionIssue) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val alpha by infiniteTransition.animateFloat(initialValue = 0.4f, targetValue = 1f, animationSpec = infiniteRepeatable(animation = tween(1000), repeatMode = RepeatMode.Reverse), label = "pulseAlpha")
    val (color, text) = when {
        !isRunning -> Color.Gray to stringResource(R.string.status_badge_offline)
        connectionIssue == ConnectionIssue.AuthFailed -> MaterialTheme.colorScheme.error to stringResource(R.string.status_badge_auth_failed)
        connectionIssue == ConnectionIssue.BridgeNotResponding -> Color(0xFFFF9100) to stringResource(R.string.status_badge_bridge_timeout)
        connectionIssue == ConnectionIssue.NetworkError -> MaterialTheme.colorScheme.error to stringResource(R.string.status_badge_network_error)
        connState == ConnectionState.Connected -> MaterialTheme.colorScheme.secondary to stringResource(R.string.status_badge_connected)
        connState == ConnectionState.Connecting -> Color(0xFFFFD600) to stringResource(R.string.status_badge_connecting)
        else -> MaterialTheme.colorScheme.error to stringResource(R.string.status_badge_error)
    }
    Row(modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(color.copy(alpha = 0.15f)).border(1.dp, color.copy(alpha = 0.3f), RoundedCornerShape(12.dp)).padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(8.dp).alpha(if (connState == ConnectionState.Connecting) alpha else 1f).background(color, shape = CircleShape))
        Spacer(modifier = Modifier.width(6.dp))
        Text(text = text, color = color, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun LogsTabContent() {
    var isLiveActive by remember { mutableStateOf(LiveEventLogger.isLiveActive) }
    val logsList = remember { mutableStateListOf<LogEntry>() }
    var filterText by remember { mutableStateOf("") }

    LaunchedEffect(isLiveActive) {
        LiveEventLogger.isLiveActive = isLiveActive
    }

    LaunchedEffect(isLiveActive) {
        if (isLiveActive) {
            LiveEventLogger.logFlow.collect { logLine ->
                if (logsList.size >= 500) {
                    logsList.removeAt(0)
                }
                logsList.add(logLine)
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            LiveEventLogger.isLiveActive = false
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.live_logs_enable),
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp,
                        color = Color.White
                    )
                    Text(
                        text = if (isLiveActive) "Streaming logs..." else "Logs paused",
                        fontSize = 12.sp,
                        color = Color.Gray
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { logsList.clear() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.White.copy(alpha = 0.08f),
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Text(stringResource(R.string.logs_clear), fontSize = 12.sp)
                    }
                    Switch(
                        checked = isLiveActive,
                        onCheckedChange = { isLiveActive = it },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.Black,
                            checkedTrackColor = MaterialTheme.colorScheme.primary,
                        ),
                    )
                }
            }
        }

        OutlinedTextField(
            value = filterText,
            onValueChange = { filterText = it },
            placeholder = { Text(stringResource(R.string.logs_filter_placeholder), color = Color.Gray, fontSize = 13.sp) },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = "Search",
                    tint = Color.Gray,
                    modifier = Modifier.size(18.dp)
                )
            },
            trailingIcon = {
                if (filterText.isNotEmpty()) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Clear",
                        tint = Color.Gray,
                        modifier = Modifier
                            .size(18.dp)
                            .clickable { filterText = "" }
                    )
                }
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, color = Color.White),
            colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = Color.White.copy(alpha = 0.12f),
                cursorColor = MaterialTheme.colorScheme.primary
            )
        )

        Card(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.4f)),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f))
        ) {
            val filteredLogs = remember {
                androidx.compose.runtime.derivedStateOf {
                    val query = filterText
                    if (query.isBlank()) {
                        logsList.toList()
                    } else {
                        logsList.filter { entry ->
                            entry.message.contains(query, ignoreCase = true) ||
                                entry.timestamp.contains(query, ignoreCase = true) ||
                                entry.type.name.contains(query, ignoreCase = true)
                        }
                    }
                }
            }.value

            val listState = rememberLazyListState()

            LaunchedEffect(filteredLogs.size) {
                if (filteredLogs.isNotEmpty() && !listState.isScrollInProgress) {
                    listState.animateScrollToItem(filteredLogs.size - 1)
                }
            }

            if (filteredLogs.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = if (logsList.isEmpty()) {
                            "No logs yet. Enable Live Logs to see traffic."
                        } else {
                            "No logs match the filter."
                        },
                        color = Color.Gray,
                        fontSize = 14.sp
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(filteredLogs) { logEntry ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Text(
                                text = logEntry.timestamp,
                                color = Color.Gray,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                fontSize = 10.sp,
                                modifier = Modifier.padding(end = 6.dp, top = 2.dp)
                            )
                            val badgeColor = when (logEntry.type) {
                                LogType.ADV -> Color(0xFFFFCC80)
                                LogType.TX -> Color(0xFFC5E1A5)
                                LogType.RX -> Color(0xFF90CAF9)
                                LogType.NOTIF -> Color(0xFFB39DDB)
                                LogType.LINK -> Color(0xFFEEEEEE)
                            }
                            val badgeText = when (logEntry.type) {
                                LogType.ADV -> "ADV"
                                LogType.TX -> "TX"
                                LogType.RX -> "RX"
                                LogType.NOTIF -> "NOTIF"
                                LogType.LINK -> "LINK"
                            }
                            Box(
                                modifier = Modifier
                                    .padding(end = 6.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(badgeColor.copy(alpha = 0.15f))
                                    .border(0.5.dp, badgeColor.copy(alpha = 0.3f), RoundedCornerShape(4.dp))
                                    .padding(horizontal = 4.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = badgeText,
                                    color = badgeColor,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 9.sp,
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                                )
                            }
                            Text(
                                text = logEntry.message,
                                color = Color.LightGray,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                fontSize = 11.sp,
                                lineHeight = 14.sp,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun validateHaUrl(context: Context, url: String): String? {
    if (url.isBlank() || url == "https://" || url == "http://") return null
    val regex = "^https?://[a-zA-Z0-9.-]+(:[0-9]+)?(/.*)?$".toRegex()
    if (!regex.matches(url)) {
        return context.getString(R.string.invalid_url_format_error)
    }
    return null
}

private fun formatLastRefreshed(context: Context, timestamp: Long): String {
    if (timestamp <= 0L) return context.getString(R.string.time_no_record)
    val diffMs = System.currentTimeMillis() - timestamp
    if (diffMs < 0) return context.getString(R.string.time_just_now)
    val diffSec = diffMs / 1000
    if (diffSec < 60) return context.getString(R.string.time_just_now)
    val diffMin = diffSec / 60
    if (diffMin < 60) return context.getString(R.string.time_minutes_ago, diffMin.toInt())
    val diffHour = diffMin / 60
    if (diffHour < 24) return context.getString(R.string.time_hours_ago, diffHour.toInt())
    val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
    return sdf.format(java.util.Date(timestamp))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GitConfigSection(
    repoInput: String,
    fileInput: String,
    tokenInput: String,
    onRepoChange: (String) -> Unit,
    onFileChange: (String) -> Unit,
    onTokenChange: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var yamlFiles by remember { mutableStateOf<List<String>>(emptyList()) }
    var isBrowsing by remember { mutableStateOf(false) }
    var browseError by remember { mutableStateOf<String?>(null) }
    var dropdownExpanded by remember { mutableStateOf(false) }
    var showTokenField by remember { mutableStateOf(tokenInput.isNotBlank()) }
    var lastBrowsedRepo by remember { mutableStateOf("") }

    LaunchedEffect(tokenInput) { if (tokenInput.isNotBlank()) showTokenField = true }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Settings, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.git_config_section_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }

            // Repo input row + Browse button
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = repoInput,
                    onValueChange = {
                        onRepoChange(it)
                        if (it != lastBrowsedRepo) { yamlFiles = emptyList(); browseError = null }
                    },
                    label = { Text(stringResource(R.string.git_repo_label)) },
                    placeholder = { Text("username/config-repo", color = Color.Gray, fontSize = 13.sp) },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                )
                Button(
                    onClick = {
                        val repo = repoInput.trim()
                        scope.launch {
                            isBrowsing = true
                            browseError = null
                            val result = GitHubHelper.fetchYamlFiles(repo, tokenInput.ifBlank { null })
                            isBrowsing = false
                            lastBrowsedRepo = repo
                            result.onSuccess { files ->
                                yamlFiles = files
                                browseError = null
                                if (files.size == 1 && fileInput.isBlank()) onFileChange(files[0])
                                if (files.isNotEmpty()) dropdownExpanded = true
                            }.onFailure { e ->
                                val errMsg = when (e) {
                                    is dev.eigger.hassble.net.GitHubUnauthorizedException -> context.getString(R.string.github_error_401)
                                    is dev.eigger.hassble.net.GitHubNotFoundException -> context.getString(R.string.github_error_404)
                                    is dev.eigger.hassble.net.GitHubApiException -> context.getString(R.string.github_error_generic, e.code)
                                    else -> e.localizedMessage ?: context.getString(R.string.github_error_network)
                                }
                                browseError = errMsg
                                Toast.makeText(context, errMsg, Toast.LENGTH_LONG).show()
                            }
                        }
                    },
                    enabled = repoInput.isNotBlank() && !isBrowsing,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.height(56.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                        contentColor = MaterialTheme.colorScheme.primary,
                    ),
                ) {
                    if (isBrowsing) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
                    else Icon(Icons.Default.Search, contentDescription = stringResource(R.string.browse_files_btn))
                }
            }

            browseError?.let { err ->
                Text(err, color = MaterialTheme.colorScheme.error, fontSize = 11.sp)
            }

            // File dropdown — shown when files were browsed OR when there's already a selection
            if (yamlFiles.isNotEmpty() || fileInput.isNotBlank()) {
                ExposedDropdownMenuBox(
                    expanded = dropdownExpanded && yamlFiles.isNotEmpty(),
                    onExpandedChange = { if (yamlFiles.isNotEmpty()) dropdownExpanded = it },
                ) {
                    OutlinedTextField(
                        value = fileInput,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(R.string.git_file_label)) },
                        trailingIcon = {
                            if (yamlFiles.isNotEmpty()) ExposedDropdownMenuDefaults.TrailingIcon(expanded = dropdownExpanded)
                        },
                        modifier = Modifier.fillMaxWidth().menuAnchor(),
                        shape = RoundedCornerShape(12.dp),
                    )
                    if (yamlFiles.isNotEmpty()) {
                        ExposedDropdownMenu(expanded = dropdownExpanded, onDismissRequest = { dropdownExpanded = false }) {
                            yamlFiles.forEach { file ->
                                DropdownMenuItem(
                                    text = { Text(file, fontSize = 13.sp) },
                                    onClick = { onFileChange(file); dropdownExpanded = false },
                                )
                            }
                        }
                    }
                }
            }

            // Private repo toggle + token field
            Row(
                modifier = Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { showTokenField = !showTokenField; if (!showTokenField) onTokenChange("") }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                androidx.compose.material3.Checkbox(
                    checked = showTokenField,
                    onCheckedChange = { showTokenField = it; if (!it) onTokenChange("") },
                    colors = androidx.compose.material3.CheckboxDefaults.colors(
                        checkedColor = MaterialTheme.colorScheme.primary,
                        uncheckedColor = Color.Gray,
                    ),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    stringResource(R.string.git_private_repo_checkbox),
                    color = if (showTokenField) Color.White else Color.Gray,
                    fontSize = 13.sp,
                    fontWeight = if (showTokenField) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
            AnimatedVisibility(visible = showTokenField, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                OutlinedTextField(
                    value = tokenInput,
                    onValueChange = onTokenChange,
                    label = { Text(stringResource(R.string.git_token_optional)) },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                )
            }
        }
    }
}

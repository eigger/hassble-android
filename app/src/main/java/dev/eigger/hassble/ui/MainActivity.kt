package dev.eigger.hassble.ui

import android.Manifest
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
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
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.interaction.collectIsDraggedAsState
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
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Close
import dev.eigger.hassble.service.LiveEventLogger
import dev.eigger.hassble.service.LogEntry
import dev.eigger.hassble.service.LogType
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
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
import androidx.compose.runtime.derivedStateOf
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
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.res.stringResource
import dev.eigger.hassble.BuildConfig
import dev.eigger.hassble.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import android.os.PowerManager
import android.provider.Settings
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import dev.eigger.hassble.ble.DiscoveredAdvInstance
import dev.eigger.hassble.ble.SensorLastValue
import dev.eigger.hassble.config.AdvertisementInstanceMode
import dev.eigger.hassble.config.ConfigLoader
import dev.eigger.hassble.config.ConfigTemplates
import dev.eigger.hassble.config.ConfigTemplatesLoader
import dev.eigger.hassble.config.ConfigMerger
import dev.eigger.hassble.config.ConfigValidator
import dev.eigger.hassble.config.DeviceConfig
import dev.eigger.hassble.config.GatewayConfig
import dev.eigger.hassble.config.HassBleDefaults
import dev.eigger.hassble.config.HassSettingsRepository
import dev.eigger.hassble.config.ObdPresetStore
import dev.eigger.hassble.config.ValidationIssue
import dev.eigger.hassble.config.ValidationLevel
import dev.eigger.hassble.config.Source
import dev.eigger.hassble.ble.DeviceLinkState
import dev.eigger.hassble.ble.DeviceLinkStatus
import dev.eigger.hassble.ble.haRemoveModeForDevice
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
                ),
                shapes = Shapes(
                    small = HassBleShapes.Button,
                    medium = HassBleShapes.ButtonLarge,
                    large = HassBleShapes.CardLarge,
                ),
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
    val presets = remember {
        ObdPresetStore.fromYaml(context.assets.open("obd_presets.yaml").bufferedReader().readText())
    }
    val bundledTemplates = remember { ConfigTemplates.fromAssets(context) }
    val loader = remember { ConfigLoader(File(context.filesDir, "config_cache"), presets) }
    val templatesLoader = remember {
        ConfigTemplatesLoader(File(context.filesDir, "templates_cache"), bundledTemplates, loader)
    }
    var configTemplates by remember { mutableStateOf(bundledTemplates) }
    var templatesSource by remember { mutableStateOf(ConfigTemplatesLoader.Source.BUNDLED) }
    var isTemplatesLoading by remember { mutableStateOf(false) }
    var templatesReloadTrigger by remember { mutableStateOf(0) }

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
    var gitBranchInput by remember { mutableStateOf(HassBleDefaults.DEFAULT_BRANCH) }
    var gitTokenInput by remember { mutableStateOf("") }
    var settingsLoaded by remember { mutableStateOf(false) }

    val gitUrlInput = remember(gitRepoInput, gitBranchInput) {
        if (gitRepoInput.isBlank()) "" else GitHubHelper.buildConfigUrl(gitRepoInput.trim(), gitBranchInput)
    }

    LaunchedEffect(savedHaUrl, savedHaToken) {
        urlInput = savedHaUrl
        tokenInput = savedHaToken
        settingsLoaded = true
    }
    LaunchedEffect(savedGitUrl, savedGitToken) {
        val normalized = loader.normalizeUrl(savedGitUrl)
        GitHubHelper.parseRepoBranch(normalized)?.let { (repo, branch) ->
            gitRepoInput = repo
            gitBranchInput = branch
        } ?: run {
            val legacy = savedGitUrl.trim()
            if (legacy.isNotBlank()) {
                gitRepoInput = legacy
                gitBranchInput = HassBleDefaults.DEFAULT_BRANCH
            }
        }
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
        if (gitRepoInput.isNotBlank()) {
            repository.saveGitSettings(gitUrlInput, gitTokenInput.ifBlank { null })
        }
    }


    val isRunning by BleGatewayService.isServiceRunning.collectAsState()
    val connState by BleGatewayService.serviceConnectionState.collectAsState()
    val connectionIssue by BleGatewayService.connectionIssue.collectAsState()
    val serviceError by BleGatewayService.serviceError.collectAsState()
    val usingCachedConfigService by BleGatewayService.usingCachedConfig.collectAsState()
    val deviceLinkStatuses by BleGatewayService.deviceLinkStatuses.collectAsState()
    val excludedDeviceIds by repository.excludedDevices.collectAsState(initial = emptySet())
    val autoConnectDisabledIds by repository.autoConnectDisabled.collectAsState(initial = emptySet())
    val logBufferLimit by repository.logBufferLimit.collectAsState(initial = LiveEventLogger.DEFAULT_MAX_LOGS)
    val discoveredAdv by BleGatewayService.discoveredAdvInstances.collectAsState()
    val sensorLastValues by BleGatewayService.sensorLastValues.collectAsState()

    LaunchedEffect(logBufferLimit) {
        LiveEventLogger.setMaxLogs(logBufferLimit)
    }

    var loadedConfig by remember { mutableStateOf<GatewayConfig?>(null) }
    var draftDevices by remember { mutableStateOf<List<DeviceConfig>>(emptyList()) }
    var configError by remember { mutableStateOf<String?>(null) }
    var isConfigLoading by remember { mutableStateOf(false) }
    var reloadTrigger by remember { mutableStateOf(0) }
    var usingCachedConfig by remember { mutableStateOf(false) }
    var showOnboarding by remember { mutableStateOf(false) }
    var showTemplateDialog by remember { mutableStateOf(false) }
    var showObdDialog by remember { mutableStateOf(false) }
    var showAdvWizard by remember { mutableStateOf(false) }
    var editingDevice by remember { mutableStateOf<DeviceConfig?>(null) }
    var showExportDialog by remember { mutableStateOf(false) }
    var missingPermCount by remember { mutableStateOf(0) }

    LaunchedEffect(Unit) {
        draftDevices = repository.loadDraftDevices()
    }

    val effectiveConfig = remember(loadedConfig, draftDevices, excludedDeviceIds) {
        ConfigMerger.effectiveConfig(loadedConfig, draftDevices, excludedDeviceIds)
    }
    val draftDeviceIds = remember(draftDevices, loadedConfig) {
        val remoteIds = loadedConfig?.devices?.map { it.id }?.toSet() ?: emptySet()
        draftDevices.map { it.id }.filterNot { it in remoteIds }.toSet()
    }

    val cacheSavedAtMs = remember(gitUrlInput, usingCachedConfig) {
        if (usingCachedConfig) loader.cacheSavedAt(gitUrlInput) else null
    }

    LaunchedEffect(onboardingComplete) {
        if (!onboardingComplete) showOnboarding = true
    }

    LaunchedEffect(gitUrlInput, gitTokenInput, templatesReloadTrigger) {
        isTemplatesLoading = true
        val loaded = templatesLoader.load(gitUrlInput, gitTokenInput.ifBlank { null })
        configTemplates = loaded.templates
        templatesSource = loaded.source
        isTemplatesLoading = false
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

    LaunchedEffect(effectiveConfig) {
        val config = effectiveConfig ?: return@LaunchedEffect
        repository.syncEnabledSensors(config)
    }

    val validationIssues = remember(effectiveConfig) {
        effectiveConfig?.let { ConfigValidator.validate(it) } ?: emptyList()
    }

    val effectiveEnabledSensors = remember(effectiveConfig, enabledSensors, enabledSensorsInitialized, validationIssues) {
        val errorKeys = validationIssues
            .filter { it.level == ValidationLevel.ERROR && it.sensorKey != null }
            .map { "${it.deviceId}/${it.sensorKey}" }
            .toSet()
        val configKeys = effectiveConfig?.allSensorKeys().orEmpty() - errorKeys
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
    var logIncludeAdv by remember { mutableStateOf(false) }
    var sensorDeviceSearch by remember { mutableStateOf("") }

    LaunchedEffect(selectedTab) {
        if (selectedTab != 2) {
            logIncludeAdv = false
            LiveEventLogger.setIncludeAdvLogs(false, purgeExisting = true)
        }
    }

    LaunchedEffect(selectedTab, logIncludeAdv) {
        LiveEventLogger.setIncludeAdvLogs(selectedTab == 2 && logIncludeAdv)
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
            val cacheText = cacheSavedAtMs?.let {
                stringResource(R.string.config_cache_banner_with_time, formatLastRefreshed(context, it))
            } ?: stringResource(R.string.config_cache_banner)
            InfoBanner(
                text = cacheText,
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
                    settingsLoaded = settingsLoaded,
                    connState = connState,
                    connectionIssue = connectionIssue,
                    gitRepoConfigured = gitRepoInput.isNotBlank(),
                    hasDraftDevices = draftDevices.isNotEmpty(),
                    enabledSensorCount = effectiveEnabledSensors.size,
                    onGoToSensorsTab = { selectedTab = 1 },
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
                        scope.launch {
                            repository.saveHaSettings(urlInput, tokenInput)
                            repository.saveGitSettings(gitUrlInput, gitTokenInput.ifBlank { null })
                            val refreshToken = repository.haRefreshToken.first()
                            BleGatewayService.start(
                                context,
                                urlInput,
                                tokenInput,
                                refreshToken,
                                gitUrlInput,
                                gitTokenInput.ifBlank { null },
                            )
                        }
                    },
                    onStopGateway = { BleGatewayService.stop(context) },
                )
                1 -> SensorsTabContent(
                    isRunning = isRunning,
                    isConfigLoading = isConfigLoading,
                    configError = configError,
                    loadedConfig = effectiveConfig,
                    validationIssues = validationIssues,
                    boundDevices = boundDevices,
                    enabledSensors = effectiveEnabledSensors,
                    autoConnectDisabledIds = autoConnectDisabledIds,
                    discoveredAdv = discoveredAdv,
                    sensorLastValues = sensorLastValues,
                    deviceLinkStatuses = deviceLinkStatuses,
                    gitRepoInput = gitRepoInput,
                    gitBranchInput = gitBranchInput,
                    gitTokenInput = gitTokenInput,
                    deviceSearchText = sensorDeviceSearch,
                    onDeviceSearchChange = { sensorDeviceSearch = it },
                    draftDeviceCount = draftDevices.size,
                    draftDeviceIds = draftDeviceIds,
                    cacheSavedAtMs = cacheSavedAtMs,
                    usingCachedConfig = usingCachedConfig,
                    onGitRepoChange = { gitRepoInput = it },
                    onGitBranchChange = { gitBranchInput = it },
                    onGitTokenChange = { gitTokenInput = it },
                    onReloadConfig = { reloadTrigger++ },
                    onImportTemplate = { showTemplateDialog = true },
                    onAddObd = { showObdDialog = true },
                    onAddAdv = { showAdvWizard = true },
                    onExportYaml = { showExportDialog = true },
                    onBindClick = { scanningDevice = it },
                    onUnbindClick = { deviceId -> scope.launch { repository.unbindDevice(deviceId) } },
                    onSensorToggle = { key, enabled ->
                        scope.launch { repository.setSensorEnabled(key, enabled) }
                    },
                    onSensorsBulkToggle = { keys, enabled ->
                        scope.launch { repository.setSensorsEnabled(keys, enabled) }
                    },
                    onDeleteDevice = { deviceId ->
                        scope.launch {
                            val isDraft = deviceId in draftDeviceIds
                            val device = draftDevices.firstOrNull { it.id == deviceId }
                                ?: effectiveConfig?.devices?.firstOrNull { it.id == deviceId }
                            val mode = haRemoveModeForDevice(device)
                            repository.deleteDevice(deviceId, isDraft = isDraft, haRemoveMode = mode)
                            draftDevices = repository.loadDraftDevices()
                            // 게이트웨이가 실행 중일 때만 서비스에 알린다.
                            // 중단 상태면 deleteDevice가 대기열에 넣은 HA 삭제가 다음 시작 시 처리된다.
                            if (isRunning) {
                                BleGatewayService.removeDevice(context, deviceId)
                                BleGatewayService.reloadConfig(context, gitUrlInput, gitTokenInput.ifBlank { null })
                            }
                        }
                    },
                    onEditDevice = { deviceId ->
                        editingDevice = draftDevices.firstOrNull { it.id == deviceId }
                    },
                    onSetAutoConnect = { deviceId, enabled -> BleGatewayService.setAutoConnect(context, deviceId, enabled) },
                    onConnectDevice = { deviceId -> BleGatewayService.connectDevice(context, deviceId) },
                    onDisconnectDevice = { deviceId -> BleGatewayService.disconnectDevice(context, deviceId) },
                )
                2 -> LogsTabContent(
                    isRunning = isRunning,
                    logBufferLimit = logBufferLimit,
                    logIncludeAdv = logIncludeAdv,
                    onLogBufferLimitChange = { limit ->
                        scope.launch { repository.saveLogBufferLimit(limit) }
                    },
                    onLogIncludeAdvChange = { enabled ->
                        logIncludeAdv = enabled
                        if (!enabled) {
                            LiveEventLogger.setIncludeAdvLogs(false, purgeExisting = true)
                        }
                    },
                    onFindMacInSensors = { mac ->
                        sensorDeviceSearch = mac
                        selectedTab = 1
                    },
                )
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
                icon = { Icon(Icons.AutoMirrored.Filled.List, contentDescription = stringResource(R.string.tab_logs)) },
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

    if (showTemplateDialog) {
        TemplateImportDialog(
            templates = configTemplates,
            isLoading = isTemplatesLoading,
            source = templatesSource,
            templatesUrl = gitRepoInput.takeIf { it.isNotBlank() }
                ?.let { GitHubHelper.buildTemplatesUrl(it.trim(), gitBranchInput) },
            onRefresh = { templatesReloadTrigger++ },
            onDismiss = { showTemplateDialog = false },
            onImport = { template ->
                scope.launch {
                    val result = runCatching {
                        val existingIds = (effectiveConfig?.devices?.map { it.id } ?: emptyList()).toSet()
                        val device = presets.expandDevice(template.device)
                        val unique = ConfigMerger.ensureUniqueId(device, existingIds)
                        repository.addDraftDevice(unique)
                        draftDevices = repository.loadDraftDevices()
                        unique
                    }
                    showTemplateDialog = false
                    result.onSuccess { unique ->
                        Toast.makeText(
                            context,
                            context.getString(R.string.config_imported_toast, unique.name),
                            Toast.LENGTH_SHORT,
                        ).show()
                        if (isRunning) BleGatewayService.reloadConfig(context, gitUrlInput, gitTokenInput.ifBlank { null })
                    }.onFailure { e ->
                        Toast.makeText(
                            context,
                            context.getString(R.string.config_import_failed, e.message ?: template.name),
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
            },
        )
    }

    if (showObdDialog) {
        AddObdDeviceDialog(
            presets = presets,
            onDismiss = { showObdDialog = false },
            onCreate = { device ->
                scope.launch {
                    val result = runCatching {
                        val existingIds = (effectiveConfig?.devices?.map { it.id } ?: emptyList()).toSet()
                        val expanded = presets.expandDevice(device)
                        val unique = ConfigMerger.ensureUniqueId(expanded, existingIds)
                        repository.addDraftDevice(unique)
                        draftDevices = repository.loadDraftDevices()
                        unique
                    }
                    showObdDialog = false
                    result.onSuccess { unique ->
                        Toast.makeText(
                            context,
                            context.getString(R.string.config_imported_toast, unique.name),
                            Toast.LENGTH_SHORT,
                        ).show()
                        if (isRunning) BleGatewayService.reloadConfig(context, gitUrlInput, gitTokenInput.ifBlank { null })
                    }.onFailure { e ->
                        Toast.makeText(
                            context,
                            context.getString(R.string.config_import_failed, e.message ?: device.name),
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
            },
        )
    }

    if (showExportDialog) {
        ExportYamlDialog(
            mergedConfig = effectiveConfig,
            draftDevices = draftDevices,
            gitRepoInput = gitRepoInput,
            onDismiss = { showExportDialog = false },
        )
    }

    if (showAdvWizard) {
        AdvertisementWizardDialog(
            onDismiss = { showAdvWizard = false },
            onCreate = { device ->
                scope.launch {
                    val existingIds = (effectiveConfig?.devices?.map { it.id } ?: emptyList()).toSet()
                    val unique = ConfigMerger.ensureUniqueId(device, existingIds)
                    repository.addDraftDevice(unique)
                    draftDevices = repository.loadDraftDevices()
                    showAdvWizard = false
                    Toast.makeText(
                        context,
                        context.getString(R.string.config_imported_toast, unique.name),
                        Toast.LENGTH_SHORT,
                    ).show()
                    if (isRunning) BleGatewayService.reloadConfig(context, gitUrlInput, gitTokenInput.ifBlank { null })
                }
            },
        )
    }

    editingDevice?.let { dev ->
        DeviceEditDialog(
            device = dev,
            presets = presets,
            onDismiss = { editingDevice = null },
            onSave = { updated ->
                scope.launch {
                    val expanded = if (updated.source == Source.obd) presets.expandDevice(updated) else updated
                    repository.updateDraftDevice(expanded)
                    draftDevices = repository.loadDraftDevices()
                    editingDevice = null
                    Toast.makeText(
                        context,
                        context.getString(R.string.config_imported_toast, expanded.name),
                        Toast.LENGTH_SHORT,
                    ).show()
                    if (isRunning) BleGatewayService.reloadConfig(context, gitUrlInput, gitTokenInput.ifBlank { null })
                }
            },
        )
    }
}



private enum class GatewayReadinessItem {
    HaUrl,
    HaAuth,
    GitRepo,
    EnabledSensors,
}

private data class GatewayReadiness(
    val missingBlocking: List<GatewayReadinessItem>,
    val missingAdvisory: List<GatewayReadinessItem>,
) {
    val hasAnyMissing: Boolean
        get() = missingBlocking.isNotEmpty() || missingAdvisory.isNotEmpty()

    val canStart: Boolean
        get() = missingBlocking.isEmpty()
}

private fun isHaUrlReady(url: String): Boolean =
    url.isNotBlank() && url != "https://" && url != "http://"

private fun computeGatewayReadiness(
    urlInput: String,
    tokenInput: String,
    haRefreshToken: String,
    gitRepoConfigured: Boolean,
    hasDraftDevices: Boolean,
    enabledSensorCount: Int,
): GatewayReadiness {
    val blocking = buildList {
        if (!isHaUrlReady(urlInput)) add(GatewayReadinessItem.HaUrl)
        if (tokenInput.isBlank() && haRefreshToken.isBlank()) add(GatewayReadinessItem.HaAuth)
        if (!gitRepoConfigured && !hasDraftDevices) add(GatewayReadinessItem.GitRepo)
    }
    val advisory = buildList {
        if (enabledSensorCount == 0) add(GatewayReadinessItem.EnabledSensors)
        if (!gitRepoConfigured && hasDraftDevices) add(GatewayReadinessItem.GitRepo)
    }
    return GatewayReadiness(blocking, advisory)
}

@Composable
private fun GatewayReadinessBanner(
    readiness: GatewayReadiness,
    onGoToSensorsTab: () -> Unit,
) {
    val missingItems = readiness.missingBlocking + readiness.missingAdvisory
    val itemLabels = missingItems.map { item ->
        when (item) {
            GatewayReadinessItem.HaUrl -> stringResource(R.string.gateway_readiness_item_ha_url)
            GatewayReadinessItem.HaAuth -> stringResource(R.string.gateway_readiness_item_ha_auth)
            GatewayReadinessItem.GitRepo -> stringResource(R.string.gateway_readiness_item_git)
            GatewayReadinessItem.EnabledSensors -> stringResource(R.string.gateway_readiness_item_sensors)
        }
    }
    val showSensorsAction = GatewayReadinessItem.GitRepo in missingItems ||
        GatewayReadinessItem.EnabledSensors in missingItems
    val isBlocking = readiness.missingBlocking.isNotEmpty()
    val accentColor = if (isBlocking) {
        MaterialTheme.colorScheme.error
    } else {
        Color(0xFFFF9100)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = accentColor.copy(alpha = 0.1f),
        ),
        border = BorderStroke(1.dp, accentColor.copy(alpha = 0.3f)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = accentColor,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.gateway_readiness_prefix),
                color = accentColor,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = itemLabels.joinToString(" · "),
                color = if (isBlocking) accentColor else Color.White,
                fontSize = 12.sp,
                modifier = Modifier.weight(1f),
            )
            if (showSensorsAction) {
                HassLinkButton(
                    text = stringResource(R.string.gateway_go_to_sensors),
                    onClick = onGoToSensorsTab,
                )
            }
        }
    }
}

@Composable
private fun GatewayTabContent(
    isRunning: Boolean,
    settingsLoaded: Boolean = true,
    connState: ConnectionState,
    connectionIssue: ConnectionIssue,
    gitRepoConfigured: Boolean,
    hasDraftDevices: Boolean,
    enabledSensorCount: Int,
    onGoToSensorsTab: () -> Unit,
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

    if (showClearOAuthDialog) {
        Dialog(onDismissRequest = { showClearOAuthDialog = false }) {
            Card(shape = HassBleShapes.Dialog, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(stringResource(R.string.oauth_clear_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(stringResource(R.string.oauth_clear_body), color = Color.Gray, fontSize = 13.sp)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        HassCancelButton(onClick = { showClearOAuthDialog = false })
                        Spacer(Modifier.width(8.dp))
                        HassDangerButton(
                            text = stringResource(R.string.oauth_clear_confirm),
                            onClick = { showClearOAuthDialog = false; onClearOAuth() },
                        )
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
                    HassLinkButton(
                        text = stringResource(R.string.show_onboarding_again),
                        onClick = onShowOnboarding,
                    )
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
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = stringResource(R.string.oauth_active_chip), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            Text(text = stringResource(R.string.oauth_last_refreshed, formatLastRefreshed(context, haTokenLastRefreshed)), color = Color.Gray, fontSize = 10.sp)
                        }
                        HassLinkButton(
                            text = stringResource(R.string.oauth_logout_btn),
                            onClick = { showClearOAuthDialog = true },
                            enabled = inputsEnabled,
                        )
                    }
                } else if (tokenInput.isNotBlank()) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color.White.copy(alpha = 0.06f), RoundedCornerShape(8.dp))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            tint = Color.Gray,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.oauth_manual_active_chip),
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
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
                HassPrimaryButton(
                    text = stringResource(R.string.oauth_login_btn),
                    onClick = {
                        if (urlInput.isBlank() || urlInput == "https://" || urlInput == "http://") {
                            Toast.makeText(context, context.getString(R.string.ha_url_required_toast), Toast.LENGTH_SHORT).show()
                        } else {
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
                        }
                    },
                    enabled = inputsEnabled && urlInput.isNotBlank() && urlInput != "https://" && urlInput != "http://",
                    modifier = Modifier.fillMaxWidth(),
                    large = true,
                )
                if (haRefreshToken.isBlank()) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.auth_manual_token_hint),
                        color = Color.Gray,
                        fontSize = 11.sp,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = tokenInput,
                        onValueChange = onTokenChange,
                        label = { Text(stringResource(R.string.long_lived_token)) },
                        enabled = inputsEnabled,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                    )
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
                        HassAccentButton(
                            text = stringResource(R.string.test_connection_btn),
                            onClick = {
                                if (urlInput.isBlank() || tokenInput.isBlank()) {
                                    Toast.makeText(context, context.getString(R.string.fill_all_fields_toast), Toast.LENGTH_SHORT).show()
                                } else {
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
                                }
                            },
                            enabled = !isTestingConnection,
                            loading = isTestingConnection,
                        )
                    }
                    HassSecondaryButton(
                        text = stringResource(R.string.open_ws_bridge_repo),
                        onClick = {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(context.getString(R.string.ws_bridge_url))))
                        },
                        compact = true,
                    )
                }
            }
        }

        val readiness = computeGatewayReadiness(
            urlInput = urlInput,
            tokenInput = tokenInput,
            haRefreshToken = haRefreshToken,
            gitRepoConfigured = gitRepoConfigured,
            hasDraftDevices = hasDraftDevices,
            enabledSensorCount = enabledSensorCount,
        )
        if (settingsLoaded && !isRunning && readiness.hasAnyMissing) {
            GatewayReadinessBanner(
                readiness = readiness,
                onGoToSensorsTab = onGoToSensorsTab,
            )
        }

        GatewayControlButton(
            isRunning = isRunning,
            startEnabled = !settingsLoaded || readiness.canStart,
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
                            HassToggleChip(
                                label = stringResource(btnTextId),
                                selected = selected,
                                onClick = { onScanModeChange(mode) },
                            )
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
                        HassPrimaryButton(
                            text = stringResource(R.string.request_battery_ignore_btn),
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
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = stringResource(R.string.hide_notif_icon_btn), fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = Color.White)
                        Text(text = stringResource(R.string.hide_notif_icon_desc), fontSize = 11.sp, color = Color.Gray)
                    }
                    HassSecondaryButton(
                        text = stringResource(R.string.go_to_settings_btn),
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
                        modifier = Modifier.padding(start = 8.dp),
                        compact = true,
                    )
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
    validationIssues: List<ValidationIssue> = emptyList(),
    boundDevices: Map<String, String>,
    enabledSensors: Set<String>,
    autoConnectDisabledIds: Set<String>,
    discoveredAdv: List<DiscoveredAdvInstance>,
    sensorLastValues: List<SensorLastValue>,
    deviceLinkStatuses: List<DeviceLinkStatus>,
    gitRepoInput: String,
    gitBranchInput: String,
    gitTokenInput: String,
    deviceSearchText: String = "",
    onDeviceSearchChange: (String) -> Unit = {},
    draftDeviceCount: Int = 0,
    draftDeviceIds: Set<String> = emptySet(),
    cacheSavedAtMs: Long? = null,
    usingCachedConfig: Boolean = false,
    onGitRepoChange: (String) -> Unit,
    onGitBranchChange: (String) -> Unit,
    onGitTokenChange: (String) -> Unit,
    onReloadConfig: () -> Unit,
    onImportTemplate: () -> Unit,
    onAddObd: () -> Unit,
    onAddAdv: () -> Unit,
    onExportYaml: () -> Unit,
    onBindClick: (DeviceConfig) -> Unit,
    onUnbindClick: (String) -> Unit,
    onSensorToggle: (String, Boolean) -> Unit,
    onSensorsBulkToggle: (Set<String>, Boolean) -> Unit,
    onDeleteDevice: (String) -> Unit,
    onEditDevice: (String) -> Unit = {},
    onSetAutoConnect: (String, Boolean) -> Unit,
    onConnectDevice: (String) -> Unit = {},
    onDisconnectDevice: (String) -> Unit = {},
) {
    val filteredDevices = remember(loadedConfig, boundDevices, discoveredAdv, deviceSearchText) {
        val devices = loadedConfig?.devices ?: emptyList()
        val query = deviceSearchText.trim()
        if (query.isBlank()) {
            devices
        } else {
            val macsByProfile = discoveredAdv.groupBy({ it.profileId }, { it.mac })
            devices.filter { device ->
                deviceMatchesSearch(
                    device = device,
                    boundMac = boundDevices[device.id],
                    discoveredMacs = macsByProfile[device.id].orEmpty(),
                    query = query,
                )
            }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            GitConfigSection(
                repoInput = gitRepoInput,
                branchInput = gitBranchInput,
                tokenInput = gitTokenInput,
                onRepoChange = onGitRepoChange,
                onBranchChange = onGitBranchChange,
                onTokenChange = onGitTokenChange,
            )
        }
        item {
            ConfigToolsRow(
                onImportTemplate = onImportTemplate,
                onAddObd = onAddObd,
                onAddAdv = onAddAdv,
                onExportYaml = onExportYaml,
                draftDeviceCount = draftDeviceCount,
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
                HassAccentButton(
                    text = stringResource(R.string.reload_config_btn),
                    onClick = onReloadConfig,
                )
            }
        }
        if (loadedConfig != null && loadedConfig.devices.isNotEmpty()) {
            item {
                OutlinedTextField(
                    value = deviceSearchText,
                    onValueChange = onDeviceSearchChange,
                    placeholder = {
                        Text(
                            stringResource(R.string.sensors_device_search_placeholder),
                            color = Color.Gray,
                            fontSize = 12.sp,
                        )
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = null,
                            tint = Color.Gray,
                            modifier = Modifier.size(18.dp),
                        )
                    },
                    trailingIcon = {
                        if (deviceSearchText.isNotEmpty()) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = null,
                                tint = Color.Gray,
                                modifier = Modifier
                                    .size(18.dp)
                                    .clickable { onDeviceSearchChange("") },
                            )
                        }
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, color = Color.White),
                    colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                        unfocusedBorderColor = Color.White.copy(alpha = 0.12f),
                    ),
                )
            }
        }
        if (isRunning) {
            item {
                var runningHintsExpanded by remember { mutableStateOf(false) }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color.White.copy(alpha = 0.04f))
                        .clickable { runningHintsExpanded = !runningHintsExpanded }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = stringResource(R.string.sensors_running_hints_title),
                            color = Color.Gray,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Icon(
                            imageVector = if (runningHintsExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                            contentDescription = null,
                            tint = Color.Gray,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    AnimatedVisibility(
                        visible = runningHintsExpanded,
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut(),
                    ) {
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
            }
        }

        if (usingCachedConfig) {
            item {
                val ctx = LocalContext.current
                val cacheText = cacheSavedAtMs?.let {
                    stringResource(R.string.config_cache_banner_with_time, formatLastRefreshed(ctx, it))
                } ?: stringResource(R.string.config_cache_banner)
                WarningBanner(text = cacheText)
            }
        }

        if (configError != null && loadedConfig != null && !usingCachedConfig) {
            item { WarningBanner(text = configError) }
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
                        HassDangerButton(
                            text = stringResource(R.string.retry_btn),
                            onClick = onReloadConfig,
                            modifier = Modifier.align(Alignment.End),
                        )
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
        } else if (filteredDevices.isEmpty()) {
            item {
                Text(
                    text = stringResource(R.string.sensors_device_filter_empty),
                    color = Color.Gray,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
            }
        } else {
            items(filteredDevices, key = { it.id }) { device ->
                DeviceConfigCard(
                    device = device,
                    boundMac = boundDevices[device.id],
                    enabledSensors = enabledSensors,
                    validationIssues = validationIssues.filter { it.deviceId == device.id },
                    isRunning = isRunning,
                    isDraftDevice = device.id in draftDeviceIds,
                    autoConnect = device.id !in autoConnectDisabledIds,
                    discoveredInstances = discoveredAdv.filter { it.profileId == device.id },
                    sensorLastValues = sensorLastValues.filter { it.profileId == device.id },
                    linkStatus = deviceLinkStatuses.firstOrNull { it.profileId == device.id },
                    onSensorToggle = onSensorToggle,
                    onSensorsBulkToggle = onSensorsBulkToggle,
                    onBindClick = { onBindClick(device) },
                    onUnbindClick = { onUnbindClick(device.id) },
                    onDelete = { onDeleteDevice(device.id) },
                    onEdit = { onEditDevice(device.id) },
                    onSetAutoConnect = { enabled -> onSetAutoConnect(device.id, enabled) },
                    onConnectDevice = { onConnectDevice(device.id) },
                    onDisconnectDevice = { onDisconnectDevice(device.id) },
                )
            }
        }

        item { Spacer(modifier = Modifier.height(4.dp)) }
    }
}

@Composable
private fun GatewayControlButton(
    isRunning: Boolean,
    startEnabled: Boolean = true,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    val buttonBrush = if (isRunning) {
        Brush.horizontalGradient(listOf(Color(0xFFFF5252), Color(0xFFFF1744)))
    } else {
        Brush.horizontalGradient(listOf(Color(0xFF9E86FF), Color(0xFF651FFF)))
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(HassBleShapes.Pill)
            .background(buttonBrush)
            .alpha(if (!isRunning && !startEnabled) 0.45f else 1f),
    ) {
        Button(
            onClick = { if (isRunning) onStop() else onStart() },
            modifier = Modifier.fillMaxSize(),
            enabled = isRunning || startEnabled,
            colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
            shape = HassBleShapes.Pill,
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
    validationIssues: List<ValidationIssue> = emptyList(),
    isRunning: Boolean,
    isDraftDevice: Boolean = false,
    autoConnect: Boolean,
    discoveredInstances: List<DiscoveredAdvInstance>,
    sensorLastValues: List<SensorLastValue>,
    linkStatus: DeviceLinkStatus?,
    onSensorToggle: (String, Boolean) -> Unit,
    onSensorsBulkToggle: (Set<String>, Boolean) -> Unit,
    onBindClick: () -> Unit,
    onUnbindClick: () -> Unit,
    onDelete: () -> Unit,
    onEdit: () -> Unit = {},
    onSetAutoConnect: (Boolean) -> Unit,
    onConnectDevice: () -> Unit = {},
    onDisconnectDevice: () -> Unit = {},
) {
    val deviceSensorKeys = remember(device.id, device.sensors) {
        device.sensors.map { "${device.id}/${it.key}" }
    }
    val enabledCount = deviceSensorKeys.count { it in enabledSensors }
    val errorSensorCount = remember(validationIssues) {
        validationIssues.count { it.level == ValidationLevel.ERROR && it.sensorKey != null }
    }
    val warnSensorCount = remember(validationIssues) {
        validationIssues.count { it.level == ValidationLevel.WARNING && it.sensorKey != null }
    }
    var expanded by remember(device.id) { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
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
                            color = Color.White,
                        )
                        if (isDraftDevice) {
                            Text(
                                text = stringResource(R.string.config_draft_device_badge),
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
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
                        if (errorSensorCount > 0 || warnSensorCount > 0) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                modifier = Modifier.padding(top = 4.dp),
                            ) {
                                if (errorSensorCount > 0) {
                                    Text(
                                        text = "$errorSensorCount error",
                                        fontSize = 10.sp,
                                        color = MaterialTheme.colorScheme.error,
                                        modifier = Modifier
                                            .background(MaterialTheme.colorScheme.error.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                                            .padding(horizontal = 6.dp, vertical = 2.dp),
                                    )
                                }
                                if (warnSensorCount > 0) {
                                    Text(
                                        text = "$warnSensorCount warning",
                                        fontSize = 10.sp,
                                        color = Color(0xFFFFB300),
                                        modifier = Modifier
                                            .background(Color(0xFFFFB300).copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                                            .padding(horizontal = 6.dp, vertical = 2.dp),
                                    )
                                }
                            }
                        }
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
                                    HassLinkButton(
                                        text = stringResource(R.string.sensors_enable_all),
                                        onClick = { onSensorsBulkToggle(deviceSensorKeys.toSet(), true) },
                                    )
                                    HassLinkButton(
                                        text = stringResource(R.string.sensors_disable_all),
                                        onClick = { onSensorsBulkToggle(deviceSensorKeys.toSet(), false) },
                                    )
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
                            val sensorIssues = validationIssues.filter { it.sensorKey == sensor.key }
                            val hasError = sensorIssues.any { it.level == ValidationLevel.ERROR }
                            Column(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = sensorDisplayLabel(sensor),
                                            color = when {
                                                hasError -> MaterialTheme.colorScheme.error
                                                checked -> Color.White
                                                else -> Color.Gray
                                            },
                                            fontSize = 12.sp,
                                        )
                                        sensorIssues.forEach { issue ->
                                            val isErr = issue.level == ValidationLevel.ERROR
                                            val pathHint = if (issue.yamlPath.isNotBlank()) " (${issue.yamlPath})" else ""
                                            Text(
                                                text = "${if (isErr) "⚠ ERROR" else "⚠ WARN"}: ${issue.message}$pathHint",
                                                color = if (isErr) MaterialTheme.colorScheme.error
                                                        else Color(0xFFFFB300),
                                                fontSize = 10.sp,
                                                modifier = Modifier
                                                    .padding(top = 2.dp)
                                                    .background(
                                                        color = if (isErr) MaterialTheme.colorScheme.error.copy(alpha = 0.12f)
                                                                else Color(0xFFFFB300).copy(alpha = 0.12f),
                                                        shape = RoundedCornerShape(4.dp),
                                                    )
                                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                                            )
                                        }
                                    }
                                    Switch(
                                        checked = checked && !hasError,
                                        onCheckedChange = { if (!hasError) onSensorToggle(sensorKey, it) },
                                        enabled = !isRunning && !hasError,
                                        colors = SwitchDefaults.colors(
                                            checkedThumbColor = Color.Black,
                                            checkedTrackColor = if (hasError) MaterialTheme.colorScheme.error
                                                                else MaterialTheme.colorScheme.primary,
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
                    val isActive = isConnected ||
                            linkStatus?.state == DeviceLinkState.Connecting ||
                            linkStatus?.state == DeviceLinkState.Scanning

                    if (isRunning && (device.source == Source.gatt_notify || device.source == Source.obd)) {
                        val hasMac = if (device.source == Source.gatt_notify) !device.gatt?.mac.isNullOrBlank() else !device.obd?.mac.isNullOrBlank()
                        val hasBoundMac = !boundMac.isNullOrBlank()
                        if (hasMac || hasBoundMac) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (isActive) {
                                    HassDangerOutlinedButton(
                                        text = stringResource(R.string.device_disconnect_btn),
                                        onClick = onDisconnectDevice,
                                    )
                                } else {
                                    HassAccentButton(
                                        text = stringResource(R.string.device_connect_btn),
                                        onClick = onConnectDevice,
                                    )
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(stringResource(R.string.auto_connect_label), fontSize = 12.sp, color = Color.Gray)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Switch(
                                        checked = autoConnect,
                                        onCheckedChange = { onSetAutoConnect(it) },
                                        colors = SwitchDefaults.colors(
                                            checkedThumbColor = Color.Black,
                                            checkedTrackColor = MaterialTheme.colorScheme.primary,
                                        ),
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (isDraftDevice) {
                            HassSecondaryButton(
                                text = stringResource(R.string.device_edit_btn),
                                onClick = onEdit,
                            )
                        }
                        HassDangerTintButton(
                            text = stringResource(R.string.device_delete_btn),
                            onClick = onDelete,
                            enabled = !isConnected,
                        )
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
    val sortedDevices = remember(devicesList.toList()) {
        devicesList.sortedByDescending { it.rssi }
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.96f)
                .fillMaxHeight(0.92f),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Column(modifier = Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = stringResource(R.string.search_bluetooth_device), fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Color.White)
                        Text(text = stringResource(R.string.find_target_for, deviceConfig.name), fontSize = 12.sp, color = Color.Gray)
                    }
                    if (isScanning) CircularProgressIndicator(modifier = Modifier.size(20.dp), color = MaterialTheme.colorScheme.primary, strokeWidth = 2.dp)
                }
                if (targetServiceUuid != null) {
                    Row(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color.Black.copy(alpha = 0.15f)).padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = stringResource(R.string.service_match_filter), fontSize = 13.sp, color = Color.White, fontWeight = FontWeight.SemiBold)
                            Text(text = "UUID: ${targetServiceUuid.take(8)}...", fontSize = 11.sp, color = Color.Gray)
                        }
                        Switch(checked = applyFilter, onCheckedChange = { applyFilter = it }, colors = SwitchDefaults.colors(checkedThumbColor = Color.Black, checkedTrackColor = MaterialTheme.colorScheme.primary))
                    }
                }
                Box(modifier = Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color.Black.copy(alpha = 0.2f)).padding(8.dp)) {
                    if (sortedDevices.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(text = stringResource(R.string.no_devices_found), color = Color.Gray, fontSize = 14.sp) }
                    } else {
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(sortedDevices, key = { it.address }) { scanned ->
                                Row(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { onDeviceSelected(scanned.address) }.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                    Column(modifier = Modifier.weight(1f)) {
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
                    HassCancelButton(onClick = onDismiss)
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LogsTabContent(
    isRunning: Boolean,
    logBufferLimit: Int,
    logIncludeAdv: Boolean,
    onLogBufferLimitChange: (Int) -> Unit,
    onLogIncludeAdvChange: (Boolean) -> Unit,
    onFindMacInSensors: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val logsList = remember {
        mutableStateListOf<LogEntry>().apply {
            addAll(LiveEventLogger.logs)
        }
    }
    var filterText by remember { mutableStateOf("") }
    var settingsExpanded by remember { mutableStateOf(false) }
    var followLatest by remember { mutableStateOf(true) }
    var disabledTypes by remember { mutableStateOf(setOf<LogType>()) }

    LaunchedEffect(logBufferLimit) {
        LiveEventLogger.setMaxLogs(logBufferLimit)
        while (logsList.size > logBufferLimit) {
            logsList.removeAt(0)
        }
    }

    LaunchedEffect(logIncludeAdv) {
        if (!logIncludeAdv) {
            logsList.removeAll { it.type == LogType.ADV }
        }
    }

    LaunchedEffect(Unit) {
        LiveEventLogger.logFlow.collect { logLine ->
            while (logsList.size >= LiveEventLogger.maxLogs) {
                logsList.removeAt(0)
            }
            logsList.add(logLine)
        }
    }

    val filteredLogs by remember {
        derivedStateOf {
            val query = filterText.trim()
            logsList.filter { entry ->
                entry.type !in disabledTypes &&
                    (query.isBlank() ||
                        entry.message.contains(query, ignoreCase = true) ||
                        entry.timestamp.contains(query, ignoreCase = true) ||
                        entry.type.name.contains(query, ignoreCase = true))
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(R.string.logs_summary, logsList.size, logBufferLimit),
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
                color = Color.White,
                modifier = Modifier.weight(1f),
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = stringResource(R.string.logs_include_adv),
                    fontSize = 11.sp,
                    color = Color.Gray,
                )
                Switch(
                    checked = logIncludeAdv,
                    onCheckedChange = onLogIncludeAdvChange,
                    modifier = Modifier.height(28.dp),
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.Black,
                        checkedTrackColor = MaterialTheme.colorScheme.primary,
                    ),
                )
                IconButton(
                    onClick = { settingsExpanded = !settingsExpanded },
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        imageVector = if (settingsExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.Settings,
                        contentDescription = stringResource(R.string.logs_settings),
                        tint = if (settingsExpanded) MaterialTheme.colorScheme.primary else Color.Gray,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
        Text(
            text = stringResource(R.string.logs_include_adv_hint),
            fontSize = 10.sp,
            color = Color.Gray,
            modifier = Modifier.padding(start = 2.dp),
        )

        AnimatedVisibility(
            visible = settingsExpanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.logs_buffer_limit),
                            fontSize = 11.sp,
                            color = Color.Gray,
                        )
                        LiveEventLogger.BUFFER_LIMIT_OPTIONS.forEach { limit ->
                            val selected = logBufferLimit == limit
                            TextButton(
                                onClick = { onLogBufferLimitChange(limit) },
                                contentPadding = HassBleButtonDefaults.compactPadding,
                                colors = HassBleButtonDefaults.linkTextColors(),
                            ) {
                                Text(
                                    limit.toString(),
                                    fontSize = 11.sp,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (selected) MaterialTheme.colorScheme.primary else Color.Gray,
                                )
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        HassLinkButton(
                            text = stringResource(R.string.logs_save),
                            onClick = { saveLogsToDownloads(context, filteredLogs) },
                            enabled = filteredLogs.isNotEmpty(),
                        )
                        HassLinkButton(
                            text = stringResource(R.string.logs_share),
                            onClick = { shareLogs(context, filteredLogs) },
                            enabled = filteredLogs.isNotEmpty(),
                        )
                        HassLinkButton(
                            text = stringResource(R.string.logs_clear),
                            onClick = {
                                logsList.clear()
                                LiveEventLogger.clearLogs()
                            },
                        )
                    }
                }
            }
        }

        Card(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.4f)),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                OutlinedTextField(
                    value = filterText,
                    onValueChange = { filterText = it },
                    placeholder = {
                        Text(
                            stringResource(R.string.logs_filter_placeholder),
                            color = Color.Gray,
                            fontSize = 12.sp,
                        )
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = "Search",
                            tint = Color.Gray,
                            modifier = Modifier.size(16.dp),
                        )
                    },
                    trailingIcon = {
                        if (filterText.isNotEmpty()) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Clear",
                                tint = Color.Gray,
                                modifier = Modifier
                                    .size(16.dp)
                                    .clickable { filterText = "" },
                            )
                        }
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp, bottomStart = 0.dp, bottomEnd = 0.dp),
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp, color = Color.White),
                    colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent,
                        cursorColor = MaterialTheme.colorScheme.primary,
                    ),
                )
                HorizontalDivider(color = Color.White.copy(alpha = 0.08f))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 8.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FlowRow(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        LogType.entries.forEach { type ->
                            LogTypeFilterChip(
                                type = type,
                                selected = type !in disabledTypes,
                                onClick = {
                                    disabledTypes = if (type in disabledTypes) {
                                        disabledTypes - type
                                    } else {
                                        disabledTypes + type
                                    }
                                },
                            )
                        }
                    }
                    HassCopyIconButton(
                        onClick = { copyTextToClipboard(context, formatLogs(filteredLogs), "logs") },
                        enabled = filteredLogs.isNotEmpty(),
                        contentDescription = stringResource(R.string.logs_copy_all),
                    )
                }
                HorizontalDivider(color = Color.White.copy(alpha = 0.08f))

                val listState = rememberLazyListState()
                val isDragged by listState.interactionSource.collectIsDraggedAsState()
                val atBottom by remember {
                    derivedStateOf {
                        val info = listState.layoutInfo
                        if (info.totalItemsCount == 0) true
                        else {
                            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: 0
                            lastVisible >= info.totalItemsCount - 2
                        }
                    }
                }

                LaunchedEffect(atBottom, isDragged) {
                    if (isDragged) {
                        if (!atBottom && followLatest) {
                            followLatest = false
                        }
                    } else if (atBottom && !followLatest) {
                        followLatest = true
                    }
                }

                LaunchedEffect(filteredLogs.size, followLatest) {
                    if (followLatest && filteredLogs.isNotEmpty()) {
                        listState.animateScrollToItem(filteredLogs.lastIndex)
                    }
                }

                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    if (filteredLogs.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                text = when {
                                    logsList.isEmpty() && !isRunning ->
                                        stringResource(R.string.logs_empty_gateway_stopped)
                                    logsList.isEmpty() ->
                                        stringResource(R.string.logs_empty_hint)
                                    else ->
                                        stringResource(R.string.logs_filter_empty)
                                },
                                color = Color.Gray,
                                fontSize = 13.sp,
                                modifier = Modifier.padding(horizontal = 16.dp),
                            )
                        }
                    } else {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            items(filteredLogs, key = { it.id }) { logEntry ->
                                LogEntryRow(
                                    logEntry = logEntry,
                                    onCopyLine = {
                                        copyTextToClipboard(context, formatLogLine(logEntry), "log")
                                    },
                                    onFilterByMac = { mac ->
                                        filterText = mac
                                        Toast.makeText(
                                            context,
                                            context.getString(R.string.logs_filter_mac_applied, mac),
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                    },
                                    onFindMacInSensors = { mac ->
                                        onFindMacInSensors(mac)
                                        Toast.makeText(
                                            context,
                                            context.getString(R.string.logs_find_mac_in_sensors),
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                    },
                                )
                            }
                        }
                    }

                    if (!followLatest && filteredLogs.isNotEmpty()) {
                        HassPrimaryButton(
                            text = stringResource(R.string.logs_scroll_to_latest),
                            onClick = { followLatest = true },
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 8.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LogTypeFilterChip(
    type: LogType,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val label = when (type) {
        LogType.ADV -> "ADV"
        LogType.TX -> "TX"
        LogType.RX -> "RX"
        LogType.NOTIF -> "NOTIF"
        LogType.LINK -> "LINK"
    }
    val color = when (type) {
        LogType.ADV -> Color(0xFFFFCC80)
        LogType.TX -> Color(0xFFC5E1A5)
        LogType.RX -> Color(0xFF90CAF9)
        LogType.NOTIF -> Color(0xFFB39DDB)
        LogType.LINK -> Color(0xFFEEEEEE)
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (selected) color.copy(alpha = 0.2f) else Color.White.copy(alpha = 0.06f))
            .border(
                0.5.dp,
                if (selected) color.copy(alpha = 0.5f) else Color.White.copy(alpha = 0.1f),
                RoundedCornerShape(6.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(
            text = label,
            color = if (selected) color else Color.Gray,
            fontSize = 10.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
        )
    }
}

@Composable
private fun LogEntryRow(
    logEntry: LogEntry,
    onCopyLine: () -> Unit,
    onFilterByMac: (String) -> Unit,
    onFindMacInSensors: (String) -> Unit,
) {
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
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = logEntry.timestamp,
            color = Color.Gray,
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            fontSize = 10.sp,
            modifier = Modifier.padding(end = 6.dp, top = 2.dp),
        )
        Box(
            modifier = Modifier
                .padding(end = 6.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(badgeColor.copy(alpha = 0.15f))
                .border(0.5.dp, badgeColor.copy(alpha = 0.3f), RoundedCornerShape(4.dp))
                .padding(horizontal = 4.dp, vertical = 2.dp),
        ) {
            Text(
                text = badgeText,
                color = badgeColor,
                fontWeight = FontWeight.Bold,
                fontSize = 9.sp,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            )
        }
        LogEntryMessage(
            message = logEntry.message,
            modifier = Modifier.weight(1f),
            onCopyLine = onCopyLine,
            onMacClick = onFilterByMac,
            onMacLongClick = onFindMacInSensors,
        )
    }
}

private sealed interface LogMessageSegment {
    data class Plain(val text: String) : LogMessageSegment
    data class Mac(val value: String) : LogMessageSegment
}

private val LOG_MAC_PATTERN = Regex("""(?i)([0-9A-F]{2}(:[0-9A-F]{2}){5})""")

private fun splitLogMessageSegments(message: String): List<LogMessageSegment> {
    val segments = mutableListOf<LogMessageSegment>()
    var lastIndex = 0
    LOG_MAC_PATTERN.findAll(message).forEach { match ->
        if (match.range.first > lastIndex) {
            segments.add(LogMessageSegment.Plain(message.substring(lastIndex, match.range.first)))
        }
        segments.add(LogMessageSegment.Mac(match.value))
        lastIndex = match.range.last + 1
    }
    if (lastIndex < message.length) {
        segments.add(LogMessageSegment.Plain(message.substring(lastIndex)))
    }
    return segments
}

private fun deviceMatchesSearch(
    device: DeviceConfig,
    boundMac: String?,
    discoveredMacs: List<String>,
    query: String,
): Boolean {
    val q = query.trim()
    if (q.isBlank()) return true
    if (device.name.contains(q, ignoreCase = true)) return true
    if (device.id.contains(q, ignoreCase = true)) return true
    boundMac?.let { if (it.contains(q, ignoreCase = true)) return true }
    if (discoveredMacs.any { it.contains(q, ignoreCase = true) }) return true
    // 소스 토큰은 2자 이상 검색어가 토큰의 접두사일 때만 매칭(짧은/무관 검색어 과민 매칭 방지).
    if (q.length >= 2) {
        val sourceTokens = when (device.source) {
            Source.advertisement -> listOf("advertisement", "adv", "passive", "scan")
            Source.gatt_notify -> listOf("gatt", "notify", "gatt_notify", "connection")
            Source.obd -> listOf("obd", "polling")
        }
        if (sourceTokens.any { it.startsWith(q, ignoreCase = true) }) return true
    }
    return false
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
private fun LogEntryMessage(
    message: String,
    modifier: Modifier = Modifier,
    onCopyLine: () -> Unit,
    onMacClick: (String) -> Unit,
    onMacLongClick: (String) -> Unit,
) {
    val segments = remember(message) { splitLogMessageSegments(message) }
    if (segments.none { it is LogMessageSegment.Mac }) {
        Text(
            text = message,
            color = Color.LightGray,
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            fontSize = 11.sp,
            lineHeight = 14.sp,
            modifier = modifier.clickable(onClick = onCopyLine),
        )
        return
    }
    FlowRow(modifier = modifier) {
        segments.forEach { segment ->
            when (segment) {
                is LogMessageSegment.Plain -> {
                    if (segment.text.isNotEmpty()) {
                        Text(
                            text = segment.text,
                            color = Color.LightGray,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            fontSize = 11.sp,
                            lineHeight = 14.sp,
                            modifier = Modifier.clickable(onClick = onCopyLine),
                        )
                    }
                }
                is LogMessageSegment.Mac -> {
                    Text(
                        text = segment.value,
                        color = MaterialTheme.colorScheme.primary,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 14.sp,
                        textDecoration = TextDecoration.Underline,
                        modifier = Modifier.combinedClickable(
                            onClick = { onMacClick(segment.value) },
                            onLongClick = { onMacLongClick(segment.value) },
                        ),
                    )
                }
            }
        }
    }
}

private fun formatLogLine(entry: LogEntry): String =
    "[${entry.timestamp}] [${entry.type.name}] ${entry.message}"

private fun formatLogs(logs: List<LogEntry>): String =
    logs.joinToString("\n") { "[${it.timestamp}] [${it.type.name}] ${it.message}" }

private fun logFileName(): String = "hassble_logs_${System.currentTimeMillis()}.txt"

private fun writeLogCacheFile(context: Context, logString: String, fileName: String = logFileName()): java.io.File {
    val file = java.io.File(context.cacheDir, fileName)
    file.writeText(logString)
    return file
}

private fun shareLogs(context: Context, logs: List<LogEntry>) {
    if (logs.isEmpty()) {
        Toast.makeText(context, context.getString(R.string.logs_empty), Toast.LENGTH_SHORT).show()
        return
    }
    val logString = formatLogs(logs)
    try {
        val fileName = logFileName()
        val file = writeLogCacheFile(context, logString, fileName)
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
        val mimeType = context.contentResolver.getType(uri) ?: "text/plain"
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, fileName)
            clipData = ClipData.newUri(context.contentResolver, fileName, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(sendIntent, context.getString(R.string.logs_share)).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(chooser)
    } catch (e: Exception) {
        Toast.makeText(context, context.getString(R.string.logs_share_failed, e.message), Toast.LENGTH_LONG).show()
    }
}

private fun saveLogsToDownloads(context: Context, logs: List<LogEntry>) {
    if (logs.isEmpty()) {
        Toast.makeText(context, context.getString(R.string.logs_empty), Toast.LENGTH_SHORT).show()
        return
    }
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
        Toast.makeText(context, context.getString(R.string.logs_save_unsupported), Toast.LENGTH_LONG).show()
        return
    }
    val logString = formatLogs(logs)
    val fileName = logFileName()
    try {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("insert failed")
        resolver.openOutputStream(uri)?.use { stream ->
            stream.write(logString.toByteArray(Charsets.UTF_8))
        } ?: throw IllegalStateException("openOutputStream failed")
        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        Toast.makeText(context, context.getString(R.string.logs_saved_to_downloads, fileName), Toast.LENGTH_LONG).show()
    } catch (e: Exception) {
        Toast.makeText(context, context.getString(R.string.logs_save_failed, e.message), Toast.LENGTH_LONG).show()
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

@Composable
private fun GitConfigSection(
    repoInput: String,
    branchInput: String,
    tokenInput: String,
    onRepoChange: (String) -> Unit,
    onBranchChange: (String) -> Unit,
    onTokenChange: (String) -> Unit,
) {
    var showAdvanced by remember {
        mutableStateOf(
            branchInput != HassBleDefaults.DEFAULT_BRANCH || tokenInput.isNotBlank(),
        )
    }

    LaunchedEffect(branchInput, tokenInput) {
        if (branchInput != HassBleDefaults.DEFAULT_BRANCH || tokenInput.isNotBlank()) {
            showAdvanced = true
        }
    }

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

            OutlinedTextField(
                value = repoInput,
                onValueChange = onRepoChange,
                label = { Text(stringResource(R.string.git_repo_label)) },
                placeholder = { Text("eigger/hassble-config", color = Color.Gray, fontSize = 13.sp) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
            )

            Text(
                text = stringResource(
                    R.string.git_fixed_files_hint,
                    HassBleDefaults.CONFIG_FILE,
                    HassBleDefaults.TEMPLATES_FILE,
                ),
                fontSize = 11.sp,
                color = Color.Gray,
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { showAdvanced = !showAdvanced }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                androidx.compose.material3.Checkbox(
                    checked = showAdvanced,
                    onCheckedChange = { showAdvanced = it },
                    colors = androidx.compose.material3.CheckboxDefaults.colors(
                        checkedColor = MaterialTheme.colorScheme.primary,
                        uncheckedColor = Color.Gray,
                    ),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    stringResource(R.string.git_advanced_options),
                    color = if (showAdvanced) Color.White else Color.Gray,
                    fontSize = 13.sp,
                    fontWeight = if (showAdvanced) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
            AnimatedVisibility(
                visible = showAdvanced,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = branchInput,
                        onValueChange = onBranchChange,
                        label = { Text(stringResource(R.string.git_branch_label)) },
                        placeholder = { Text(HassBleDefaults.DEFAULT_BRANCH, color = Color.Gray, fontSize = 13.sp) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                    )
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
}

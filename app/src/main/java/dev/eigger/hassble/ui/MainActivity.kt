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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.foundation.BorderStroke
import androidx.compose.runtime.DisposableEffect
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
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
import dev.eigger.hassble.config.ConfigLoader
import dev.eigger.hassble.config.DeviceConfig
import dev.eigger.hassble.config.GatewayConfig
import dev.eigger.hassble.config.HassSettingsRepository
import dev.eigger.hassble.config.ObdPresetStore
import dev.eigger.hassble.config.Source
import dev.eigger.hassble.net.ConnectionState
import dev.eigger.hassble.service.BleGatewayService
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
    val savedGitUrl by repository.gitUrl.collectAsState(initial = "")
    val savedGitToken by repository.gitToken.collectAsState(initial = "")
    val boundDevices by repository.boundDevices.collectAsState(initial = emptyMap())
    val startOnBoot by repository.startOnBoot.collectAsState(initial = true)

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
    var gitUrlInput by remember { mutableStateOf("") }
    var gitTokenInput by remember { mutableStateOf("") }

    LaunchedEffect(savedHaUrl, savedHaToken, savedGitUrl, savedGitToken) {
        if (urlInput.isEmpty()) urlInput = savedHaUrl
        if (tokenInput.isEmpty()) tokenInput = savedHaToken
        if (gitUrlInput.isEmpty()) gitUrlInput = savedGitUrl
        if (gitTokenInput.isEmpty()) gitTokenInput = savedGitToken ?: ""
    }

    val presets = remember {
        ObdPresetStore.fromYaml(context.assets.open("obd_presets.yaml").bufferedReader().readText())
    }
    val loader = remember { ConfigLoader(File(context.filesDir, "config.yaml"), presets) }
    var loadedConfig by remember { mutableStateOf<GatewayConfig?>(null) }
    var configError by remember { mutableStateOf<String?>(null) }
    var isConfigLoading by remember { mutableStateOf(false) }

    LaunchedEffect(gitUrlInput, gitTokenInput) {
        if (gitUrlInput.isNotBlank()) {
            isConfigLoading = true
            configError = null
            val res = loader.load(gitUrlInput, gitTokenInput.ifBlank { null })
            isConfigLoading = false
            if (res.isSuccess) {
                loadedConfig = res.getOrNull()
            } else {
                val exception = res.exceptionOrNull()
                val userFriendlyError = when (exception) {
                    is java.net.UnknownHostException -> "네트워크 연결 실패: 서버 주소를 찾을 수 없습니다."
                    is java.net.ConnectException -> "서버 연결 실패: URL을 확인해 주세요."
                    is java.io.IOException -> "네트워크 데이터 읽기 오류가 발생했습니다."
                    is com.charleskorn.kaml.YamlException -> "설정 파일(YAML) 구문 오류가 발생했습니다. 들여쓰기 등을 확인해 주세요.\n(상세: ${exception.localizedMessage})"
                    is kotlinx.serialization.SerializationException -> "설정 파일 데이터 분석 오류가 발생했습니다.\n(상세: ${exception.localizedMessage})"
                    else -> "설정을 로드할 수 없습니다. URL 및 파일을 확인해 주세요.\n(상세: ${exception?.localizedMessage})"
                }
                configError = userFriendlyError
                loadedConfig = loader.loadCache()
                if (loadedConfig != null) {
                    Toast.makeText(context, "최신 설정을 불러오지 못해 캐시된 설정을 사용합니다.", Toast.LENGTH_LONG).show()
                }
            }
        } else {
            loadedConfig = loader.loadCache()
        }
    }

    val isRunning by BleGatewayService.isServiceRunning.collectAsState()
    val connState by BleGatewayService.serviceConnectionState.collectAsState()

    val permissionsToRequest = mutableListOf<String>().apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val launcher = rememberLauncherForActivityResult(contract = ActivityResultContracts.RequestMultiplePermissions()) { _ -> }
    LaunchedEffect(Unit) {
        val ungranted = permissionsToRequest.filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
        if (ungranted.isNotEmpty()) launcher.launch(ungranted.toTypedArray())
    }

    var scanningDevice by remember { mutableStateOf<DeviceConfig?>(null) }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text(text = stringResource(R.string.app_name), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                Text(text = stringResource(R.string.app_sub_title), style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            }
            StatusBadge(isRunning = isRunning, connState = connState)
        }

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
            }
        }

        Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(imageVector = Icons.Default.Settings, contentDescription = "Settings", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = stringResource(R.string.server_integration_settings), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                }
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedTextField(value = urlInput, onValueChange = { urlInput = it }, label = { Text("HA URL") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp))
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(value = tokenInput, onValueChange = { tokenInput = it }, label = { Text(stringResource(R.string.long_lived_token)) }, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp))
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(value = gitUrlInput, onValueChange = { gitUrlInput = it }, label = { Text(stringResource(R.string.git_config_url)) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp))
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(value = gitTokenInput, onValueChange = { gitTokenInput = it }, label = { Text(stringResource(R.string.git_token_optional)) }, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp))
            }
        }

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
                        onCheckedChange = { scope.launch { repository.saveStartOnBoot(it) } },
                        colors = SwitchDefaults.colors(checkedThumbColor = Color.Black, checkedTrackColor = MaterialTheme.colorScheme.primary)
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = stringResource(R.string.battery_opt_status), fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = Color.White)
                        Text(
                            text = if (isBatteryIgnored) stringResource(R.string.battery_opt_ignored) else stringResource(R.string.battery_opt_active),
                            fontSize = 12.sp,
                            color = if (isBatteryIgnored) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error
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
                                    val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                                    context.startActivity(intent)
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                            shape = RoundedCornerShape(8.dp)
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
                        modifier = Modifier.padding(start = 8.dp)
                    ) {
                        Text(text = stringResource(R.string.go_to_settings_btn), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        Text(text = stringResource(R.string.devices_adapters_list), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.padding(top = 8.dp))

        if (isConfigLoading) {
            Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = MaterialTheme.colorScheme.primary) }
        } else if (configError != null && loadedConfig == null) {
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.1f)), border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.3f))) {
                Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(imageVector = Icons.Default.Warning, contentDescription = "Error", tint = MaterialTheme.colorScheme.error)
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(text = stringResource(R.string.config_load_error, configError ?: ""), color = MaterialTheme.colorScheme.error, fontSize = 14.sp)
                }
            }
        } else if (loadedConfig == null || loadedConfig?.devices?.isEmpty() == true) {
            Text(text = stringResource(R.string.no_devices_configured), color = Color.Gray, fontSize = 14.sp, modifier = Modifier.padding(vertical = 12.dp))
        } else {
            loadedConfig?.devices?.forEach { device ->
                DeviceConfigCard(
                    device = device,
                    boundMac = boundDevices[device.id],
                    onBindClick = { scanningDevice = device },
                    onUnbindClick = { scope.launch { repository.unbindDevice(device.id) } }
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        val buttonBrush = if (isRunning) Brush.horizontalGradient(listOf(Color(0xFFFF5252), Color(0xFFFF1744))) else Brush.horizontalGradient(listOf(Color(0xFF9E86FF), Color(0xFF651FFF)))
        Box(modifier = Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(28.dp)).background(buttonBrush)) {
            Button(onClick = {
                if (isRunning) {
                    BleGatewayService.stop(context)
                } else {
                    if (urlInput.isNotBlank() && tokenInput.isNotBlank() && gitUrlInput.isNotBlank()) {
                        scope.launch {
                            repository.saveHaSettings(urlInput, tokenInput)
                            repository.saveGitSettings(gitUrlInput, gitTokenInput.ifBlank { null })
                            BleGatewayService.start(context, urlInput, tokenInput, gitUrlInput, gitTokenInput.ifBlank { null })
                        }
                    } else {
                        Toast.makeText(context, context.getString(R.string.fill_all_fields_toast), Toast.LENGTH_SHORT).show()
                    }
                }
            }, modifier = Modifier.fillMaxSize(), colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent), shape = RoundedCornerShape(28.dp)) {
                Text(text = if (isRunning) stringResource(R.string.stop_gateway) else stringResource(R.string.start_gateway), fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
            }
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
}



@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DeviceConfigCard(device: DeviceConfig, boundMac: String?, onBindClick: () -> Unit, onUnbindClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = device.name, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Color.White)
                    Text(text = "ID: ${device.id}", fontSize = 12.sp, color = Color.Gray)
                }
                val badgeColor = when (device.source) { Source.advertisement -> Color(0xFF02B3E4); Source.gatt_notify -> Color(0xFF9E86FF); Source.obd -> Color(0xFFFF9100) }
                val badgeText = when (device.source) {
                    Source.advertisement -> stringResource(R.string.source_advertisement)
                    Source.gatt_notify -> stringResource(R.string.source_gatt_notify)
                    Source.obd -> stringResource(R.string.source_obd)
                }
                Box(modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(badgeColor.copy(alpha = 0.15f)).border(1.dp, badgeColor.copy(alpha = 0.3f), RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 4.dp)) {
                    Text(text = badgeText, color = badgeColor, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            if (device.sensors.isNotEmpty()) {
                Text(text = stringResource(R.string.sensor_list), fontSize = 12.sp, color = Color.Gray, fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.height(4.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    device.sensors.take(4).forEach { sensor ->
                        Box(modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Color.White.copy(alpha = 0.05f)).padding(horizontal = 6.dp, vertical = 3.dp)) {
                            Text(text = sensor.key, color = Color.LightGray, fontSize = 11.sp)
                        }
                    }
                    if (device.sensors.size > 4) {
                        Box(modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Color.White.copy(alpha = 0.05f)).padding(horizontal = 6.dp, vertical = 3.dp)) {
                            Text(text = "+${device.sensors.size - 4}", color = Color.LightGray, fontSize = 11.sp)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
            }
            Box(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color.Black.copy(alpha = 0.2f)).padding(12.dp)) {
                when (device.source) {
                    Source.advertisement -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(modifier = Modifier.size(6.dp).background(Color(0xFF00E676), CircleShape))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(text = stringResource(R.string.auto_detecting), color = Color(0xFF00E676), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    Source.gatt_notify, Source.obd -> {
                        val hardcodedMac = if (device.source == Source.gatt_notify) device.gatt?.mac else device.obd?.mac
                        if (!hardcodedMac.isNullOrBlank()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(modifier = Modifier.size(6.dp).background(Color.Gray, CircleShape))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(text = stringResource(R.string.bound_in_config, hardcodedMac), color = Color.LightGray, fontSize = 12.sp)
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
    }
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
private fun StatusRow(label: String, value: String, isHighlighted: Boolean) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(text = label, color = Color.Gray, fontSize = 14.sp)
        Text(text = value, color = if (isHighlighted) MaterialTheme.colorScheme.secondary else Color.White, fontWeight = if (isHighlighted) FontWeight.Bold else FontWeight.Normal, fontSize = 14.sp)
    }
}

@Composable
private fun StatusBadge(isRunning: Boolean, connState: ConnectionState) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val alpha by infiniteTransition.animateFloat(initialValue = 0.4f, targetValue = 1f, animationSpec = infiniteRepeatable(animation = tween(1000), repeatMode = RepeatMode.Reverse), label = "pulseAlpha")
    val (color, text) = when { !isRunning -> Color.Gray to "Offline"; connState == ConnectionState.Connected -> MaterialTheme.colorScheme.secondary to "Connected"; connState == ConnectionState.Connecting -> Color(0xFFFFD600) to "Connecting"; else -> MaterialTheme.colorScheme.error to "Error" }
    Row(modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(color.copy(alpha = 0.15f)).border(1.dp, color.copy(alpha = 0.3f), RoundedCornerShape(12.dp)).padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(8.dp).alpha(if (connState == ConnectionState.Connecting) alpha else 1f).background(color, shape = CircleShape))
        Spacer(modifier = Modifier.width(6.dp))
        Text(text = text, color = color, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

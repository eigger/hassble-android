package dev.eigger.hassble.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.addCallback
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.eigger.hassble.R
import dev.eigger.hassble.config.BleScanModeOption
import dev.eigger.hassble.config.ConfigLoader
import dev.eigger.hassble.config.HassSettingsRepository
import dev.eigger.hassble.config.ObdPresetStore
import dev.eigger.hassble.config.Source
import dev.eigger.hassble.service.TaskerCommand
import dev.eigger.hassble.service.TaskerPlugin
import dev.eigger.hassble.service.TaskerSetting
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Tasker/Locale "Action > Plugin > HassBle" 설정 화면.
 * 기존 설정은 [TaskerPlugin.EXTRA_BUNDLE]로 받고, 저장하면 번들과 요약(blurb)을 돌려준다.
 */
class TaskerEditActivity : ComponentActivity() {
    private val initial: TaskerSetting? by lazy { TaskerSetting.fromBundle(intent.getBundleExtra(TaskerPlugin.EXTRA_BUNDLE)) }
    private val hostSupportsSync: Boolean by lazy { TaskerPlugin.hostSupportsSynchronousExecution(intent.extras) }

    private var command by mutableStateOf(TaskerCommand.START_GATEWAY)
    private var mode by mutableStateOf(BleScanModeOption.BALANCED)
    private var deviceId by mutableStateOf("")
    private var stopAfter by mutableStateOf(false)
    private var dirty = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(Activity.RESULT_CANCELED)
        initial?.let {
            command = it.command
            it.mode?.let { m -> mode = m }
            deviceId = it.deviceId.orEmpty()
            stopAfter = it.stopAfter
        }
        // Tasker는 뒤로 가기를 "저장"으로 취급한다. 새 액션을 건드리지 않고 나가면 저장하지 않는다.
        onBackPressedDispatcher.addCallback(this) {
            if (initial != null || dirty) saveAndFinish() else cancelAndFinish()
        }
        setContent {
            HassBleTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    EditScreen()
                }
            }
        }
    }

    private fun currentSetting(): TaskerSetting? =
        TaskerSetting.fromValues(TaskerSetting.CURRENT_VERSION, command.key, mode.name, deviceId, stopAfter)

    private fun commandLabel(c: TaskerCommand): String = getString(
        when (c) {
            TaskerCommand.START_GATEWAY -> R.string.tasker_cmd_start
            TaskerCommand.STOP_GATEWAY -> R.string.tasker_cmd_stop
            TaskerCommand.SET_SCAN_MODE -> R.string.tasker_cmd_scan_mode
            TaskerCommand.POLL_DEVICE -> R.string.tasker_cmd_poll
        },
    )

    private fun modeLabel(m: BleScanModeOption): String = getString(
        when (m) {
            BleScanModeOption.LOW_POWER -> R.string.scan_mode_low_power_btn
            BleScanModeOption.BALANCED -> R.string.scan_mode_balanced_btn
            BleScanModeOption.LOW_LATENCY -> R.string.scan_mode_low_latency_btn
        },
    )

    private fun saveAndFinish() {
        val setting = currentSetting() ?: return cancelAndFinish()
        val blurb = setting.blurb(::commandLabel, ::modeLabel, getString(R.string.tasker_blurb_stop_after))
        val data = Intent()
            .putExtra(TaskerPlugin.EXTRA_BUNDLE, setting.toBundle())
            .putExtra(TaskerPlugin.EXTRA_STRING_BLURB, blurb)
        val timeoutMs = setting.requestedTimeoutMs()
        // Tasker가 동기 실행을 지원할 때만 완료 대기 시간을 요청한다(없으면 Tasker는 기다리지 않는다).
        if (hostSupportsSync && timeoutMs > 0) {
            data.putExtra(TaskerPlugin.EXTRA_REQUESTED_TIMEOUT, timeoutMs.coerceAtMost(TaskerPlugin.REQUESTED_TIMEOUT_MS_MAX))
        }
        setResult(Activity.RESULT_OK, data)
        finish()
    }

    private fun cancelAndFinish() {
        setResult(Activity.RESULT_CANCELED)
        finish()
    }

    @Composable
    private fun EditScreen() {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val repository = remember { HassSettingsRepository(context.applicationContext) }
        val allowAutomation by repository.allowAutomation.collectAsState(initial = true)
        var devices by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }

        LaunchedEffect(Unit) {
            devices = withContext(Dispatchers.IO) { loadPollableDevices(repository) }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.tasker_edit_title), fontSize = 20.sp, color = Color.White)

            if (!allowAutomation) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.12f))) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.tasker_automation_off), fontSize = 13.sp, color = Color.White)
                        Button(onClick = { scope.launch { repository.saveAllowAutomation(true) } }) {
                            Text(stringResource(R.string.tasker_enable_automation))
                        }
                    }
                }
            }

            DropdownField(
                label = stringResource(R.string.tasker_command),
                valueText = when (command) {
                    TaskerCommand.POLL_DEVICE -> stringResource(R.string.tasker_cmd_poll_option)
                    else -> commandLabel(command)
                },
                options = TaskerCommand.entries.map {
                    (if (it == TaskerCommand.POLL_DEVICE) context.getString(R.string.tasker_cmd_poll_option) else commandLabel(it)) to it
                },
                onSelect = { command = it; dirty = true },
            )

            when (command) {
                TaskerCommand.SET_SCAN_MODE -> DropdownField(
                    label = stringResource(R.string.tasker_scan_mode),
                    valueText = modeLabel(mode),
                    options = BleScanModeOption.entries.map { modeLabel(it) to it },
                    onSelect = { mode = it; dirty = true },
                )
                TaskerCommand.POLL_DEVICE -> {
                    DeviceField(devices)
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { stopAfter = !stopAfter; dirty = true },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = stopAfter, onCheckedChange = { stopAfter = it; dirty = true })
                        Text(stringResource(R.string.tasker_stop_after), color = Color.White, fontSize = 14.sp)
                    }
                }
                else -> Unit
            }

            if (command == TaskerCommand.START_GATEWAY || command == TaskerCommand.POLL_DEVICE) {
                Text(stringResource(R.string.tasker_wait_note), fontSize = 12.sp, color = Color.Gray)
            }

            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = { cancelAndFinish() }) { Text(stringResource(R.string.cancel)) }
                Button(onClick = { saveAndFinish() }, enabled = currentSetting() != null) {
                    Text(stringResource(R.string.tasker_save))
                }
            }
        }
    }

    @Composable
    private fun DeviceField(devices: List<Pair<String, String>>) {
        var expanded by remember { mutableStateOf(false) }
        Box {
            OutlinedTextField(
                value = deviceId,
                onValueChange = { deviceId = it; dirty = true },
                label = { Text(stringResource(R.string.tasker_device)) },
                singleLine = true,
                isError = deviceId.isBlank(),
                supportingText = {
                    Text(
                        stringResource(
                            if (deviceId.isBlank()) R.string.tasker_device_required
                            else if (devices.isEmpty()) R.string.tasker_device_no_config
                            else R.string.tasker_device_pick,
                        ),
                    )
                },
                trailingIcon = {
                    if (devices.isNotEmpty()) {
                        IconButton(onClick = { expanded = true }) { Icon(Icons.Filled.ArrowDropDown, contentDescription = stringResource(R.string.tasker_device_pick)) }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                devices.forEach { (id, name) ->
                    DropdownMenuItem(
                        text = { Text(if (name.isNotBlank() && name != id) "$name ($id)" else id) },
                        onClick = { deviceId = id; dirty = true; expanded = false },
                    )
                }
            }
        }
    }

    /** 저장된 설정 URL의 캐시된 config에서 GATT/OBD 기기만 (id, name)으로 뽑는다. 없으면 빈 목록. */
    private suspend fun loadPollableDevices(repository: HassSettingsRepository): List<Pair<String, String>> =
        runCatching {
            val gitUrl = repository.gitUrl.first()
            if (gitUrl.isBlank()) return@runCatching emptyList()
            val presets = ObdPresetStore.fromYaml(assets.open("obd_presets.yaml").bufferedReader().readText())
            val loader = ConfigLoader(File(filesDir, "config_cache"), presets)
            loader.loadCache(gitUrl)?.devices
                ?.filter { it.source != Source.advertisement }
                ?.map { it.id to it.name }
                .orEmpty()
        }.getOrDefault(emptyList())
}

@Composable
private fun <T> DropdownField(label: String, valueText: String, options: List<Pair<String, T>>, onSelect: (T) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedTextField(
            value = valueText,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { Icon(Icons.Filled.ArrowDropDown, contentDescription = null) },
            modifier = Modifier.fillMaxWidth(),
        )
        Box(Modifier.matchParentSize().clickable { expanded = true })
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (text, value) ->
                DropdownMenuItem(text = { Text(text) }, onClick = { onSelect(value); expanded = false })
            }
        }
    }
}

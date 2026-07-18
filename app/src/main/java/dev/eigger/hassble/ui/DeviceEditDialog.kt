package dev.eigger.hassble.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.eigger.hassble.R
import dev.eigger.hassble.config.DataType
import dev.eigger.hassble.config.DecodeConfig
import dev.eigger.hassble.config.DeviceConfig
import dev.eigger.hassble.config.Endian
import dev.eigger.hassble.config.HaSensorOptions
import dev.eigger.hassble.config.MatchConfig
import dev.eigger.hassble.config.ObdDeviceBuilder
import dev.eigger.hassble.config.ObdPresetStore
import dev.eigger.hassble.config.SensorConfig
import dev.eigger.hassble.config.Source
import dev.eigger.hassble.config.SourceField
import dev.eigger.hassble.config.effectiveStateClass

/**
 * 앱에서 만든 draft 기기의 설정을 편집한다.
 * 기기 id는 엔티티 키/바인딩 정합성을 위해 고정하고, 이름·match·센서만 수정한다.
 */
@Composable
fun DeviceEditDialog(
    device: DeviceConfig,
    presets: ObdPresetStore,
    onDismiss: () -> Unit,
    onSave: (DeviceConfig) -> Unit,
) {
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
            when (device.source) {
                Source.obd -> ObdEditContent(device, presets, onDismiss, onSave)
                else -> DecodeEditContent(device, onDismiss, onSave)
            }
        }
    }
}

@Composable
private fun EditHeader(device: DeviceConfig) {
    Column {
        Text(
            stringResource(R.string.device_edit_title),
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp,
            color = Color.White,
        )
        Text(
            "${device.id} · ${device.source.name}",
            fontSize = 11.sp,
            color = Color.Gray,
            fontFamily = FontFamily.Monospace,
        )
    }
}

// ---------------------------------------------------------------------------
// advertisement / gatt_notify: 이름 + match + decode 센서 편집
// ---------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DecodeEditContent(
    device: DeviceConfig,
    onDismiss: () -> Unit,
    onSave: (DeviceConfig) -> Unit,
) {
    val context = LocalContext.current
    val isAdvertisement = device.source == Source.advertisement

    var deviceName by remember(device.id) { mutableStateOf(device.name) }
    var namePrefix by remember(device.id) { mutableStateOf(device.match?.namePrefix ?: "") }
    var mfrMinLength by remember(device.id) {
        mutableStateOf(device.match?.manufacturerMinLength?.toString() ?: "")
    }

    val sensors = remember(device.id) { mutableStateListOf<SensorConfig>().apply { addAll(device.sensors) } }
    var editingIndex by remember(device.id) { mutableStateOf<Int?>(null) }
    var pendingDeleteIndex by remember(device.id) { mutableStateOf<Int?>(null) }

    // 센서 입력 폼 상태
    var sensorKey by remember(device.id) { mutableStateOf("value") }
    var platform by remember(device.id) { mutableStateOf("sensor") }
    var sourceField by remember(device.id) {
        mutableStateOf(if (isAdvertisement) SourceField.manufacturer_data else SourceField.raw)
    }
    var offset by remember(device.id) { mutableIntStateOf(0) }
    var length by remember(device.id) { mutableIntStateOf(2) }
    var dataType by remember(device.id) { mutableStateOf(DataType.int16) }
    var endian by remember(device.id) { mutableStateOf(Endian.big) }
    var scaleText by remember(device.id) { mutableStateOf("1") }
    var unit by remember(device.id) { mutableStateOf("") }
    var deviceClass by remember(device.id) { mutableStateOf("") }
    var stateClass by remember(device.id) { mutableStateOf("measurement") }
    var minLengthManual by remember(device.id) { mutableStateOf(false) }
    var minLengthText by remember(device.id) { mutableStateOf("") }
    var exactLengthText by remember(device.id) { mutableStateOf("") }

    fun resetForm() {
        editingIndex = null
        sensorKey = "value_${sensors.size + 1}"
        platform = "sensor"
        sourceField = if (isAdvertisement) SourceField.manufacturer_data else SourceField.raw
        offset = 0
        dataType = DataType.int16
        length = defaultDecodeLength(DataType.int16)
        endian = Endian.big
        scaleText = "1"
        unit = ""
        deviceClass = ""
        stateClass = "measurement"
        minLengthManual = false
        minLengthText = ""
        exactLengthText = ""
    }

    fun loadForm(index: Int) {
        val s = sensors[index]
        editingIndex = index
        sensorKey = s.key
        platform = s.platform
        sourceField = s.sourceField
        val d = s.decode ?: DecodeConfig()
        offset = d.offset
        dataType = d.type
        length = d.length
        endian = d.endian
        scaleText = formatScale(d.scale)
        unit = s.unit ?: ""
        deviceClass = s.deviceClass ?: ""
        stateClass = s.effectiveStateClass() ?: HaSensorOptions.defaultStateClass(d.type, s.platform)
        exactLengthText = s.length?.toString() ?: ""
        val autoMin = d.offset + d.length
        minLengthManual = s.minLength != null && s.minLength != autoMin
        minLengthText = s.minLength?.toString() ?: autoMin.toString()
    }

    val isNumericDecode = dataType !in setOf(DataType.timestamp, DataType.string)
    val decodeLengthEditable = dataType == DataType.string

    pendingDeleteIndex?.let { index ->
        val target = sensors.getOrNull(index)
        if (target == null) {
            pendingDeleteIndex = null
        } else {
            Dialog(onDismissRequest = { pendingDeleteIndex = null }) {
                Card(shape = HassBleShapes.Dialog, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(stringResource(R.string.device_edit_sensor_delete_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(stringResource(R.string.device_edit_sensor_delete_body, target.key), color = Color.Gray, fontSize = 13.sp)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            HassCancelButton(onClick = { pendingDeleteIndex = null })
                            Spacer(Modifier.width(8.dp))
                            HassDangerButton(
                                text = stringResource(R.string.device_edit_sensor_delete_confirm),
                                onClick = {
                                    sensors.removeAt(index)
                                    if (editingIndex == index) resetForm()
                                    pendingDeleteIndex = null
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            EditHeader(device)

            OutlinedTextField(
                value = deviceName,
                onValueChange = { deviceName = it },
                label = { Text(stringResource(R.string.config_device_name_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            if (isAdvertisement) {
                OutlinedTextField(
                    value = namePrefix,
                    onValueChange = { namePrefix = it },
                    label = { Text(stringResource(R.string.device_edit_name_prefix)) },
                    placeholder = { Text(stringResource(R.string.device_edit_name_prefix_hint), color = Color.Gray) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = mfrMinLength,
                    onValueChange = { mfrMinLength = it.filter { ch -> ch.isDigit() } },
                    label = { Text(stringResource(R.string.device_edit_mfr_min_length)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                val readOnly = buildList {
                    device.match?.mac?.let { add("mac=$it") }
                    device.match?.serviceDataUuid?.let { add("svc=$it") }
                    device.match?.manufacturerId?.let { add("mfr=0x%04X".format(it)) }
                    device.match?.manufacturerHexPrefix?.let { add("prefix=$it") }
                }
                if (readOnly.isNotEmpty()) {
                    Text(stringResource(R.string.device_edit_match_readonly), fontSize = 10.sp, color = Color.Gray)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        readOnly.forEach { MatchChip(it) }
                    }
                }
            }

            HorizontalDivider(color = Color.White.copy(alpha = 0.1f))

            Text(
                stringResource(R.string.adv_wizard_sensors_added, sensors.size),
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
                color = Color.White,
            )
            sensors.forEachIndexed { index, s ->
                val highlighted = editingIndex == index
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            if (highlighted) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                            else Color.Transparent,
                        )
                        .clickable { loadForm(index) }
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val d = s.decode ?: DecodeConfig()
                    val minLabel = s.minLength?.toString() ?: (d.offset + d.length).toString()
                    Text(
                        stringResource(
                            R.string.adv_wizard_sensor_line,
                            s.key,
                            d.type.name,
                            d.offset,
                            d.length,
                            minLabel,
                        ) + if (s.platform == "text_sensor") " · text" else "",
                        modifier = Modifier.weight(1f),
                        fontSize = 11.sp,
                        color = if (highlighted) MaterialTheme.colorScheme.primary else Color.LightGray,
                    )
                    IconButton(onClick = { loadForm(index) }) {
                        Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.device_edit_btn), tint = MaterialTheme.colorScheme.primary)
                    }
                    IconButton(onClick = { pendingDeleteIndex = index }) {
                        Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.device_edit_sensor_delete_confirm), tint = MaterialTheme.colorScheme.error)
                    }
                }
            }

            HorizontalDivider(color = Color.White.copy(alpha = 0.1f))

            Text(
                stringResource(
                    if (editingIndex != null) R.string.device_edit_editing_sensor
                    else R.string.adv_wizard_add_sensor,
                ),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
            )

            OutlinedTextField(
                value = sensorKey,
                onValueChange = { sensorKey = it },
                label = { Text(stringResource(R.string.adv_wizard_sensor_key)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            if (isAdvertisement) {
                SourceFieldDropdown(sourceField) { sourceField = it }
            }

            PlatformDropdown(platform, enabled = dataType != DataType.timestamp) { platform = it }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = offset.toString(),
                    onValueChange = { offset = it.toIntOrNull()?.coerceAtLeast(0) ?: 0 },
                    label = { Text(stringResource(R.string.adv_wizard_offset)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = length.toString(),
                    onValueChange = { if (decodeLengthEditable) length = it.toIntOrNull()?.coerceIn(1, 64) ?: 1 },
                    readOnly = !decodeLengthEditable,
                    enabled = decodeLengthEditable,
                    label = { Text(stringResource(R.string.adv_wizard_decode_length)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                DataTypeDropdown(
                    dataType,
                    onSelected = { newType ->
                        dataType = newType
                        if (!decodeLengthEditableFor(newType)) length = defaultDecodeLength(newType)
                        else if (length <= 0) length = defaultDecodeLength(newType)
                        when (newType) {
                            DataType.string -> {
                                platform = "text_sensor"
                                deviceClass = ""
                                stateClass = ""
                            }
                            DataType.timestamp -> {
                                platform = "sensor"
                                deviceClass = HaSensorOptions.defaultDeviceClass(newType)
                                stateClass = ""
                            }
                            else -> {
                                if (platform == "text_sensor") platform = "sensor"
                                if (deviceClass in setOf("timestamp", "date", "uptime")) deviceClass = ""
                                if (stateClass.isBlank()) stateClass = "measurement"
                            }
                        }
                        if (!minLengthManual) minLengthText = (offset + length).toString()
                    },
                    modifier = Modifier.weight(1f),
                )
                if (isNumericDecode) {
                    EndianDropdown(endian, onSelected = { endian = it }, modifier = Modifier.weight(1f))
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = minLengthText,
                    onValueChange = {
                        minLengthManual = true
                        minLengthText = it.filter { ch -> ch.isDigit() }
                    },
                    label = { Text(stringResource(R.string.adv_wizard_min_length)) },
                    placeholder = { Text(stringResource(R.string.adv_wizard_auto_min_length), color = Color.Gray) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = exactLengthText,
                    onValueChange = { exactLengthText = it.filter { ch -> ch.isDigit() } },
                    label = { Text(stringResource(R.string.adv_wizard_exact_length)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }

            if (isNumericDecode) {
                val scaleInvalid = scaleText.isNotBlank() && scaleText.toDoubleOrNull() == null
                OutlinedTextField(
                    value = scaleText,
                    onValueChange = { scaleText = filterDecimalInput(it) },
                    label = { Text(stringResource(R.string.adv_wizard_scale)) },
                    isError = scaleInvalid,
                    supportingText = if (scaleInvalid) {
                        { Text(stringResource(R.string.adv_wizard_scale_invalid), color = MaterialTheme.colorScheme.error) }
                    } else null,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = unit,
                    onValueChange = { unit = it },
                    label = { Text(stringResource(R.string.adv_wizard_unit)) },
                    placeholder = { Text("°C, %, …", color = Color.Gray) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                HaDeviceClassDropdown(
                    selected = deviceClass,
                    dataType = dataType,
                    platform = platform,
                    onSelected = { deviceClass = it },
                    modifier = Modifier.weight(1f),
                )
                HaStateClassDropdown(
                    selected = stateClass,
                    dataType = dataType,
                    platform = platform,
                    onSelected = { stateClass = it },
                    modifier = Modifier.weight(1f),
                )
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HassSecondaryButton(
                    text = stringResource(
                        if (editingIndex != null) R.string.device_edit_update_sensor
                        else R.string.adv_wizard_add_sensor_btn,
                    ),
                    onClick = {
                        val base = editingIndex?.let { sensors[it] }
                        val built = buildSensor(
                            base = base,
                            key = sensorKey.trim().ifBlank { "value_${sensors.size + 1}" },
                            platform = platform,
                            sourceField = sourceField,
                            offset = offset,
                            length = length,
                            type = dataType,
                            endian = endian,
                            scaleText = scaleText,
                            unit = unit,
                            deviceClass = deviceClass,
                            stateClass = stateClass,
                            minLengthText = minLengthText,
                            exactLengthText = exactLengthText,
                        )
                        val idx = editingIndex
                        if (idx != null) sensors[idx] = built else sensors.add(built)
                        resetForm()
                    },
                    modifier = Modifier.weight(1f),
                )
                if (editingIndex != null) {
                    HassCancelButton(
                        text = stringResource(R.string.device_edit_cancel_sensor),
                        onClick = { resetForm() },
                    )
                }
            }
        }

        HorizontalDivider(color = Color.White.copy(alpha = 0.1f), modifier = Modifier.padding(vertical = 4.dp))

        HassDialogActionRow(
            primaryLabel = stringResource(R.string.device_edit_save),
            onPrimary = {
                when {
                    deviceName.isBlank() ->
                        Toast.makeText(context, context.getString(R.string.adv_wizard_fill_device), Toast.LENGTH_SHORT).show()
                    sensors.isEmpty() ->
                        Toast.makeText(context, context.getString(R.string.adv_wizard_need_sensor), Toast.LENGTH_SHORT).show()
                    else -> {
                        val newMatch = if (isAdvertisement) {
                            (device.match ?: MatchConfig()).copy(
                                namePrefix = namePrefix.trim().ifBlank { null },
                                manufacturerMinLength = mfrMinLength.toIntOrNull(),
                            )
                        } else {
                            device.match
                        }
                        onSave(
                            device.copy(
                                name = deviceName.trim(),
                                match = newMatch,
                                sensors = sensors.toList(),
                            ),
                        )
                    }
                }
            },
            onCancel = onDismiss,
            primaryEnabled = sensors.isNotEmpty() && deviceName.isNotBlank(),
        )
    }
}

// ---------------------------------------------------------------------------
// OBD: 이름 + preset 선택
// ---------------------------------------------------------------------------

@Composable
private fun ObdEditContent(
    device: DeviceConfig,
    presets: ObdPresetStore,
    onDismiss: () -> Unit,
    onSave: (DeviceConfig) -> Unit,
) {
    var deviceName by remember(device.id) { mutableStateOf(device.name) }
    val presetNames = remember(presets) { presets.presetNames() }
    var selected by remember(device.id) {
        mutableStateOf(device.sensors.mapNotNull { it.preset }.toSet())
    }

    Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
        EditHeader(device)

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = deviceName,
                onValueChange = { deviceName = it },
                label = { Text(stringResource(R.string.config_device_name_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )

            HorizontalDivider(color = Color.White.copy(alpha = 0.1f))

            Text(
                stringResource(R.string.config_obd_presets_label),
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
                color = Color.White,
            )
            presetNames.forEach { preset ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable {
                            selected = if (preset in selected) selected - preset else selected + preset
                        }
                        .padding(vertical = 2.dp),
                ) {
                    Checkbox(
                        checked = preset in selected,
                        onCheckedChange = { checked ->
                            selected = if (checked) selected + preset else selected - preset
                        },
                    )
                    Text(preset, fontSize = 13.sp, color = Color.White)
                }
            }
        }

        HorizontalDivider(color = Color.White.copy(alpha = 0.1f), modifier = Modifier.padding(vertical = 8.dp))

        HassDialogActionRow(
            primaryLabel = stringResource(R.string.device_edit_save),
            onPrimary = {
                val name = deviceName.ifBlank { device.id }
                val rebuilt = ObdDeviceBuilder.create(
                    id = device.id,
                    name = name,
                    presetKeys = selected.sorted(),
                    serviceUuid = device.obd?.serviceUuid ?: "FFF0",
                    txCharUuid = device.obd?.txCharUuid ?: "FFF2",
                    rxCharUuid = device.obd?.rxCharUuid ?: "FFF1",
                )
                val merged = rebuilt.copy(
                    obd = device.obd ?: rebuilt.obd,
                    sensors = rebuilt.sensors.map { s ->
                        device.sensors.firstOrNull { it.preset == s.preset }
                            ?.let { s.copy(updateInterval = it.updateInterval) } ?: s
                    },
                )
                onSave(merged)
            },
            onCancel = onDismiss,
            primaryEnabled = selected.isNotEmpty() && deviceName.isNotBlank(),
        )
    }
}

// ---------------------------------------------------------------------------
// helpers
// ---------------------------------------------------------------------------

private fun decodeLengthEditableFor(type: DataType): Boolean = type == DataType.string

private fun formatScale(d: Double): String =
    if (d == d.toLong().toDouble()) d.toLong().toString() else d.toString()

/** 숫자 필드용 입력 필터: 숫자, 맨 앞의 '-', 소수점 하나만 허용 (scale은 음수·소수 모두 가능). */
internal fun filterDecimalInput(raw: String): String {
    val sb = StringBuilder()
    var seenDot = false
    for ((i, ch) in raw.withIndex()) {
        when {
            ch == '-' && i == 0 -> sb.append(ch)
            ch == '.' && !seenDot -> { seenDot = true; sb.append(ch) }
            ch.isDigit() -> sb.append(ch)
        }
    }
    return sb.toString()
}

private fun buildSensor(
    base: SensorConfig?,
    key: String,
    platform: String,
    sourceField: SourceField,
    offset: Int,
    length: Int,
    type: DataType,
    endian: Endian,
    scaleText: String,
    unit: String,
    deviceClass: String,
    stateClass: String,
    minLengthText: String,
    exactLengthText: String,
): SensorConfig {
    val isText = type == DataType.string || platform == "text_sensor"
    val effectivePlatform = if (isText) "text_sensor" else platform
    val isNumeric = type !in setOf(DataType.timestamp, DataType.string)
    val scale = scaleText.toDoubleOrNull() ?: 1.0
    val parsedMin = minLengthText.toIntOrNull() ?: (offset + length)
    val template = base ?: SensorConfig(key = key)
    return template.copy(
        key = key,
        platform = effectivePlatform,
        deviceClass = deviceClass.takeIf { it.isNotBlank() },
        unit = if (isText) null else unit.takeIf { it.isNotBlank() },
        stateClass = if (isText) null else stateClass.takeIf { it.isNotBlank() },
        accuracyDecimals = if (isNumeric && scale != 1.0) (template.accuracyDecimals ?: 1) else null,
        sourceField = sourceField,
        length = exactLengthText.toIntOrNull(),
        minLength = parsedMin,
        decode = DecodeConfig(
            offset = offset,
            length = length,
            type = type,
            endian = endian,
            scale = if (isNumeric) scale else 1.0,
        ),
    )
}

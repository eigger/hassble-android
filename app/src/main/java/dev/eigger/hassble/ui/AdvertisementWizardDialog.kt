package dev.eigger.hassble.ui

import android.Manifest
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import dev.eigger.hassble.R
import dev.eigger.hassble.ble.AdvertisementCapture
import dev.eigger.hassble.ble.AdvertisementFormatGrouper
import dev.eigger.hassble.config.AdvDeviceBuilder
import dev.eigger.hassble.config.DataType
import dev.eigger.hassble.config.DecodeConfig
import dev.eigger.hassble.config.DeviceConfig
import dev.eigger.hassble.config.Endian
import dev.eigger.hassble.config.HaSensorOptions
import dev.eigger.hassble.config.SourceField
import dev.eigger.hassble.decode.Decoder

private enum class AdvWizardStep { SCAN, EDIT }

private data class AdvWizardSelection(
    val capture: AdvertisementCapture.CapturedAdvertisement,
    /** true면 match.mac 고정 (특정 MAC을 골랐을 때) */
    val fixMacInMatch: Boolean = false,
)

@Composable
fun AdvertisementWizardDialog(
    onDismiss: () -> Unit,
    onCreate: (DeviceConfig) -> Unit,
) {
    var step by remember { mutableStateOf(AdvWizardStep.SCAN) }
    var selection by remember { mutableStateOf<AdvWizardSelection?>(null) }

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
            when (step) {
                AdvWizardStep.SCAN -> AdvScanStep(
                    onDismiss = onDismiss,
                    onSelected = {
                        selection = it
                        step = AdvWizardStep.EDIT
                    },
                )
                AdvWizardStep.EDIT -> selection?.let { sel ->
                    AdvEditStep(
                        capture = sel.capture,
                        fixMacInMatch = sel.fixMacInMatch,
                        onBack = { step = AdvWizardStep.SCAN },
                        onDismiss = onDismiss,
                        onCreate = onCreate,
                    )
                }
            }
        }
    }
}

@Composable
private fun AdvScanStep(
    onDismiss: () -> Unit,
    onSelected: (AdvWizardSelection) -> Unit,
) {
    val context = LocalContext.current
    val manager = remember { context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager }
    val adapter = remember { manager.adapter }
    val scanner = remember { adapter?.bluetoothLeScanner }
    val captures = remember { mutableStateListOf<AdvertisementCapture.CapturedAdvertisement>() }
    var isScanning by remember { mutableStateOf(false) }
    var filterText by remember { mutableStateOf("") }
    var groupByFormat by remember { mutableStateOf(true) }
    var nearbyOnly by remember { mutableStateOf(false) }
    var expandedSignature by remember { mutableStateOf<String?>(null) }

    val allCaptures = captures.toList()
    val filteredCaptures = remember(allCaptures, filterText, nearbyOnly) {
        var list = AdvertisementFormatGrouper.filterByQuery(filterText, allCaptures)
        if (nearbyOnly) list = AdvertisementFormatGrouper.filterNearby(list)
        list.sortedByDescending { it.rssi }
    }
    val formatGroups = remember(filteredCaptures, filterText, nearbyOnly) {
        var groups = AdvertisementFormatGrouper.groupByFormat(filteredCaptures)
        groups = AdvertisementFormatGrouper.filterGroupsByQuery(groups, filterText)
        if (nearbyOnly) groups = AdvertisementFormatGrouper.filterNearbyGroups(groups)
        groups
    }

    val scanCallback = remember {
        object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val captured = AdvertisementCapture.fromScanResult(
                    result,
                    context.getString(R.string.unknown_device),
                )
                if (captured.manufacturerHex == null && captured.serviceDataHex == null && captured.fullScanHex == null) {
                    return
                }
                val idx = captures.indexOfFirst { it.address == captured.address }
                if (idx >= 0) captures[idx] = captured else captures.add(0, captured)
            }
        }
    }

    LaunchedEffect(Unit) {
        if (scanner != null && ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
            if (adapter?.isEnabled == true) {
                isScanning = true
                scanner.startScan(scanCallback)
            } else {
                Toast.makeText(context, context.getString(R.string.enable_bluetooth_toast), Toast.LENGTH_SHORT).show()
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            if (scanner != null && ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
                scanner.stopScan(scanCallback)
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.config_add_adv), fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Color.White)
                Text(stringResource(R.string.adv_wizard_scan_hint), fontSize = 12.sp, color = Color.Gray)
            }
            if (isScanning) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
            }
        }

        Text(stringResource(R.string.adv_wizard_rssi_hint), fontSize = 11.sp, color = Color.Gray)

        OutlinedTextField(
            value = filterText,
            onValueChange = { filterText = it },
            placeholder = { Text(stringResource(R.string.adv_wizard_search_placeholder), color = Color.Gray, fontSize = 12.sp) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.adv_wizard_group_by_format),
                    fontSize = 12.sp,
                    color = Color.White,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = groupByFormat,
                    onCheckedChange = { groupByFormat = it },
                    colors = SwitchDefaults.colors(checkedThumbColor = Color.Black, checkedTrackColor = MaterialTheme.colorScheme.primary),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.adv_wizard_nearby_only),
                    fontSize = 12.sp,
                    color = Color.White,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = nearbyOnly,
                    onCheckedChange = { nearbyOnly = it },
                    colors = SwitchDefaults.colors(checkedThumbColor = Color.Black, checkedTrackColor = MaterialTheme.colorScheme.primary),
                )
            }
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Color.Black.copy(alpha = 0.2f))
                .padding(8.dp),
        ) {
            if (filteredCaptures.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.adv_wizard_waiting_adv), color = Color.Gray, fontSize = 14.sp)
                }
            } else if (groupByFormat) {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(formatGroups, key = { it.signature }) { group ->
                        val expanded = expandedSignature == group.signature
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.06f)),
                        ) {
                            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .clickable { expandedSignature = if (expanded) null else group.signature },
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.Top,
                                ) {
                                    Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                        Text(
                                            group.label,
                                            fontWeight = FontWeight.SemiBold,
                                            color = Color.White,
                                            fontSize = 14.sp,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            stringResource(R.string.adv_wizard_devices_count, group.deviceCount),
                                            fontSize = 11.sp,
                                            color = Color.Gray,
                                        )
                                    }
                                    Text(
                                        "${group.bestRssi} dBm",
                                        color = MaterialTheme.colorScheme.primary,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                    )
                                }
                                PacketHexBlock(
                                    manufacturerHex = group.representative.manufacturerHex,
                                    serviceDataHex = group.representative.serviceDataHex,
                                    fullScanHex = group.representative.fullScanHex,
                                )
                                HassAccentButton(
                                    text = stringResource(R.string.adv_wizard_use_format),
                                    onClick = {
                                        onSelected(AdvWizardSelection(group.representative, fixMacInMatch = false))
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                if (expanded) {
                                    Text(stringResource(R.string.adv_wizard_pick_mac), fontSize = 11.sp, color = Color.Gray, fontWeight = FontWeight.SemiBold)
                                    group.devices.forEach { item ->
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(Color.Black.copy(alpha = 0.15f))
                                                .clickable { onSelected(AdvWizardSelection(item, fixMacInMatch = true)) }
                                                .padding(8.dp),
                                        ) {
                                            CaptureDeviceContent(item)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(filteredCaptures, key = { it.address }) { item ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelected(AdvWizardSelection(item, fixMacInMatch = true)) },
                            colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.06f)),
                        ) {
                            CaptureDeviceContent(item, Modifier.padding(12.dp))
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

@Composable
private fun CaptureDeviceContent(
    item: AdvertisementCapture.CapturedAdvertisement,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
            Text(
                item.name,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                fontSize = 13.sp,
                modifier = Modifier.weight(1f).padding(end = 8.dp),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text("${item.rssi} dBm", color = MaterialTheme.colorScheme.primary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        Text(item.address, color = Color.Gray, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        PacketHexBlock(
            manufacturerHex = item.manufacturerHex,
            serviceDataHex = item.serviceDataHex,
            fullScanHex = item.fullScanHex,
        )
    }
}

@Composable
private fun PacketHexBlock(
    manufacturerHex: String?,
    serviceDataHex: String?,
    fullScanHex: String?,
) {
    if (manufacturerHex == null && serviceDataHex == null && fullScanHex == null) return
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        manufacturerHex?.let { HexLine("mfr", it) }
        serviceDataHex?.let { HexLine("svc", it) }
        fullScanHex?.let { HexLine("raw", it) }
    }
}

@Composable
private fun HexLine(label: String, hex: String) {
    val formatted = hex.uppercase().chunked(2).joinToString(" ")
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(label, fontSize = 9.sp, color = Color.Gray, fontWeight = FontWeight.SemiBold)
        Text(
            text = formatted,
            fontSize = 10.sp,
            color = Color.LightGray,
            fontFamily = FontFamily.Monospace,
            lineHeight = 14.sp,
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun AdvEditStep(
    capture: AdvertisementCapture.CapturedAdvertisement,
    fixMacInMatch: Boolean,
    onBack: () -> Unit,
    onDismiss: () -> Unit,
    onCreate: (DeviceConfig) -> Unit,
) {
    val context = LocalContext.current
    var deviceId by remember(capture.address) { mutableStateOf(AdvDeviceBuilder.slugify(capture.name)) }
    var deviceName by remember(capture.address) { mutableStateOf(capture.name) }
    var useNamePrefix by remember { mutableStateOf(true) }
    val sensors = remember { mutableStateListOf<AdvDeviceBuilder.SensorDraft>() }

    var sensorKey by remember { mutableStateOf("value") }
    var platform by remember { mutableStateOf("sensor") }
    var sourceField by remember { mutableStateOf(AdvDeviceBuilder.defaultSourceField(capture)) }
    var offset by remember { mutableIntStateOf(0) }
    var length by remember { mutableIntStateOf(2) }
    var dataType by remember { mutableStateOf(DataType.int16) }
    var endian by remember { mutableStateOf(Endian.big) }
    var scaleText by remember { mutableStateOf("0.1") }
    var unit by remember { mutableStateOf("") }
    var deviceClass by remember { mutableStateOf("") }
    var stateClass by remember { mutableStateOf("measurement") }
    var minLengthManual by remember { mutableStateOf(false) }
    var minLengthText by remember { mutableStateOf("") }
    var exactLengthText by remember { mutableStateOf("") }

    LaunchedEffect(dataType) {
        length = defaultDecodeLength(dataType)
        when (dataType) {
            DataType.string -> {
                platform = "text_sensor"
                deviceClass = ""
                stateClass = ""
            }
            DataType.timestamp -> {
                platform = "sensor"
                deviceClass = HaSensorOptions.defaultDeviceClass(dataType)
                stateClass = ""
            }
            else -> {
                if (platform == "text_sensor") platform = "sensor"
                if (deviceClass in setOf("timestamp", "date", "uptime")) deviceClass = ""
                if (stateClass.isBlank()) stateClass = "measurement"
            }
        }
    }

    LaunchedEffect(offset, length, dataType, minLengthManual) {
        if (!minLengthManual) {
            minLengthText = (offset + length).toString()
        }
    }

    val isNumericDecode = dataType !in setOf(DataType.timestamp, DataType.string)
    val decodeLengthEditable = dataType == DataType.string

    val match = remember(capture, useNamePrefix, fixMacInMatch) {
        val base = AdvDeviceBuilder.suggestMatch(capture, useNamePrefix)
        if (fixMacInMatch) base.copy(mac = capture.address) else base
    }
    val payloadHex = capture.payloadHex(sourceField)
    val previewDecode = remember(payloadHex, offset, length, dataType, endian, scaleText) {
        val bytes = payloadHex?.let { Decoder.hexToBytes(it) } ?: return@remember null
        val scale = scaleText.toDoubleOrNull() ?: 1.0
        Decoder.decodeStructured(
            bytes,
            DecodeConfig(offset = offset, length = length, type = dataType, endian = endian, scale = scale),
        )
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.adv_wizard_edit_title), fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Color.White)
            Text("${capture.name} · ${capture.address}", fontSize = 11.sp, color = Color.Gray, fontFamily = FontFamily.Monospace)

            Text(stringResource(R.string.adv_wizard_packet_preview), fontSize = 11.sp, color = Color.Gray, fontWeight = FontWeight.SemiBold)
            PacketHexBlock(
                manufacturerHex = capture.manufacturerHex,
                serviceDataHex = capture.serviceDataHex,
                fullScanHex = capture.fullScanHex,
            )

            OutlinedTextField(
            value = deviceId,
            onValueChange = { deviceId = it },
            label = { Text(stringResource(R.string.config_device_id_label)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = deviceName,
            onValueChange = { deviceName = it },
            label = { Text(stringResource(R.string.config_device_name_label)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Text(stringResource(R.string.adv_wizard_match_preview), fontSize = 11.sp, color = Color.Gray, fontWeight = FontWeight.SemiBold)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            match.mac?.let { MatchChip("mac=$it") }
            match.namePrefix?.let { MatchChip("name=$it") }
            match.serviceDataUuid?.let { MatchChip("svc=$it") }
            match.manufacturerId?.let { MatchChip("mfr=0x%04X".format(it)) }
            match.manufacturerMinLength?.let { MatchChip("min_len=$it") }
        }

        HorizontalDivider(color = Color.White.copy(alpha = 0.1f))

        Text(stringResource(R.string.adv_wizard_sensors_added, sensors.size), fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = Color.White)
        sensors.forEachIndexed { index, s ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                val minLabel = s.minLength?.toString()
                    ?: (s.decode.offset + s.decode.length).toString()
                Text(
                    stringResource(
                        R.string.adv_wizard_sensor_line,
                        s.key,
                        s.decode.type.name,
                        s.decode.offset,
                        s.decode.length,
                        minLabel,
                    ) + if (s.platform == "text_sensor") " · text" else "",
                    modifier = Modifier.weight(1f),
                    fontSize = 11.sp,
                    color = Color.LightGray,
                )
                IconButton(onClick = { sensors.removeAt(index) }) {
                    Icon(Icons.Default.Delete, contentDescription = "Remove", tint = MaterialTheme.colorScheme.error)
                }
            }
        }

        Text(stringResource(R.string.adv_wizard_add_sensor), fontSize = 12.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)

        OutlinedTextField(
            value = sensorKey,
            onValueChange = { sensorKey = it },
            label = { Text(stringResource(R.string.adv_wizard_sensor_key)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        SourceFieldDropdown(sourceField) { sourceField = it }

        PlatformDropdown(platform, enabled = dataType != DataType.timestamp) { platform = it }

        payloadHex?.let { hex ->
            Text(stringResource(R.string.adv_wizard_tap_offset), fontSize = 10.sp, color = Color.Gray)
            HexBytePicker(
                hex = hex,
                selectedOffset = offset,
                selectedLength = length,
                onOffsetSelected = { offset = it },
            )
        } ?: Text(stringResource(R.string.adv_wizard_no_payload), color = MaterialTheme.colorScheme.error, fontSize = 11.sp)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = offset.toString(),
                onValueChange = { offset = it.toIntOrNull()?.coerceAtLeast(0) ?: 0 },
                label = { Text(stringResource(R.string.adv_wizard_offset)) },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            if (decodeLengthEditable) {
                OutlinedTextField(
                    value = length.toString(),
                    onValueChange = { length = it.toIntOrNull()?.coerceIn(1, 64) ?: 1 },
                    label = { Text(stringResource(R.string.adv_wizard_decode_length)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            } else {
                OutlinedTextField(
                    value = length.toString(),
                    onValueChange = {},
                    readOnly = true,
                    enabled = false,
                    label = { Text(stringResource(R.string.adv_wizard_decode_length)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            DataTypeDropdown(dataType, onSelected = { dataType = it }, modifier = Modifier.weight(1f))
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
        Text(stringResource(R.string.adv_wizard_min_length_hint), fontSize = 10.sp, color = Color.Gray)
        if (exactLengthText.isNotBlank()) {
            Text(stringResource(R.string.adv_wizard_exact_length_hint), fontSize = 10.sp, color = Color.Gray)
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

        previewDecode?.let {
            Text(
                stringResource(R.string.adv_wizard_preview_value, it.toString()),
                color = MaterialTheme.colorScheme.secondary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
            )
        }

        HassSecondaryButton(
            text = stringResource(R.string.adv_wizard_add_sensor_btn),
            onClick = {
                val scale = scaleText.toDoubleOrNull() ?: 1.0
                val key = sensorKey.trim().ifBlank { "value_${sensors.size + 1}" }
                val effectivePlatform = when {
                    dataType == DataType.string -> "text_sensor"
                    platform == "text_sensor" && dataType != DataType.string -> "sensor"
                    else -> platform
                }
                val parsedMin = minLengthText.toIntOrNull()
                    ?: (offset + length)
                sensors += AdvDeviceBuilder.SensorDraft(
                    key = key,
                    platform = effectivePlatform,
                    sourceField = sourceField,
                    decode = DecodeConfig(
                        offset = offset,
                        length = length,
                        type = dataType,
                        endian = endian,
                        scale = if (isNumericDecode) scale else 1.0,
                    ),
                    deviceClass = deviceClass.takeIf { it.isNotBlank() },
                    unit = if (effectivePlatform == "text_sensor") null else unit.takeIf { it.isNotBlank() },
                    stateClass = if (effectivePlatform == "text_sensor") null else stateClass.takeIf { it.isNotBlank() },
                    accuracyDecimals = if (isNumericDecode && scale != 1.0) 1 else null,
                    minLength = parsedMin,
                    exactLength = exactLengthText.toIntOrNull(),
                )
                sensorKey = "value_${sensors.size + 1}"
                minLengthManual = false
                exactLengthText = ""
            },
            modifier = Modifier.fillMaxWidth(),
        )
        }

        HorizontalDivider(color = Color.White.copy(alpha = 0.1f), modifier = Modifier.padding(vertical = 4.dp))

        HassDialogActionRowWithBack(
            backLabel = stringResource(R.string.adv_wizard_back),
            onBack = onBack,
            primaryLabel = stringResource(R.string.config_add_device_confirm),
            onPrimary = {
                when {
                    deviceId.isBlank() || deviceName.isBlank() ->
                        Toast.makeText(context, context.getString(R.string.adv_wizard_fill_device), Toast.LENGTH_SHORT).show()
                    sensors.isEmpty() ->
                        Toast.makeText(context, context.getString(R.string.adv_wizard_need_sensor), Toast.LENGTH_SHORT).show()
                    else -> onCreate(AdvDeviceBuilder.build(deviceId, deviceName, match, sensors.toList()))
                }
            },
            onCancel = onDismiss,
            primaryEnabled = sensors.isNotEmpty(),
        )
    }
}

@Composable
internal fun MatchChip(text: String) {
    Text(
        text = text,
        fontSize = 10.sp,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HexBytePicker(
    hex: String,
    selectedOffset: Int,
    selectedLength: Int,
    onOffsetSelected: (Int) -> Unit,
) {
    val pairs = hex.chunked(2).filter { it.length == 2 }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 180.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.Black.copy(alpha = 0.3f))
            .verticalScroll(rememberScrollState())
            .padding(8.dp),
    ) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            pairs.forEachIndexed { index, pair ->
                val selected = index in selectedOffset until (selectedOffset + selectedLength)
                Text(
                    text = pair,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = if (selected) Color.Black else Color.LightGray,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent)
                        .clickable { onOffsetSelected(index) }
                        .padding(horizontal = 5.dp, vertical = 4.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SourceFieldDropdown(selected: SourceField, onSelected: (SourceField) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val labels = mapOf(
        SourceField.manufacturer_data to stringResource(R.string.adv_wizard_field_manufacturer),
        SourceField.service_data to stringResource(R.string.adv_wizard_field_service),
        SourceField.raw to stringResource(R.string.adv_wizard_field_raw),
    )
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = labels[selected] ?: selected.name,
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.adv_wizard_source_field)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            SourceField.entries.forEach { field ->
                DropdownMenuItem(
                    text = { Text(labels[field] ?: field.name) },
                    onClick = { onSelected(field); expanded = false },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlatformDropdown(
    selected: String,
    enabled: Boolean = true,
    onSelected: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val options = listOf("sensor" to R.string.adv_wizard_platform_sensor, "text_sensor" to R.string.adv_wizard_platform_text)
    ExposedDropdownMenuBox(expanded = expanded && enabled, onExpandedChange = { if (enabled) expanded = it }) {
        OutlinedTextField(
            value = stringResource(
                options.firstOrNull { it.first == selected }?.second ?: R.string.adv_wizard_platform_sensor,
            ),
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = { Text(stringResource(R.string.adv_wizard_platform)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (value, labelRes) ->
                DropdownMenuItem(
                    text = { Text(stringResource(labelRes)) },
                    onClick = { onSelected(value); expanded = false },
                )
            }
        }
    }
}

internal fun defaultDecodeLength(type: DataType): Int = when (type) {
    DataType.timestamp -> 4
    DataType.uint8, DataType.int8 -> 1
    DataType.int16, DataType.uint16 -> 2
    DataType.int32, DataType.uint32, DataType.float32 -> 4
    DataType.string -> 2
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DataTypeDropdown(
    selected: DataType,
    onSelected: (DataType) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val options = listOf(
        DataType.uint8,
        DataType.int8,
        DataType.int16,
        DataType.uint16,
        DataType.int32,
        DataType.uint32,
        DataType.float32,
        DataType.timestamp,
        DataType.string,
    )
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }, modifier = modifier) {
        OutlinedTextField(
            value = selected.name,
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.adv_wizard_type)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { t ->
                DropdownMenuItem(text = { Text(t.name) }, onClick = { onSelected(t); expanded = false })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EndianDropdown(
    selected: Endian,
    onSelected: (Endian) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }, modifier = modifier) {
        OutlinedTextField(
            value = selected.name,
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.adv_wizard_endian)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            Endian.entries.forEach { e ->
                DropdownMenuItem(text = { Text(e.name) }, onClick = { onSelected(e); expanded = false })
            }
        }
    }
}

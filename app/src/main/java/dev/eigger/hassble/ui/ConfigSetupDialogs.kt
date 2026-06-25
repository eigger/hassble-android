package dev.eigger.hassble.ui

import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import dev.eigger.hassble.R
import dev.eigger.hassble.config.ConfigTemplate
import dev.eigger.hassble.config.ConfigTemplates
import dev.eigger.hassble.config.ConfigTemplatesLoader
import dev.eigger.hassble.config.ConfigYamlWriter
import dev.eigger.hassble.config.DeviceConfig
import dev.eigger.hassble.config.GatewayConfig
import dev.eigger.hassble.config.ObdDeviceBuilder
import dev.eigger.hassble.config.ObdPresetStore

@Composable
fun ConfigToolsRow(
    onImportTemplate: () -> Unit,
    onAddObd: () -> Unit,
    onAddAdv: () -> Unit,
    onExportYaml: () -> Unit,
    draftDeviceCount: Int,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ConfigToolButton(
                label = stringResource(R.string.config_import_template),
                onClick = onImportTemplate,
                modifier = Modifier.weight(1f),
            )
            ConfigToolButton(
                label = stringResource(R.string.config_add_obd),
                onClick = onAddObd,
                modifier = Modifier.weight(1f),
            )
            ConfigToolButton(
                label = stringResource(R.string.config_add_adv),
                onClick = onAddAdv,
                modifier = Modifier.weight(1f),
            )
        }
        ConfigToolButton(
            label = stringResource(R.string.config_export_yaml),
            onClick = onExportYaml,
            modifier = Modifier.fillMaxWidth(),
        )
        if (draftDeviceCount > 0) {
            DraftDeviceBanner(
                draftDeviceCount = draftDeviceCount,
                onExportYaml = onExportYaml,
            )
        }
    }
}

@Composable
private fun DraftDeviceBanner(
    draftDeviceCount: Int,
    onExportYaml: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(R.string.config_draft_banner, draftDeviceCount),
                color = Color.White,
                fontSize = 11.sp,
                modifier = Modifier.weight(1f),
            )
            HassLinkButton(
                text = stringResource(R.string.config_draft_export_btn),
                onClick = onExportYaml,
            )
        }
    }
}

@Composable
private fun ConfigToolButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    HassSecondaryButton(
        text = label,
        onClick = onClick,
        modifier = modifier,
        compact = true,
    )
}

@Composable
private fun FullscreenSetupDialog(
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.96f)
                .fillMaxHeight(0.92f),
            shape = HassBleShapes.Dialog,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            content()
        }
    }
}

@Composable
fun TemplateImportDialog(
    templates: ConfigTemplates,
    isLoading: Boolean = false,
    source: ConfigTemplatesLoader.Source = ConfigTemplatesLoader.Source.BUNDLED,
    templatesUrl: String? = null,
    onRefresh: () -> Unit = {},
    onDismiss: () -> Unit,
    onImport: (ConfigTemplate) -> Unit,
) {
    val sourceLabel = when (source) {
        ConfigTemplatesLoader.Source.REMOTE -> stringResource(R.string.config_templates_source_remote)
        ConfigTemplatesLoader.Source.CACHE -> stringResource(R.string.config_templates_source_cache)
        ConfigTemplatesLoader.Source.BUNDLED -> stringResource(R.string.config_templates_source_builtin)
    }

    FullscreenSetupDialog(onDismiss = onDismiss) {
        Column(
            modifier = Modifier.fillMaxSize().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.config_import_template),
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        color = Color.White,
                    )
                    Text(
                        sourceLabel,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            templatesUrl?.let {
                Text(
                    text = it,
                    fontSize = 10.sp,
                    color = Color.Gray,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    fontFamily = FontFamily.Monospace,
                )
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.Black.copy(alpha = 0.2f))
                    .padding(8.dp),
            ) {
                when {
                    isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    }
                    templates.all().isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            stringResource(R.string.config_templates_empty),
                            fontSize = 14.sp,
                            color = Color.Gray,
                        )
                    }
                    else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(templates.all(), key = { it.id }) { template ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onImport(template) },
                                colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.06f)),
                            ) {
                                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(template.name, fontWeight = FontWeight.SemiBold, color = Color.White, fontSize = 15.sp)
                                    if (template.description.isNotBlank()) {
                                        Text(template.description, fontSize = 12.sp, color = Color.Gray)
                                    }
                                    Text(
                                        text = "${template.device.source} · ${template.device.sensors.size} sensors",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (templatesUrl != null) {
                    HassLinkButton(
                        text = stringResource(R.string.config_templates_refresh),
                        onClick = onRefresh,
                        enabled = !isLoading,
                    )
                } else {
                    Box(Modifier)
                }
                HassCancelButton(onClick = onDismiss)
            }
        }
    }
}

@Composable
fun AddObdDeviceDialog(
    presets: ObdPresetStore,
    onDismiss: () -> Unit,
    onCreate: (DeviceConfig) -> Unit,
) {
    var deviceId by remember { mutableStateOf("obd_vehicle") }
    var deviceName by remember { mutableStateOf("") }
    val presetNames = remember(presets) { presets.presetNames() }
    var selected by remember {
        mutableStateOf(setOf("rpm", "speed", "coolant_temp", "fuel_level"))
    }

    FullscreenSetupDialog(onDismiss = onDismiss) {
        Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
            Text(
                stringResource(R.string.config_add_obd),
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                color = Color.White,
            )
            Text(
                stringResource(R.string.config_add_obd_hint),
                fontSize = 12.sp,
                color = Color.Gray,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
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
                    placeholder = { Text(stringResource(R.string.config_device_name_obd_hint), color = Color.Gray) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
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

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                HassSecondaryButton(
                    text = stringResource(R.string.config_add_device_confirm),
                    onClick = {
                        val name = deviceName.ifBlank { deviceId }
                        onCreate(
                            ObdDeviceBuilder.create(
                                id = deviceId,
                                name = name,
                                presetKeys = selected.sorted(),
                            ),
                        )
                    },
                    enabled = deviceId.isNotBlank() && selected.isNotEmpty(),
                )
                HassCancelButton(onClick = onDismiss)
            }
        }
    }
}

@Composable
fun ExportYamlDialog(
    mergedConfig: GatewayConfig?,
    draftDevices: List<DeviceConfig>,
    gitRepoInput: String = "",
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val localCount = draftDevices.size
    var exportLocalOnly by remember(localCount) {
        mutableStateOf(localCount > 0)
    }
    val activeConfig = remember(mergedConfig, draftDevices, exportLocalOnly) {
        when {
            exportLocalOnly && draftDevices.isNotEmpty() -> GatewayConfig(devices = draftDevices)
            else -> mergedConfig
        }
    }
    val yaml = remember(activeConfig) {
        activeConfig?.let { runCatching { ConfigYamlWriter.encode(it) }.getOrNull() }
    }
    val deviceCount = activeConfig?.devices?.size ?: 0

    FullscreenSetupDialog(onDismiss = onDismiss) {
        Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
            Text(
                stringResource(R.string.config_export_yaml),
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                color = Color.White,
            )
            Text(
                text = when {
                    activeConfig == null || deviceCount == 0 ->
                        stringResource(R.string.config_export_empty)
                    exportLocalOnly ->
                        stringResource(R.string.config_export_summary, deviceCount)
                    localCount > 0 ->
                        stringResource(
                            R.string.config_export_summary_with_local,
                            deviceCount,
                            localCount,
                        )
                    else -> stringResource(R.string.config_export_summary, deviceCount)
                },
                fontSize = 12.sp,
                color = Color.Gray,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
            )

            if (localCount > 0 && (mergedConfig?.devices?.isNotEmpty() == true)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    HassToggleChip(
                        label = stringResource(R.string.config_export_mode_all),
                        selected = !exportLocalOnly,
                        onClick = { exportLocalOnly = false },
                        modifier = Modifier.weight(1f),
                    )
                    HassToggleChip(
                        label = stringResource(R.string.config_export_mode_local),
                        selected = exportLocalOnly,
                        onClick = { exportLocalOnly = true },
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            if (localCount > 0 && gitRepoInput.isNotBlank()) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.config_export_next_step_git),
                            color = Color.White,
                            fontSize = 11.sp,
                            modifier = Modifier.weight(1f),
                        )
                        HassLinkButton(
                            text = stringResource(R.string.config_export_open_repo),
                            onClick = {
                                val repoPath = gitRepoInput.trim()
                                    .removePrefix("https://github.com/")
                                    .removePrefix("github.com/")
                                    .trim('/')
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/$repoPath")),
                                )
                            },
                        )
                    }
                }
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.Black.copy(alpha = 0.2f))
                    .padding(12.dp),
            ) {
                if (yaml.isNullOrBlank()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.config_export_empty), color = Color.Gray, fontSize = 14.sp)
                    }
                } else {
                    Text(
                        text = yaml,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = Color.LightGray,
                        lineHeight = 16.sp,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(end = 28.dp)
                            .verticalScroll(rememberScrollState()),
                    )
                    HassCopyIconButton(
                        onClick = { copyTextToClipboard(context, yaml, "yaml") },
                        modifier = Modifier.align(Alignment.TopEnd),
                    )
                }
            }

            HassDialogSecondaryActionRow(
                modifier = Modifier.padding(top = 12.dp),
                onCancel = onDismiss,
            ) {
                HassSecondaryButton(
                    text = stringResource(R.string.logs_save),
                    onClick = { activeConfig?.let { saveConfigYamlToDownloads(context, it) } },
                    enabled = !yaml.isNullOrBlank(),
                    modifier = Modifier.weight(1f),
                    compact = true,
                )
                HassSecondaryButton(
                    text = stringResource(R.string.logs_share),
                    onClick = { activeConfig?.let { shareConfigYaml(context, it) } },
                    enabled = !yaml.isNullOrBlank(),
                    modifier = Modifier.weight(1f),
                    compact = true,
                )
            }
        }
    }
}

private fun configYamlFileName(): String = "hassble_config_${System.currentTimeMillis()}.yaml"

fun shareConfigYaml(context: Context, config: GatewayConfig) {
    if (config.devices.isEmpty()) {
        Toast.makeText(context, context.getString(R.string.config_export_empty), Toast.LENGTH_SHORT).show()
        return
    }
    try {
        val yaml = ConfigYamlWriter.encode(config)
        val fileName = configYamlFileName()
        val file = java.io.File(context.cacheDir, fileName)
        file.writeText(yaml)
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
        val chooser = Intent.createChooser(sendIntent, context.getString(R.string.config_export_yaml)).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(chooser)
    } catch (e: Exception) {
        Toast.makeText(context, context.getString(R.string.config_export_failed, e.message), Toast.LENGTH_LONG).show()
    }
}

fun saveConfigYamlToDownloads(context: Context, config: GatewayConfig) {
    if (config.devices.isEmpty()) {
        Toast.makeText(context, context.getString(R.string.config_export_empty), Toast.LENGTH_SHORT).show()
        return
    }
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
        Toast.makeText(context, context.getString(R.string.logs_save_unsupported), Toast.LENGTH_LONG).show()
        return
    }
    val yaml = ConfigYamlWriter.encode(config)
    val fileName = configYamlFileName()
    try {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, "text/yaml")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("insert failed")
        resolver.openOutputStream(uri)?.use { stream ->
            stream.write(yaml.toByteArray(Charsets.UTF_8))
        } ?: throw IllegalStateException("openOutputStream failed")
        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        Toast.makeText(
            context,
            context.getString(R.string.config_export_saved_to_downloads, fileName),
            Toast.LENGTH_LONG,
        ).show()
    } catch (e: Exception) {
        Toast.makeText(context, context.getString(R.string.config_export_save_failed, e.message), Toast.LENGTH_LONG).show()
    }
}

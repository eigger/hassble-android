package dev.eigger.hassble.ui

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.eigger.hassble.R
import dev.eigger.hassble.config.ConfigCatalog
import dev.eigger.hassble.config.ConfigTemplate
import dev.eigger.hassble.config.ConfigYamlWriter
import dev.eigger.hassble.config.DeviceConfig
import dev.eigger.hassble.config.GatewayConfig
import dev.eigger.hassble.config.ObdDeviceBuilder
import dev.eigger.hassble.config.ObdPresetStore

@Composable
fun ConfigToolsRow(
    onImportCatalog: () -> Unit,
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
                label = stringResource(R.string.config_import_catalog),
                onClick = onImportCatalog,
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
    }
    if (draftDeviceCount > 0) {
        Text(
            text = stringResource(R.string.config_draft_devices_summary, draftDeviceCount),
            color = MaterialTheme.colorScheme.primary,
            fontSize = 11.sp,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun ConfigToolButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.White.copy(alpha = 0.08f),
            contentColor = Color.White,
        ),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(label, fontSize = 11.sp, maxLines = 2)
    }
}

@Composable
fun CatalogImportDialog(
    catalog: ConfigCatalog,
    onDismiss: () -> Unit,
    onImport: (ConfigTemplate) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.config_import_catalog)) },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                items(catalog.all()) { template ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clickable { onImport(template) },
                        colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.06f)),
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(template.name, fontWeight = FontWeight.SemiBold, color = Color.White)
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
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
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

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.config_add_obd)) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
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
                Text(stringResource(R.string.config_obd_presets_label), fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                presetNames.forEach { preset ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                selected = if (preset in selected) selected - preset else selected + preset
                            },
                    ) {
                        Checkbox(
                            checked = preset in selected,
                            onCheckedChange = { checked ->
                                selected = if (checked) selected + preset else selected - preset
                            },
                        )
                        Text(preset, fontSize = 13.sp)
                    }
                }
            }
        },
        confirmButton = {
            Button(
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
            ) {
                Text(stringResource(R.string.config_add_device_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

fun exportConfigYaml(context: Context, config: GatewayConfig) {
    try {
        val yaml = ConfigYamlWriter.encode(config)
        val fileName = "hassble_config_${System.currentTimeMillis()}.yaml"
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

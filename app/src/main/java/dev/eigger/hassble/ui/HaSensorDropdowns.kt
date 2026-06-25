package dev.eigger.hassble.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.eigger.hassble.R
import dev.eigger.hassble.config.DataType
import dev.eigger.hassble.config.HaSensorOptions

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HaOptionalDropdown(
    label: String,
    selected: String,
    options: List<String>,
    onSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    var expanded by remember { mutableStateOf(false) }
    val noneLabel = stringResource(R.string.ha_option_none)
    val displayValue = selected.ifBlank { noneLabel }

    ExposedDropdownMenuBox(
        expanded = expanded && enabled,
        onExpandedChange = { if (enabled) expanded = it },
        modifier = modifier,
    ) {
        OutlinedTextField(
            value = displayValue,
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(noneLabel) },
                onClick = {
                    onSelected("")
                    expanded = false
                },
            )
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = {
                        onSelected(option)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
internal fun HaDeviceClassDropdown(
    selected: String,
    dataType: DataType,
    platform: String,
    onSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val options = remember(dataType, platform, selected) {
        HaSensorOptions.deviceClassOptions(dataType, platform, selected)
    }
    if (options.isEmpty()) return

    HaOptionalDropdown(
        label = stringResource(R.string.adv_wizard_device_class),
        selected = selected,
        options = options,
        onSelected = onSelected,
        modifier = modifier,
    )
}

@Composable
internal fun HaStateClassDropdown(
    selected: String,
    dataType: DataType,
    platform: String,
    onSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val options = remember(dataType, platform, selected) {
        HaSensorOptions.stateClassOptions(dataType, platform, selected)
    }
    if (options.isEmpty()) return

    HaOptionalDropdown(
        label = stringResource(R.string.adv_wizard_state_class),
        selected = selected,
        options = options,
        onSelected = onSelected,
        modifier = modifier,
    )
}

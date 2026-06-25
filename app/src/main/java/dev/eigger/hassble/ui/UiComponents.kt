package dev.eigger.hassble.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.eigger.hassble.R
import dev.eigger.hassble.ble.DeviceLinkState
import dev.eigger.hassble.ble.DeviceLinkStatus
import dev.eigger.hassble.config.ControlType
import dev.eigger.hassble.config.SensorConfig
import dev.eigger.hassble.net.ConnectionIssue

fun copyTextToClipboard(context: Context, text: String, label: String = "text") {
    if (text.isBlank()) return
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
    Toast.makeText(context, context.getString(R.string.logs_copied), Toast.LENGTH_SHORT).show()
}

@Composable
fun HassCopyIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentDescription: String = stringResource(R.string.action_copy),
) {
    IconButton(
        onClick = onClick,
        modifier = modifier.size(32.dp),
        enabled = enabled,
    ) {
        Icon(
            imageVector = Icons.Default.ContentCopy,
            contentDescription = contentDescription,
            tint = if (enabled) MaterialTheme.colorScheme.primary else Color.Gray,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
fun WarningBanner(text: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.1f)),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.3f)),
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
            Text(
                text = text,
                color = MaterialTheme.colorScheme.error,
                fontSize = 12.sp,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

@Composable
fun InfoBanner(text: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFF9100).copy(alpha = 0.12f)),
        border = BorderStroke(1.dp, Color(0xFFFF9100).copy(alpha = 0.3f)),
        shape = RoundedCornerShape(12.dp),
    ) {
        Text(
            text = text,
            color = Color(0xFFFF9100),
            fontSize = 12.sp,
            modifier = Modifier.padding(12.dp),
        )
    }
}

@Composable
fun PermissionBanner(missingCount: Int) {
    if (missingCount == 0) return
    val context = LocalContext.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.1f)),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.permissions_missing_title), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Text(stringResource(R.string.permissions_missing_desc), color = Color.Gray, fontSize = 12.sp)
            HassPrimaryButton(
                text = stringResource(R.string.permissions_open_settings),
                onClick = {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.parse("package:${context.packageName}")
                        },
                    )
                },
            )
        }
    }
}

@Composable
fun sensorDisplayLabel(sensor: SensorConfig): String {
    val keyLabel = sensor.key.replace('_', ' ').replaceFirstChar { it.uppercase() }
    return if (!sensor.unit.isNullOrBlank()) "$keyLabel (${sensor.unit})" else keyLabel
}

@Composable
fun linkStateLabel(state: DeviceLinkState): String = when (state) {
    DeviceLinkState.Scanning -> stringResource(R.string.link_state_scanning)
    DeviceLinkState.Connecting -> stringResource(R.string.link_state_connecting)
    DeviceLinkState.Connected -> stringResource(R.string.link_state_connected)
    DeviceLinkState.Polling -> stringResource(R.string.link_state_polling)
    DeviceLinkState.Error -> stringResource(R.string.link_state_error)
    DeviceLinkState.Disconnected -> stringResource(R.string.status_disconnected)
}

@Composable
fun controlTypeLabel(type: ControlType): String = when (type) {
    ControlType.switch -> stringResource(R.string.control_type_switch)
    ControlType.number -> stringResource(R.string.control_type_number)
    ControlType.select -> stringResource(R.string.control_type_select)
    ControlType.button -> stringResource(R.string.control_type_button)
}

@Composable
fun connectionIssueMessage(issue: ConnectionIssue): String? = when (issue) {
    ConnectionIssue.AuthFailed -> stringResource(R.string.connection_issue_auth)
    ConnectionIssue.NetworkError -> stringResource(R.string.connection_issue_network)
    ConnectionIssue.BridgeNotResponding -> stringResource(R.string.connection_issue_bridge)
    ConnectionIssue.None -> null
}

@Composable
fun lastSeenText(lastSeenMs: Long): String {
    val diffSec = (System.currentTimeMillis() - lastSeenMs) / 1000
    return when {
        diffSec < 5 -> stringResource(R.string.last_seen_just_now)
        diffSec < 60 -> stringResource(R.string.last_seen_seconds, diffSec.toInt())
        diffSec < 3600 -> stringResource(R.string.last_seen_minutes, (diffSec / 60).toInt())
        else -> stringResource(R.string.last_seen_hours, (diffSec / 3600).toInt())
    }
}

@Composable
fun DeviceLinkStatusRow(link: DeviceLinkStatus) {
    Column(modifier = Modifier.padding(top = 4.dp)) {
        Text(
            text = linkStateLabel(link.state),
            color = when (link.state) {
                DeviceLinkState.Error -> MaterialTheme.colorScheme.error
                DeviceLinkState.Polling, DeviceLinkState.Connected -> Color(0xFF00E676)
                DeviceLinkState.Connecting, DeviceLinkState.Scanning -> Color(0xFFFFD600)
                DeviceLinkState.Disconnected -> Color.Gray
            },
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
        )
        link.lastDataMs?.let { ts ->
            Text(
                text = stringResource(R.string.link_last_data, lastSeenText(ts)),
                color = Color.Gray,
                fontSize = 10.sp,
            )
        }
        link.errorMessage?.let { err ->
            Text(text = err, color = MaterialTheme.colorScheme.error, fontSize = 10.sp)
        }
    }
}

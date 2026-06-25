package dev.eigger.hassble.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import dev.eigger.hassble.R

@Composable
fun OnboardingDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = HassBleShapes.CardLarge,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Column(
                modifier = Modifier.padding(24.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(R.string.onboarding_title),
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp,
                    color = Color.White,
                )
                OnboardingStep(number = 1, text = stringResource(R.string.onboarding_step_ws_bridge))
                val wsBridgeUrl = stringResource(R.string.ws_bridge_url)
                HassLinkButton(
                    text = stringResource(R.string.open_ws_bridge_repo),
                    onClick = {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(wsBridgeUrl)))
                    },
                )
                OnboardingStep(number = 2, text = stringResource(R.string.onboarding_step_token))
                OnboardingStep(number = 3, text = stringResource(R.string.onboarding_step_git))
                OnboardingStep(number = 4, text = stringResource(R.string.onboarding_step_sensors))
                Spacer(modifier = Modifier.height(8.dp))
                HassPrimaryButton(
                    text = stringResource(R.string.onboarding_done),
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                    large = true,
                )
            }
        }
    }
}

@Composable
private fun OnboardingStep(number: Int, text: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "$number.",
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp,
            modifier = Modifier.padding(end = 8.dp),
        )
        Text(text = text, color = Color.LightGray, fontSize = 14.sp)
    }
}

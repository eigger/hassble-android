package dev.eigger.hassble.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dev.eigger.hassble.config.HassSettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "HassBleBootReceiver"
internal const val BOOT_RECEIVER_TIMEOUT_MS = 10_000L

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            Log.d(TAG, "Received action: $action, restoring HassBle background service...")
            val pendingResult = goAsync()
            val repository = HassSettingsRepository(context.applicationContext)

            CoroutineScope(Dispatchers.IO).launch {
                try {
                    withTimeoutOrNull(BOOT_RECEIVER_TIMEOUT_MS) {
                        val startOnBoot = repository.startOnBoot.first()
                        if (startOnBoot) {
                            val url = repository.haUrl.first()
                            val token = repository.haToken.first()
                            val refreshToken = repository.haRefreshToken.first()
                            val gitUrl = repository.gitUrl.first()
                            val gitToken = repository.gitToken.first()

                            if (url.isNotBlank() && token.isNotBlank() && gitUrl.isNotBlank()) {
                                Log.d(TAG, "Settings found, starting BleGatewayService")
                                BleGatewayService.start(context, url, token, refreshToken, gitUrl, gitToken)
                            } else {
                                Log.d(TAG, "HassBle settings are incomplete, skipping auto-start")
                            }
                        } else {
                            Log.d(TAG, "Start on boot is disabled by user")
                        }
                    } ?: Log.w(TAG, "Timed out (${BOOT_RECEIVER_TIMEOUT_MS}ms) restoring service on $action")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to restore service on $action", e)
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }
}

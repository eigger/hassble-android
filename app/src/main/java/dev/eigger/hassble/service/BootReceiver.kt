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

private const val TAG = "HassBleBootReceiver"

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.d(TAG, "Device booted, starting HassBle background service...")
            val repository = HassSettingsRepository(context.applicationContext)
            
            CoroutineScope(Dispatchers.IO).launch {
                val startOnBoot = repository.startOnBoot.first()
                if (startOnBoot) {
                    val url = repository.haUrl.first()
                    val token = repository.haToken.first()
                    val gitUrl = repository.gitUrl.first()
                    val gitToken = repository.gitToken.first()
                    
                    if (url.isNotBlank() && token.isNotBlank() && gitUrl.isNotBlank()) {
                        Log.d(TAG, "Settings found, starting BleGatewayService")
                        BleGatewayService.start(context, url, token, gitUrl, gitToken)
                    } else {
                        Log.d(TAG, "HassBle settings are incomplete, skipping auto-start")
                    }
                } else {
                    Log.d(TAG, "Start on boot is disabled by user")
                }
            }
        }
    }
}

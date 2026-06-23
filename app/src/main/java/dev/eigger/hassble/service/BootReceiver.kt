package dev.eigger.hassble.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dev.eigger.hassble.config.HassSettingsRepository
import dev.eigger.hassble.net.HaAuthHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private const val TAG = "HassBleBootReceiver"
private const val TOKEN_EXPIRY_MS = 25 * 60 * 1000L // 25분 (HA OAuth 수명 30분보다 보수적)

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.d(TAG, "Device booted, starting HassBle background service...")
            val repository = HassSettingsRepository(context.applicationContext)

            CoroutineScope(Dispatchers.IO).launch {
                val startOnBoot = repository.startOnBoot.first()
                if (!startOnBoot) {
                    Log.d(TAG, "Start on boot is disabled by user")
                    return@launch
                }

                val url = repository.haUrl.first()
                val refreshToken = repository.haRefreshToken.first()
                val gitUrl = repository.gitUrl.first()
                val gitToken = repository.gitToken.first()

                if (url.isBlank() || gitUrl.isBlank()) {
                    Log.d(TAG, "HassBle settings are incomplete, skipping auto-start")
                    return@launch
                }

                // 토큰 만료 여부 확인 — 만료됐으면 연결 전에 미리 갱신해 HA ban 알림 방지
                var token = repository.haToken.first()
                if (token.isBlank()) {
                    Log.d(TAG, "HassBle settings are incomplete, skipping auto-start")
                    return@launch
                }

                if (refreshToken.isNotBlank()) {
                    val lastRefreshed = repository.haTokenLastRefreshed.first()
                    val tokenAge = System.currentTimeMillis() - lastRefreshed
                    if (tokenAge > TOKEN_EXPIRY_MS) {
                        Log.d(TAG, "Access token may be expired (age: ${tokenAge / 1000}s), refreshing before connect...")
                        val result = HaAuthHelper.refreshAccessToken(url, refreshToken)
                        if (result.isSuccess) {
                            token = result.getOrThrow()
                            repository.saveHaSettings(url, token)
                            repository.saveHaTokenLastRefreshed(System.currentTimeMillis())
                            Log.d(TAG, "Token refreshed successfully before boot connect")
                        } else {
                            Log.w(TAG, "Token refresh failed, will retry after auth_invalid: ${result.exceptionOrNull()?.message}")
                        }
                    }
                }

                Log.d(TAG, "Settings found, starting BleGatewayService")
                BleGatewayService.start(context, url, token, refreshToken, gitUrl, gitToken)
            }
        }
    }
}

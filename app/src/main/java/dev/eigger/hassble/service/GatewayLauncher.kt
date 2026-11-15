package dev.eigger.hassble.service

import android.content.Context
import dev.eigger.hassble.config.HassSettingsRepository
import kotlinx.coroutines.flow.first

internal sealed interface SavedStartOutcome {
    data object Started : SavedStartOutcome
    data object SettingsIncomplete : SavedStartOutcome
    data class Failed(val error: Throwable) : SavedStartOutcome
}

/**
 * 저장된 설정으로 게이트웨이를 시작한다(BootReceiver·AutomationReceiver 공용).
 * HA URL/토큰은 항상 저장소에서만 읽는다 — 외부 intent extra로는 받지 않는다.
 */
internal suspend fun startGatewayFromSavedSettings(context: Context, repository: HassSettingsRepository): SavedStartOutcome {
    val url = repository.haUrl.first()
    val token = repository.haToken.first()
    val refreshToken = repository.haRefreshToken.first()
    val gitUrl = repository.gitUrl.first()
    val gitToken = repository.gitToken.first()
    if (url.isBlank() || token.isBlank() || gitUrl.isBlank()) return SavedStartOutcome.SettingsIncomplete
    val error = BleGatewayService.start(context, url, token, refreshToken, gitUrl, gitToken)
    return if (error == null) SavedStartOutcome.Started else SavedStartOutcome.Failed(error)
}

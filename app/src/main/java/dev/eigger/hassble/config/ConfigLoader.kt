package dev.eigger.hassble.config

import com.charleskorn.kaml.Yaml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

/**
 * git raw URL에서 설정을 가져와 파싱하고 preset을 펼친다.
 *  - 네트워크/파싱 실패 시 [cacheFile]의 마지막 유효본 폴백
 */
class ConfigLoader(
    private val cacheFile: File,
    private val presets: ObdPresetStore,
    private val http: OkHttpClient = OkHttpClient(),
) {
    suspend fun load(url: String, token: String? = null): Result<GatewayConfig> =
        withContext(Dispatchers.IO) {
            runCatching {
                val req = Request.Builder().url(url).apply {
                    if (token != null) header("Authorization", "Bearer $token")
                }.build()
                http.newCall(req).execute().use { resp ->
                    check(resp.isSuccessful) { "HTTP ${resp.code}" }
                    val text = resp.body!!.string()
                    val config = parse(text)
                    cacheFile.writeText(text)
                    config
                }
            }.recoverCatching { e ->
                loadCache() ?: throw e
            }
        }

    fun loadCache(): GatewayConfig? =
        cacheFile.takeIf { it.exists() }?.let { parse(it.readText()) }

    private fun parse(text: String): GatewayConfig =
        presets.expand(Yaml.default.decodeFromString(GatewayConfig.serializer(), text))
}

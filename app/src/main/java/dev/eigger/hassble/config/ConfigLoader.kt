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
                val normalizedUrl = normalizeUrl(url)
                val req = Request.Builder().url(normalizedUrl).apply {
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

    private fun normalizeUrl(url: String): String {
        val trimmed = url.trim().removeSuffix("/")
        if (trimmed.isEmpty()) return trimmed

        val githubPrefix = "https://github.com/"
        val githubHttpPrefix = "http://github.com/"

        val prefix = when {
            trimmed.startsWith(githubPrefix, ignoreCase = true) -> githubPrefix
            trimmed.startsWith(githubHttpPrefix, ignoreCase = true) -> githubHttpPrefix
            else -> null
        }

        if (prefix != null) {
            val parts = trimmed.substring(prefix.length).split("/")
            if (parts.size >= 2) {
                val user = parts[0]
                val repo = parts[1]
                return when {
                    parts.size == 2 -> {
                        "https://raw.githubusercontent.com/$user/$repo/main/config.yaml"
                    }
                    parts.size >= 4 && parts[2].lowercase() == "blob" -> {
                        val branch = parts[3]
                        val path = parts.drop(4).joinToString("/")
                        "https://raw.githubusercontent.com/$user/$repo/$branch/$path"
                    }
                    else -> trimmed
                }
            }
        }
        return trimmed
    }
}

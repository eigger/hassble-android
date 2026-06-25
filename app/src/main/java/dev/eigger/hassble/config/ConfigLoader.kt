package dev.eigger.hassble.config

import com.charleskorn.kaml.Yaml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

/**
 * git / raw URL에서 설정을 가져와 파싱하고 preset을 펼친다.
 * URL에 포함된 파일 경로(config.yaml 등)를 그대로 사용한다.
 *  - 네트워크/파싱 실패 시 해당 URL 전용 캐시 파일로 폴백
 */
class ConfigLoader(
    private val cacheDir: File,
    private val presets: ObdPresetStore,
    private val http: OkHttpClient = OkHttpClient(),
) {
    init {
        cacheDir.mkdirs()
    }

    suspend fun load(url: String, token: String? = null): Result<GatewayConfig> =
        withContext(Dispatchers.IO) {
            val normalizedUrl = normalizeUrl(url)
            runCatching {
                val cacheFile = cacheFileFor(normalizedUrl)
                val req = Request.Builder().url(normalizedUrl).apply {
                    if (token != null) header("Authorization", "Bearer $token")
                }.build()
                http.newCall(req).execute().use { resp ->
                    check(resp.isSuccessful) { "HTTP ${resp.code}" }
                    val text = resp.body!!.string()
                    val config = parse(text)
                    writeCache(cacheFile, text)
                    config
                }
            }.recoverCatching { e ->
                loadCache(url) ?: throw e
            }
        }

    fun loadCache(url: String): GatewayConfig? {
        val cacheFile = cacheFileFor(normalizeUrl(url))
        return cacheFile.takeIf { it.exists() }?.let { parse(it.readText()) }
    }

    fun cacheSavedAt(url: String): Long? {
        val meta = cacheMetaFile(cacheFileFor(normalizeUrl(url)))
        return meta.takeIf { it.exists() }?.readText()?.toLongOrNull()
    }

    private fun writeCache(cacheFile: File, text: String) {
        cacheFile.writeText(text)
        cacheMetaFile(cacheFile).writeText(System.currentTimeMillis().toString())
    }

    private fun cacheMetaFile(cacheFile: File): File =
        File(cacheFile.parent, "${cacheFile.name}.meta")

    private fun parse(text: String): GatewayConfig =
        presets.expand(Yaml.default.decodeFromString(GatewayConfig.serializer(), text))

    private fun cacheFileFor(normalizedUrl: String): File {
        val leaf = normalizedUrl.substringAfterLast('/')
            .takeIf { it.contains('.') } ?: "config.yaml"
        val safe = leaf.replace(Regex("""[^\w.\-]"""), "_")
        return File(cacheDir, safe)
    }

    /**
     * GitHub / raw URL을 raw.githubusercontent.com 주소로 정규화한다.
     * - raw URL: 그대로 사용
     * - repo만: main/config.yaml
     * - blob/tree/직접 경로: URL에 있는 파일 경로 사용
     */
    fun normalizeUrl(url: String): String {
        val trimmed = url.trim().removeSuffix("/")
        if (trimmed.isEmpty()) return trimmed

        if (trimmed.contains("raw.githubusercontent.com", ignoreCase = true)) {
            return trimmed
        }

        val githubPrefix = "https://github.com/"
        val githubHttpPrefix = "http://github.com/"
        val prefix = when {
            trimmed.startsWith(githubPrefix, ignoreCase = true) -> githubPrefix
            trimmed.startsWith(githubHttpPrefix, ignoreCase = true) -> githubHttpPrefix
            else -> return trimmed
        }

        val parts = trimmed.substring(prefix.length).split("/").filter { it.isNotEmpty() }
        if (parts.size < 2) return trimmed

        val user = parts[0]
        val repo = parts[1]

        return when {
            parts.size == 2 -> {
                "https://raw.githubusercontent.com/$user/$repo/main/config.yaml"
            }
            parts[2].equals("blob", ignoreCase = true) && parts.size >= 5 -> {
                val branch = parts[3]
                val path = parts.drop(4).joinToString("/")
                "https://raw.githubusercontent.com/$user/$repo/$branch/$path"
            }
            parts[2].equals("tree", ignoreCase = true) && parts.size >= 4 -> {
                val branch = parts[3]
                val path = parts.drop(4).joinToString("/").ifBlank { "config.yaml" }
                "https://raw.githubusercontent.com/$user/$repo/$branch/$path"
            }
            parts.size >= 3 && isYamlFile(parts.last()) -> {
                val path = parts.drop(2).joinToString("/")
                "https://raw.githubusercontent.com/$user/$repo/$path"
            }
            else -> trimmed
        }
    }

    private fun isYamlFile(name: String) =
        name.endsWith(".yaml", ignoreCase = true) || name.endsWith(".yml", ignoreCase = true)
}

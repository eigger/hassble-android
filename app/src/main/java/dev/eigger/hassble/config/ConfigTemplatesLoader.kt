package dev.eigger.hassble.config

import dev.eigger.hassble.config.HassBleDefaults
import dev.eigger.hassble.net.GitHubHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

/**
 * Git 설정과 같은 저장소/브랜치의 [templates.yaml]을 가져온다.
 * 네트워크 실패 시 캐시 → 앱 내장 [templates.yaml] 순으로 폴백한다.
 */
class ConfigTemplatesLoader(
    private val cacheDir: File,
    private val bundled: ConfigTemplates,
    private val configLoader: ConfigLoader,
    private val http: OkHttpClient = OkHttpClient(),
) {
    enum class Source { REMOTE, CACHE, BUNDLED }

    data class LoadedTemplates(
        val templates: ConfigTemplates,
        val source: Source,
        val url: String? = null,
    )

    init {
        cacheDir.mkdirs()
    }

    fun templatesUrlFromConfigUrl(configUrl: String): String {
        val normalized = configLoader.normalizeUrl(configUrl)
        val base = normalized.substringBeforeLast('/', missingDelimiterValue = normalized)
        return if (base == normalized) {
            GitHubHelper.buildRawUrl(normalized, HassBleDefaults.TEMPLATES_FILE)
        } else {
            "$base/${HassBleDefaults.TEMPLATES_FILE}"
        }
    }

    suspend fun load(configUrl: String, token: String? = null): LoadedTemplates =
        withContext(Dispatchers.IO) {
            if (configUrl.isBlank()) {
                return@withContext LoadedTemplates(bundled, Source.BUNDLED)
            }
            val templatesUrl = templatesUrlFromConfigUrl(configUrl)
            runCatching {
                val cacheFile = cacheFileFor(templatesUrl)
                val req = Request.Builder().url(templatesUrl).apply {
                    if (token != null) header("Authorization", "Bearer $token")
                }.build()
                http.newCall(req).execute().use { resp ->
                    check(resp.isSuccessful) { "HTTP ${resp.code}" }
                    val text = resp.body!!.string()
                    val remote = ConfigTemplates.fromYaml(text)
                    writeCache(cacheFile, text)
                    LoadedTemplates(
                        templates = ConfigTemplates.merge(remote, bundled),
                        source = Source.REMOTE,
                        url = templatesUrl,
                    )
                }
            }.getOrElse {
                loadCache(configUrl)?.let { cached ->
                    LoadedTemplates(
                        templates = ConfigTemplates.merge(cached, bundled),
                        source = Source.CACHE,
                        url = templatesUrl,
                    )
                } ?: LoadedTemplates(bundled, Source.BUNDLED)
            }
        }

    fun loadCache(configUrl: String): ConfigTemplates? {
        if (configUrl.isBlank()) return null
        val cacheFile = cacheFileFor(templatesUrlFromConfigUrl(configUrl))
        return cacheFile.takeIf { it.exists() }
            ?.let { runCatching { ConfigTemplates.fromYaml(it.readText()) }.getOrNull() }
    }

    companion object {
        val TEMPLATES_FILE: String get() = HassBleDefaults.TEMPLATES_FILE
    }

    private fun writeCache(cacheFile: File, text: String) {
        cacheFile.writeText(text)
    }

    private fun cacheFileFor(templatesUrl: String): File {
        val safe = templatesUrl.hashCode().toUInt().toString(16)
        return File(cacheDir, "templates_$safe.yaml")
    }
}

package dev.eigger.hassble.net

import dev.eigger.hassble.BuildConfig
import dev.eigger.hassble.config.HassBleDefaults
import dev.eigger.hassble.config.HassSettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * GitHub Releases에 더 새 버전이 있는지 본다. 릴리스 페이지를 열어 주기만 하고
 * APK 다운로드·설치는 하지 않는다(REQUEST_INSTALL_PACKAGES 권한이 필요해진다).
 *
 * 익명 GitHub API는 IP당 시간 60회라 결과를 캐시하고 [CHECK_INTERVAL_MS]마다 한 번만 물어본다.
 */
object UpdateChecker {
    /** 마지막 조회로부터 이 시간이 지나야 다시 물어본다. */
    const val CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L

    private val client = OkHttpClient()

    data class Release(val version: String, val pageUrl: String)

    private val fallbackPageUrl = "https://github.com/${HassBleDefaults.APP_REPO}/releases/latest"

    /**
     * 캐시가 신선하면 그대로 쓰고, 아니면 GitHub에 물어본 뒤 저장한다.
     * 네트워크가 안 되면 캐시(없으면 null)를 돌려주므로 호출 측은 실패를 따로 다룰 필요가 없다.
     */
    suspend fun latestRelease(
        repository: HassSettingsRepository,
        nowMs: Long = System.currentTimeMillis(),
    ): Release? {
        val cached = repository.loadLatestReleaseCache()
        if (cached != null && nowMs - cached.checkedAtMs < CHECK_INTERVAL_MS) {
            return Release(cached.version, cached.pageUrl)
        }
        val fetched = fetchLatest().getOrNull()
            ?: return cached?.let { Release(it.version, it.pageUrl) }
        repository.saveLatestReleaseCache(fetched.version, fetched.pageUrl, nowMs)
        return fetched
    }

    /** 설치된 버전보다 새 릴리스면 그 릴리스를, 아니면 null. */
    fun updateAvailable(
        latest: Release?,
        currentVersion: String = BuildConfig.VERSION_NAME,
    ): Release? = latest?.takeIf { isNewer(it.version, currentVersion) }

    private suspend fun fetchLatest(): Result<Release> = withContext(Dispatchers.IO) {
        runCatching {
            // /releases/latest는 draft·prerelease를 빼고 최신 정식 릴리스만 준다.
            val request = Request.Builder()
                .url("https://api.github.com/repos/${HassBleDefaults.APP_REPO}/releases/latest")
                .header("Accept", "application/vnd.github+json")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("HTTP ${response.code}")
                val body = JSONObject(response.body?.string().orEmpty())
                val tag = body.optString("tag_name").ifBlank { error("tag_name missing") }
                val pageUrl = body.optString("html_url").ifBlank { fallbackPageUrl }
                Release(normalize(tag), pageUrl)
            }
        }
    }

    /** 'v1.5.1' → '1.5.1' */
    private fun normalize(tag: String) = tag.trim().removePrefix("v").removePrefix("V")

    /**
     * 숫자 파트를 앞에서부터 비교한다. 없는 자리는 0으로 본다(1.6 == 1.6.0).
     * 숫자 파트가 같으면 접미사가 붙은 쪽(1.6.0-rc1)을 더 낮게 본다.
     */
    internal fun isNewer(latest: String, current: String): Boolean {
        val l = normalize(latest)
        val c = normalize(current)
        val lNumbers = numbers(l)
        val cNumbers = numbers(c)
        for (i in 0 until maxOf(lNumbers.size, cNumbers.size)) {
            val a = lNumbers.getOrElse(i) { 0 }
            val b = cNumbers.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return suffix(l).isEmpty() && suffix(c).isNotEmpty()
    }

    private fun numbers(version: String): List<Int> =
        version.substringBefore('-').substringBefore('+')
            .split('.')
            .map { part -> part.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }

    private fun suffix(version: String): String =
        version.substringAfter('-', "").ifEmpty { version.substringAfter('+', "") }
}

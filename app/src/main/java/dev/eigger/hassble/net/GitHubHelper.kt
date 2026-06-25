package dev.eigger.hassble.net

import dev.eigger.hassble.config.HassBleDefaults
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

object GitHubHelper {
    private val client = OkHttpClient()

    data class ParsedGitUrl(val repoShort: String, val branch: String, val file: String)

    fun parseGitUrl(url: String): ParsedGitUrl? {
        val regex = Regex("https://raw\\.githubusercontent\\.com/([^/]+)/([^/]+)/([^/]+)/(.+)")
        val match = regex.matchEntire(url.trim()) ?: return null
        val owner = match.groupValues[1]
        val repo  = match.groupValues[2]
        val branch = match.groupValues[3]
        val file  = match.groupValues[4]
        val repoShort = if (branch == "main") "$owner/$repo" else "$owner/$repo/$branch"
        return ParsedGitUrl(repoShort, branch, file)
    }

    fun buildRawUrl(repoOrUrl: String, file: String, branch: String = HassBleDefaults.DEFAULT_BRANCH): String {
        if (repoOrUrl.startsWith("http")) return repoOrUrl   // already a full URL
        if (repoOrUrl.isBlank() || file.isBlank()) return ""
        val parts = repoOrUrl.trim('/').split('/')
        val owner  = parts.getOrNull(0) ?: return ""
        val repo   = parts.getOrNull(1) ?: return ""
        val resolvedBranch = when {
            parts.size > 2 -> parts.subList(2, parts.size).joinToString("/")
            branch.isNotBlank() -> branch.trim()
            else -> HassBleDefaults.DEFAULT_BRANCH
        }
        return "https://raw.githubusercontent.com/$owner/$repo/$resolvedBranch/$file"
    }

    fun buildConfigUrl(repo: String, branch: String = HassBleDefaults.DEFAULT_BRANCH): String =
        buildRawUrl(repo, HassBleDefaults.CONFIG_FILE, branch)

    fun buildTemplatesUrl(repo: String, branch: String = HassBleDefaults.DEFAULT_BRANCH): String =
        buildRawUrl(repo, HassBleDefaults.TEMPLATES_FILE, branch)

    /** Saved raw URL → owner/repo and branch (config file path is ignored). */
    fun parseRepoBranch(url: String): Pair<String, String>? {
        val parsed = parseGitUrl(url.trim()) ?: return null
        val parts = parsed.repoShort.split('/')
        if (parts.size < 2) return null
        return "${parts[0]}/${parts[1]}" to parsed.branch
    }

    suspend fun fetchYamlFiles(repoOrUrl: String, branch: String = "main", token: String? = null): Result<List<String>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val (owner, repo, resolvedBranch) = extractRepoInfo(repoOrUrl, branch)
                val url = "https://api.github.com/repos/$owner/$repo/git/trees/$resolvedBranch?recursive=1"
                val reqBuilder = Request.Builder()
                    .url(url)
                    .header("Accept", "application/vnd.github+json")
                if (token != null) reqBuilder.header("Authorization", "Bearer $token")

                val response = client.newCall(reqBuilder.build()).execute()
                if (!response.isSuccessful) {
                    when (response.code) {
                        401 -> throw GitHubUnauthorizedException()
                        404 -> throw GitHubNotFoundException()
                        else -> throw GitHubApiException(response.code, "HTTP ${response.code}")
                    }
                }
                val body = response.body?.string() ?: error("Empty response")

                val tree = JSONObject(body).getJSONArray("tree")
                buildList {
                    for (i in 0 until tree.length()) {
                        val item = tree.getJSONObject(i)
                        val path = item.getString("path")
                        val type = item.getString("type")
                        if (type == "blob" && (path.endsWith(".yaml") || path.endsWith(".yml"))) add(path)
                    }
                }.sorted()
            }
        }

    private fun extractRepoInfo(repoOrUrl: String, branch: String = "main"): Triple<String, String, String> {
        val parsed = parseGitUrl(repoOrUrl)
        if (parsed != null) {
            val parts = parsed.repoShort.split('/')
            return Triple(parts[0], parts[1], parsed.branch)
        }
        val parts = repoOrUrl.trim('/').split('/')
        val owner  = parts.getOrNull(0) ?: error("Invalid repo format — use owner/repo")
        val repo   = parts.getOrNull(1) ?: error("Invalid repo format — use owner/repo")
        val resolvedBranch = when {
            parts.size > 2 -> parts.subList(2, parts.size).joinToString("/")
            branch.isNotBlank() -> branch.trim()
            else -> "main"
        }
        return Triple(owner, repo, resolvedBranch)
    }
}

class GitHubUnauthorizedException : Exception("GitHub Token is invalid or unauthorized (401)")
class GitHubNotFoundException : Exception("GitHub repository or branch not found (404)")
class GitHubApiException(val code: Int, message: String) : Exception(message)

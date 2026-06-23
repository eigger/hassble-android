package dev.eigger.hassble.net

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

    fun buildRawUrl(repoOrUrl: String, file: String): String {
        if (repoOrUrl.startsWith("http")) return repoOrUrl   // already a full URL
        if (repoOrUrl.isBlank() || file.isBlank()) return ""
        val parts = repoOrUrl.trim('/').split('/')
        val owner  = parts.getOrNull(0) ?: return ""
        val repo   = parts.getOrNull(1) ?: return ""
        val branch = if (parts.size > 2) parts.subList(2, parts.size).joinToString("/") else "main"
        return "https://raw.githubusercontent.com/$owner/$repo/$branch/$file"
    }

    suspend fun fetchYamlFiles(repoOrUrl: String, token: String? = null): Result<List<String>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val (owner, repo, branch) = extractRepoInfo(repoOrUrl)
                val url = "https://api.github.com/repos/$owner/$repo/git/trees/$branch?recursive=1"
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

    private fun extractRepoInfo(repoOrUrl: String): Triple<String, String, String> {
        val parsed = parseGitUrl(repoOrUrl)
        if (parsed != null) {
            val parts = parsed.repoShort.split('/')
            return Triple(parts[0], parts[1], parsed.branch)
        }
        val parts = repoOrUrl.trim('/').split('/')
        val owner  = parts.getOrNull(0) ?: error("Invalid repo format — use owner/repo")
        val repo   = parts.getOrNull(1) ?: error("Invalid repo format — use owner/repo")
        val branch = if (parts.size > 2) parts.subList(2, parts.size).joinToString("/") else "main"
        return Triple(owner, repo, branch)
    }
}

class GitHubUnauthorizedException : Exception("GitHub Token is invalid or unauthorized (401)")
class GitHubNotFoundException : Exception("GitHub repository or branch not found (404)")
class GitHubApiException(val code: Int, message: String) : Exception(message)

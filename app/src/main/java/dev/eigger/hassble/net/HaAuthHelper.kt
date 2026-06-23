package dev.eigger.hassble.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

object HaAuthHelper {
    private val client = OkHttpClient()
    private val json = Json { ignoreUnknownKeys = true }

    const val CLIENT_ID = "https://hassble-android.eigger.dev"
    const val REDIRECT_URI = "hassble://oauth-callback"

    fun getAuthorizeUrl(haUrl: String, state: String): String {
        val cleanUrl = haUrl.trimEnd('/')
        return "$cleanUrl/auth/authorize?response_type=code&client_id=$CLIENT_ID&redirect_uri=$REDIRECT_URI&state=$state"
    }

    data class TokenResponse(
        val accessToken: String,
        val refreshToken: String?,
        val expiresIn: Int
    )

    fun exchangeCodeForTokens(haUrl: String, code: String): Result<TokenResponse> {
        val cleanUrl = haUrl.trimEnd('/')
        val url = "$cleanUrl/auth/token"

        val formBody = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("code", code)
            .add("client_id", CLIENT_ID)
            .add("redirect_uri", REDIRECT_URI)
            .build()

        val request = Request.Builder()
            .url(url)
            .post(formBody)
            .build()

        return runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string()
                    throw IOException("HTTP ${response.code}: $errorBody")
                }
                val bodyStr = response.body?.string() ?: throw IOException("Empty response body")
                val jsonObj = json.parseToJsonElement(bodyStr).jsonObject
                val accessToken = jsonObj["access_token"]?.jsonPrimitive?.content ?: throw IOException("No access_token")
                val refreshToken = jsonObj["refresh_token"]?.jsonPrimitive?.content ?: throw IOException("No refresh_token")
                val expiresIn = jsonObj["expires_in"]?.jsonPrimitive?.content?.toIntOrNull() ?: 1800
                TokenResponse(accessToken, refreshToken, expiresIn)
            }
        }
    }

    fun refreshAccessToken(haUrl: String, refreshToken: String): Result<String> {
        val cleanUrl = haUrl.trimEnd('/')
        val url = "$cleanUrl/auth/token"

        val formBody = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("refresh_token", refreshToken)
            .add("client_id", CLIENT_ID)
            .build()

        val request = Request.Builder()
            .url(url)
            .post(formBody)
            .build()

        return runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string()
                    throw IOException("HTTP ${response.code}: $errorBody")
                }
                val bodyStr = response.body?.string() ?: throw IOException("Empty response body")
                val jsonObj = json.parseToJsonElement(bodyStr).jsonObject
                val accessToken = jsonObj["access_token"]?.jsonPrimitive?.content ?: throw IOException("No access_token")
                accessToken
            }
        }
    }
}

package dev.eigger.hassble.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

object HaConnectionTester {
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    sealed class Result {
        data object Ok : Result()
        data class AuthFailed(val code: Int) : Result()
        data class NetworkError(val message: String) : Result()
    }

    suspend fun test(haUrl: String, token: String): Result = withContext(Dispatchers.IO) {
        runCatching {
            val base = haUrl.trimEnd('/')
            val request = Request.Builder()
                .url("$base/api/")
                .header("Authorization", "Bearer $token")
                .header("Content-Type", "application/json")
                .get()
                .build()
            http.newCall(request).execute().use { response ->
                when (response.code) {
                    200 -> Result.Ok
                    401, 403 -> Result.AuthFailed(response.code)
                    else -> Result.NetworkError("HTTP ${response.code}")
                }
            }
        }.getOrElse { e ->
            Result.NetworkError(e.message ?: e.javaClass.simpleName)
        }
    }
}

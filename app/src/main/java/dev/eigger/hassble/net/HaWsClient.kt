package dev.eigger.hassble.net

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import dev.eigger.hassble.BuildConfig
import dev.eigger.hassble.R
import dev.eigger.hassble.service.LiveEventLogger
import dev.eigger.hassble.service.LogType
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

enum class ConnectionState {
    Disconnected,
    Connecting,
    Connected
}

/**
 * HA WebSocket API 클라이언트 (Companion 앱 유사 패턴).
 *  1) 표준 auth(토큰)
 *  2) ws_bridge/connect 구독 → HA가 command 이벤트를 push
 *  3) ws_bridge/entity(선언), ws_bridge/state(배치 갱신), ws_bridge/availability
 *
 * auth_ok 및 connect result 수신 전에는 메시지를 큐에 보관한다.
 */
class HaWsClient(
    private val baseUrl: String,
    private var token: String,
    private val gatewayId: String,
    private val gatewayName: String,
    private val scope: CoroutineScope,
    private val refreshToken: String? = null,
    private val onTokenRefreshed: (suspend (String) -> Unit)? = null,
    private val http: OkHttpClient = OkHttpClient.Builder()
        .pingInterval(5, java.util.concurrent.TimeUnit.SECONDS)
        .build(),
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        explicitNulls = false
    }
    private val idGen = AtomicInteger(1)
    private var ws: WebSocket? = null
    private var connectMessageId: Int? = null
    private var bridgeTimeoutJob: Job? = null
    private val pendingMessages = mutableListOf<String>()

    private val _events = MutableSharedFlow<JsonObject>(extraBufferCapacity = 64)
    val events: SharedFlow<JsonObject> = _events.asSharedFlow()

    private val _connectionState = MutableStateFlow(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _connectionIssue = MutableStateFlow(ConnectionIssue.None)
    val connectionIssue: StateFlow<ConnectionIssue> = _connectionIssue.asStateFlow()

    // true = initial/reconnect (reset states), false = periodic resubscribe (keep states)
    private val _bridgeConnected = MutableSharedFlow<Boolean>(extraBufferCapacity = 1)
    val bridgeConnected: SharedFlow<Boolean> = _bridgeConnected.asSharedFlow()

    private var isClosedManually = false
    private var authFailed = false
    private var reconnectDelayMs = 2000L
    private val resubscribePending = AtomicBoolean(false)
    private var resubscribeJob: Job? = null
    private val pendingRequests = java.util.concurrent.ConcurrentHashMap<Int, CompletableDeferred<JsonObject>>()

    fun connect() {
        if (_connectionState.value != ConnectionState.Disconnected) return
        isClosedManually = false
        authFailed = false
        connectMessageId = null
        pendingMessages.clear()
        _connectionIssue.value = ConnectionIssue.None
        _connectionState.value = ConnectionState.Connecting

        val protocol = if (baseUrl.startsWith("https")) "wss" else "ws"
        val cleanUrl = baseUrl.substringAfter("://").trimEnd('/')
        val url = "$protocol://$cleanUrl/api/websocket"

        ws = http.newWebSocket(Request.Builder().url(url).build(), Listener())
    }

    fun close() {
        isClosedManually = true
        bridgeTimeoutJob?.cancel()
        resubscribeJob?.cancel()
        connectMessageId = null
        pendingMessages.clear()
        _connectionState.value = ConnectionState.Disconnected
        _connectionIssue.value = ConnectionIssue.None
        ws?.close(1000, "Closed manually")
        ws = null
    }

    fun reconnectImmediately() {
        if (isClosedManually) return
        if (_connectionState.value == ConnectionState.Disconnected) {
            reconnectDelayMs = 500L
            scope.launch {
                if (!refreshToken.isNullOrBlank() && HaAuthHelper.isTokenExpiringSoon(token)) {
                    val result = withContext(Dispatchers.IO) {
                        HaAuthHelper.refreshAccessToken(baseUrl, refreshToken)
                    }
                    if (result.isSuccess) {
                        val newToken = result.getOrThrow()
                        token = newToken
                        onTokenRefreshed?.invoke(newToken)
                    }
                }
                connect()
            }
        }
    }

    fun declareEntity(msg: EntityMsg) {
        enqueueOrSend(json.encodeToString(EntityMsg.serializer(), msg.copy(id = idGen.getAndIncrement())))
    }

    fun sendStates(states: List<Pair<String, Any>>) {
        if (states.isEmpty()) return
        enqueueOrSend(buildJsonObject {
            put("id", idGen.getAndIncrement())
            put("type", "$WS_DOMAIN/state")
            put("states", buildJsonArray {
                for ((uid, v) in states) add(buildJsonObject {
                    put("unique_id", uid)
                    when (v) {
                        is Number -> put("value", v)
                        is Boolean -> put("value", v)
                        else -> put("value", v.toString())
                    }
                })
            })
            put("ts", System.currentTimeMillis() / 1000)
        }.toString())
    }

    fun sendInitialStates(uids: List<String>) {
        if (uids.isEmpty()) return
        enqueueOrSend(buildJsonObject {
            put("id", idGen.getAndIncrement())
            put("type", "$WS_DOMAIN/state")
            put("states", buildJsonArray {
                for (uid in uids) add(buildJsonObject {
                    put("unique_id", uid)
                    put("value", "unknown")
                })
            })
            put("ts", System.currentTimeMillis() / 1000)
        }.toString())
    }

    fun sendAvailability(deviceId: String, online: Boolean) {
        enqueueOrSend(buildJsonObject {
            put("id", idGen.getAndIncrement())
            put("type", "$WS_DOMAIN/availability")
            put("device_id", deviceId)
            put("online", online)
        }.toString())
    }

    suspend fun removeDevice(deviceId: String, mode: HaRemoveMode = HaRemoveMode.EXACT) {
        if (_connectionState.value != ConnectionState.Connected) return
        // ws_bridge/remove 를 사용해야 ws_bridge 내부의 _created set도 함께 정리됨.
        // native config/entity_registry/remove 는 HA 레지스트리만 삭제하고 _created는 그대로 남아
        // 이후 redeclare 시 _create()가 skip되어 엔티티가 재등록되지 않는 버그 발생.
        sendRequest("$WS_DOMAIN/remove") {
            put("device_id", deviceId)
            if (mode != HaRemoveMode.EXACT) put("mode", mode.wireName)
        }
    }

    private suspend fun sendRequest(type: String, build: JsonObjectBuilder.() -> Unit = {}): JsonObject? {
        val id = idGen.getAndIncrement()
        val deferred = CompletableDeferred<JsonObject>()
        pendingRequests[id] = deferred
        enqueueOrSend(buildJsonObject { put("id", id); put("type", type); build() }.toString())
        return withTimeoutOrNull(10_000) { deferred.await() }
    }

    private fun enqueueOrSend(text: String) {
        if (_connectionState.value == ConnectionState.Connected) {
            send(text)
        } else {
            pendingMessages += text
        }
    }

    private fun send(text: String) {
        ws?.send(text)
        LiveEventLogger.log(LogType.TX, "WS: $text")
    }

    private fun flushPendingMessages() {
        val queued = pendingMessages.toList()
        pendingMessages.clear()
        for (text in queued) send(text)
    }

    private fun subscribe() {
        val msgId = idGen.getAndIncrement()
        connectMessageId = msgId
        send(buildJsonObject {
            put("id", msgId)
            put("type", "$WS_DOMAIN/connect")
            put("gateway_id", gatewayId)
            put("name", gatewayName)
            put("app_version", BuildConfig.VERSION_NAME)
        }.toString())
        startBridgeTimeout()
    }

    private fun startBridgeTimeout() {
        bridgeTimeoutJob?.cancel()
        bridgeTimeoutJob = scope.launch {
            delay(15_000)
            if (_connectionState.value == ConnectionState.Connecting) {
                _connectionIssue.value = ConnectionIssue.BridgeNotResponding
                ws?.close(1000, "Bridge timeout")
            }
        }
    }

    fun resubscribe() {
        if (_connectionState.value != ConnectionState.Connected) return
        resubscribePending.set(true)
        subscribe()
    }

    private fun onConnectResult() {
        bridgeTimeoutJob?.cancel()
        val isResubscribe = resubscribePending.getAndSet(false)
        _connectionState.value = ConnectionState.Connected
        _connectionIssue.value = ConnectionIssue.None
        reconnectDelayMs = 2000L
        flushPendingMessages()
        _bridgeConnected.tryEmit(!isResubscribe)
        if (!isResubscribe) {
            resubscribeJob?.cancel()
            resubscribeJob = scope.launch {
                while (true) {
                    delay(60_000)
                    resubscribe()
                }
            }
        }
    }

    private fun triggerReconnection() {
        if (isClosedManually || authFailed) return
        bridgeTimeoutJob?.cancel()
        resubscribeJob?.cancel()
        connectMessageId = null
        pendingMessages.clear()
        _connectionState.value = ConnectionState.Disconnected
        scope.launch {
            delay(reconnectDelayMs)
            reconnectDelayMs = (reconnectDelayMs * 2).coerceAtMost(30000L)
            if (!refreshToken.isNullOrBlank() && HaAuthHelper.isTokenExpiringSoon(token)) {
                val result = withContext(Dispatchers.IO) {
                    HaAuthHelper.refreshAccessToken(baseUrl, refreshToken)
                }
                if (result.isSuccess) {
                    val newToken = result.getOrThrow()
                    token = newToken
                    onTokenRefreshed?.invoke(newToken)
                }
            }
            connect()
        }
    }

    private fun handleAuthFailed(webSocket: WebSocket) {
        authFailed = true
        pendingMessages.clear()
        bridgeTimeoutJob?.cancel()
        _connectionIssue.value = ConnectionIssue.AuthFailed
        _connectionState.value = ConnectionState.Disconnected
        webSocket.close(1000, "Auth failed")
    }

    private inner class Listener : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            LiveEventLogger.log(LogType.RX, "WS: $text")
            val msg = json.parseToJsonElement(text).jsonObject
            when (msg["type"]?.jsonPrimitive?.content) {
                "auth_required" -> send(buildJsonObject {
                    put("type", "auth")
                    put("access_token", token)
                }.toString())
                "auth_ok" -> subscribe()
                "auth_invalid" -> {
                    if (refreshToken.isNullOrBlank()) {
                        LiveEventLogger.logRes(LogType.LINK, R.string.log_oauth_auth_failed_no_refresh)
                        handleAuthFailed(webSocket)
                    } else {
                        LiveEventLogger.logRes(LogType.LINK, R.string.log_oauth_token_expired_refreshing)
                        scope.launch(Dispatchers.IO) {
                            val result = HaAuthHelper.refreshAccessToken(baseUrl, refreshToken)
                            if (result.isSuccess) {
                                val newToken = result.getOrThrow()
                                token = newToken
                                onTokenRefreshed?.invoke(newToken)
                                reconnectDelayMs = 500L
                                LiveEventLogger.logRes(LogType.LINK, R.string.log_oauth_refresh_success)
                                webSocket.close(4000, "Token refreshed, reconnecting")
                            } else {
                                LiveEventLogger.logRes(
                                    LogType.LINK,
                                    R.string.log_oauth_refresh_failed,
                                    result.exceptionOrNull()?.localizedMessage ?: "",
                                )
                                handleAuthFailed(webSocket)
                            }
                        }
                    }
                }
                "result" -> {
                    val id = msg["id"]?.jsonPrimitive?.content?.toIntOrNull()
                    if (id != null) {
                        pendingRequests.remove(id)?.complete(msg)
                        if (id == connectMessageId) onConnectResult()
                    }
                }
                "event" -> {
                    val event = msg["event"]?.jsonObject ?: return
                    _events.tryEmit(event)
                }
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (code != 4000 && _connectionIssue.value == ConnectionIssue.None && !authFailed) {
                _connectionIssue.value = ConnectionIssue.NetworkError
            }
            triggerReconnection()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (_connectionIssue.value == ConnectionIssue.None) {
                _connectionIssue.value = ConnectionIssue.NetworkError
            }
            triggerReconnection()
        }
    }
}

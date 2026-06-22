package dev.eigger.hassble.net

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
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
 * MQTT/추가 포트 없음.
 */
class HaWsClient(
    private val baseUrl: String,
    private val token: String,
    private val gatewayId: String,
    private val gatewayName: String,
    private val scope: CoroutineScope,
    private val http: OkHttpClient = OkHttpClient(),
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val idGen = AtomicInteger(1)
    private var ws: WebSocket? = null

    private val _events = MutableSharedFlow<JsonObject>(extraBufferCapacity = 64)
    val events: SharedFlow<JsonObject> = _events.asSharedFlow()  // command 등 HA→앱

    private val _connectionState = MutableStateFlow(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private var isClosedManually = false
    private var reconnectDelayMs = 2000L

    fun connect() {
        if (_connectionState.value != ConnectionState.Disconnected) return
        isClosedManually = false
        _connectionState.value = ConnectionState.Connecting

        val protocol = if (baseUrl.startsWith("https")) "wss" else "ws"
        val cleanUrl = baseUrl.substringAfter("://").trimEnd('/')
        val url = "$protocol://$cleanUrl/api/websocket"

        ws = http.newWebSocket(Request.Builder().url(url).build(), Listener())
    }

    fun close() {
        isClosedManually = true
        _connectionState.value = ConnectionState.Disconnected
        ws?.close(1000, "Closed manually")
        ws = null
    }

    fun declareEntity(msg: EntityMsg) {
        send(json.encodeToString(EntityMsg.serializer(), msg.copy(id = idGen.getAndIncrement())))
    }

    /** 상태 배치 전송. value는 Number/String/Boolean. */
    fun sendStates(states: List<Pair<String, Any>>) {
        if (states.isEmpty() || _connectionState.value != ConnectionState.Connected) return
        send(buildJsonObject {
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

    fun sendAvailability(deviceId: String, online: Boolean) {
        if (_connectionState.value != ConnectionState.Connected) return
        send(buildJsonObject {
            put("id", idGen.getAndIncrement())
            put("type", "$WS_DOMAIN/availability")
            put("device_id", deviceId)
            put("online", online)
        }.toString())
    }

    private fun send(text: String) {
        ws?.send(text)
    }

    private fun subscribe() {
        send(buildJsonObject {
            put("id", idGen.getAndIncrement())
            put("type", "$WS_DOMAIN/connect")
            put("gateway_id", gatewayId)
            put("name", gatewayName)
        }.toString())
        _connectionState.value = ConnectionState.Connected
        reconnectDelayMs = 2000L // Reset reconnect backoff on success
    }

    private fun triggerReconnection() {
        if (isClosedManually) return
        _connectionState.value = ConnectionState.Disconnected
        scope.launch {
            delay(reconnectDelayMs)
            reconnectDelayMs = (reconnectDelayMs * 2).coerceAtMost(30000L) // Exponential backoff max 30s
            connect()
        }
    }

    private inner class Listener : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            val msg = json.parseToJsonElement(text).jsonObject
            when (msg["type"]?.jsonPrimitive?.content) {
                "auth_required" -> send(buildJsonObject {
                    put("type", "auth"); put("access_token", token)
                }.toString())
                "auth_ok" -> subscribe()
                "event" -> msg["event"]?.let { _events.tryEmit(it.jsonObject) }
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            triggerReconnection()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            triggerReconnection()
        }
    }
}


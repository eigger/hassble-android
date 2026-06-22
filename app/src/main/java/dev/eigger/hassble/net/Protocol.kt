package dev.eigger.hassble.net

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * 앱↔HA WebSocket 프로토콜 (docs/PROTOCOL.md). 범용 ws_bridge 컴포넌트와 통신.
 * 앱이 엔티티를 선언하고 상태를 push. HA는 생성/갱신만.
 */
const val WS_DOMAIN = "ws_bridge"

// ── 앱 → HA: 엔티티 선언 ──────────────────────────────────────────────────────
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class EntityMsg(
    val id: Int,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val type: String = "$WS_DOMAIN/entity",
    @SerialName("unique_id") val uniqueId: String,
    val platform: String,                 // sensor|binary_sensor|text_sensor|switch|number|select|button
    val name: String,
    val device: DeviceRef? = null,
    @SerialName("device_class") val deviceClass: String? = null,
    @SerialName("unit_of_measurement") val unit: String? = null,
    @SerialName("state_class") val stateClass: String? = null,
    val icon: String? = null,
    @SerialName("entity_category") val entityCategory: String? = null,
    val options: List<String>? = null,    // select
    val min: Double? = null,              // number
    val max: Double? = null,
    val step: Double? = null,
)

@Serializable
data class DeviceRef(val id: String, val name: String)

// ── HA → 앱: 제어 의도 ────────────────────────────────────────────────────────
@Serializable
data class CommandPayload(
    val kind: String,                      // "command"
    @SerialName("unique_id") val uniqueId: String,
    val action: String,                    // turn_on|turn_off|set_value|select_option|press
    val value: JsonElement? = null,        // set_value(숫자) / select_option(문자열)
)

// state 메시지는 값 타입이 혼합(숫자/문자/불리언)이라 HaWsClient에서 직접 JSON 구성.

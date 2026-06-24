package dev.eigger.hassble.config

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * git URL에서 앱이 불러오는 설정. 스펙: docs/CONFIG_SCHEMA.md
 * 앱이 이 설정으로 디코딩/필터링/엔티티 선언을 한다(HA는 엔티티 생성/갱신만).
 */
@Serializable
data class GatewayConfig(
    val version: Int = 1,
    val defaults: Defaults = Defaults(),
    val devices: List<DeviceConfig> = emptyList(),
) {
    fun allSensorKeys(): Set<String> =
        devices.flatMap { d -> d.sensors.map { "${d.id}/${it.key}" } }.toSet()
}

@Serializable
data class Defaults(val publish: PublishRule = PublishRule())

enum class Source { advertisement, gatt_notify, obd }

/** advertisement 전용: mac=스캔 MAC별 엔티티, shared=프로필 ID 하나(마지막 광고가 덮어씀) */
enum class AdvertisementInstanceMode { mac, shared }

@Serializable
data class DeviceConfig(
    val id: String,
    val name: String,
    val source: Source,
    val match: MatchConfig? = null,
    @SerialName("instance_mode") val instanceMode: AdvertisementInstanceMode = AdvertisementInstanceMode.mac,
    val gatt: GattConfig? = null,
    val obd: ObdConfig? = null,
    val sensors: List<SensorConfig> = emptyList(),
    val controls: List<ControlConfig> = emptyList(),
    val publish: PublishRule? = null,
)

@Serializable
data class MatchConfig(
    val mac: String? = null,
    @SerialName("service_data_uuid") val serviceDataUuid: String? = null,
    @SerialName("manufacturer_id") val manufacturerId: Int? = null,
    /** manufacturer payload(hex, 대소문자 무시)가 이 문자열로 시작해야 함. Jaalee iBeacon: 0215 */
    @SerialName("manufacturer_hex_prefix") val manufacturerHexPrefix: String? = null,
    /** manufacturer payload 최소 바이트 수. Jaalee JHT: 24 */
    @SerialName("manufacturer_min_length") val manufacturerMinLength: Int? = null,
    @SerialName("name_prefix") val namePrefix: String? = null,
)

@Serializable
data class GattConfig(
    val mac: String? = null,
    @SerialName("service_uuid") val serviceUuid: String,
    @SerialName("notify_char_uuid") val notifyCharUuid: String,
    @SerialName("write_char_uuid") val writeCharUuid: String? = null,
    @SerialName("auto_connect") val autoConnect: Boolean = true,
)

@Serializable
data class ObdConfig(
    val mac: String? = null,
    @SerialName("service_uuid") val serviceUuid: String = "FFF0",
    @SerialName("tx_char_uuid") val txCharUuid: String = "FFF2",
    @SerialName("rx_char_uuid") val rxCharUuid: String = "FFF1",
    @SerialName("tx_delay") val txDelay: String = "50ms",
    @SerialName("init_commands") val initCommands: List<String> = emptyList(),
    @SerialName("auto_connect") val autoConnect: Boolean = true,
)

@Serializable
data class SensorConfig(
    val key: String,
    val platform: String = "sensor",       // sensor | binary_sensor | text_sensor
    @SerialName("device_class") val deviceClass: String? = null,
    val unit: String? = null,
    @SerialName("state_class") val stateClass: String? = null,
    val icon: String? = null,
    @SerialName("entity_category") val entityCategory: String? = null,
    @SerialName("accuracy_decimals") val accuracyDecimals: Int? = null,
    // advertisement / gatt_notify
    @SerialName("source_field") val sourceField: SourceField = SourceField.raw,
    @SerialName("min_length") val minLength: Int? = null,
    val decode: DecodeConfig? = null,
    // obd
    val preset: String? = null,
    val mode: String = "01",
    val pid: String? = null,
    val formula: String? = null,
    @SerialName("update_interval") val updateInterval: String = "60s",
    @SerialName("pre_commands") val preCommands: List<String> = emptyList(),
    // 필터
    val publish: PublishRule? = null,
)

// YAML에서 state_class: none 이 문자열 "none"으로 파싱되는 경우를 null로 정규화
fun SensorConfig.effectiveStateClass(): String? =
    stateClass?.takeIf { it.lowercase() != "none" && it.isNotBlank() }

enum class SourceField { service_data, manufacturer_data, raw }

@Serializable
data class DecodeConfig(
    val offset: Int = 0,
    val length: Int = 1,
    val type: DataType = DataType.uint8,
    val endian: Endian = Endian.big,
    val bitmask: Long? = null,
    val scale: Double = 1.0,
    @SerialName("offset_value") val offsetValue: Double = 0.0,
    val map: Map<String, String> = emptyMap(),
)

enum class DataType { int8, uint8, int16, uint16, int32, uint32, float32, timestamp, string }
enum class Endian { big, little }

@Serializable
data class ControlConfig(
    val key: String,
    val type: ControlType,
    val name: String? = null,
    val icon: String? = null,
    @SerialName("entity_category") val entityCategory: String? = null,
    // command 매핑:
    //  switch → {on, off} hex / number → {template} ("A1{value:02X}")
    //  select → {<option>: hex, ...} / button → {press: hex}
    val command: Map<String, String> = emptyMap(),
    val options: List<String> = emptyList(),  // select
    val min: Double? = null,                   // number
    val max: Double? = null,
    val step: Double? = null,
)

enum class ControlType { switch, number, select, button }

enum class BleScanModeOption(val label: String) {
    LOW_POWER("Low Power"),
    BALANCED("Balanced"),
    LOW_LATENCY("Low Latency"),
}

@Serializable
data class PublishRule(
    @SerialName("on_change_only") val onChangeOnly: Boolean = true,
    @SerialName("min_interval") val minInterval: String = "10s",
    val heartbeat: String? = null,
    val deadband: Double? = null,
)

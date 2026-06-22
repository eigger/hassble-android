package dev.eigger.hassble.ble

import dev.eigger.hassble.config.DeviceConfig
import kotlinx.coroutines.flow.Flow

/**
 * BLE 획득 소스 — BLE I/O만 담당. raw 바이트(hex)를 [RawReading]으로 방출.
 * 디코딩/필터링은 상위(BleRuntime)가 config로 수행.
 */
data class RawReading(
    val deviceId: String,
    val source: String,        // advertisement | gatt_notify | obd
    val rawHex: String,
)

/** 경로 A: 광고 passive scan. match를 ScanFilter로 변환. */
interface AdvertisementScanner {
    fun scan(devices: List<DeviceConfig>): Flow<RawReading>
    fun stop()
}

/** 경로 B: GATT notify + (선택) write. */
interface GattNotifySource {
    fun connect(device: DeviceConfig): Flow<RawReading>
    suspend fun write(device: DeviceConfig, hex: String)
    fun disconnect(deviceId: String)
}

/**
 * 경로 C: ELM327 폴링. 켜진 OBD 센서들의 (mode+pid)와 update_interval로 폴링 플랜을
 * 로컬 구성, base init + init_commands 후 tx_delay 간격 드레인, 응답 raw 방출.
 *
 * TODO: 멀티프레임(ISO-TP), pre_commands 헤더 전환, 재연결.
 */
interface Elm327Source {
    /** enabledKeys = 사용자가 켠 센서 key (폴링 대상). */
    fun connect(device: DeviceConfig, enabledKeys: Set<String>): Flow<RawReading>
    suspend fun write(device: DeviceConfig, hex: String)
    fun disconnect(deviceId: String)

    companion object {
        /** 매 연결 시 항상 먼저 보내는 base 시퀀스 (ESPHome ble_elm327과 동일). */
        val BASE_INIT = listOf("ATZ", "ATE0", "ATL0", "ATS0", "ATH0", "ATSP0")
    }
}

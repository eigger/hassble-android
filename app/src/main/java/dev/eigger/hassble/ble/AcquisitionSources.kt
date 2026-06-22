package dev.eigger.hassble.ble

import dev.eigger.hassble.config.BleScanModeOption
import dev.eigger.hassble.config.DeviceConfig
import kotlinx.coroutines.flow.Flow

/**
 * BLE 획득 소스 — BLE I/O만 담당. raw 바이트(hex)를 [RawReading]으로 방출.
 * 디코딩/필터링은 상위(BleRuntime)가 config로 수행.
 */
data class RawReading(
    val deviceId: String,
    val source: String,        // advertisement | gatt_notify | obd
    /** 하위 호환·비광고 소스용. 광고는 source_field별 hex 필드 사용 권장. */
    val rawHex: String,
    val macAddress: String? = null,
    val deviceName: String? = null,
    val manufacturerHex: String? = null,
    val serviceDataHex: String? = null,
    val fullScanHex: String? = null,
)

/** 경로 A: 광고 passive scan. match를 ScanFilter로 변환. */
interface AdvertisementScanner {
    fun scan(devices: List<DeviceConfig>, scanMode: BleScanModeOption = BleScanModeOption.BALANCED): Flow<RawReading>
    fun stop()
}

/** 경로 B: GATT notify + (선택) write. */
interface GattNotifySource {
    fun connect(device: DeviceConfig): Flow<RawReading>
    suspend fun write(device: DeviceConfig, hex: String)
    fun disconnect(deviceId: String)
}

/**
 * 경로 C: ELM327 폴링.
 * 단일 TX 큐 + pre_commands 헤더 전환 + ISO-TP 응답 정규화 + 끊김 시 재연결.
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

package dev.eigger.hassble.ble

/** 센서별 마지막 디코딩·전송 값 (UI 표시용). */
data class SensorLastValue(
    val profileId: String,
    val instanceId: String,
    val sensorKey: String,
    val value: String,
    val updatedAtMs: Long,
)

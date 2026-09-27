package dev.eigger.hassble.ble

/** 센서별 마지막 디코딩 값 (UI 표시용). */
data class SensorLastValue(
    val profileId: String,
    val instanceId: String,
    val sensorKey: String,
    val value: String,
    /** 마지막으로 이 값을 **수신·디코딩**한 시각. 발행 필터(on_change 등)와 무관하게 갱신된다. */
    val updatedAtMs: Long,
    /** 마지막으로 HA에 **전송**한 시각. 필터가 계속 막으면 수신 시각보다 오래될 수 있다. null이면 아직 미전송. */
    val publishedAtMs: Long? = null,
)

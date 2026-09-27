package dev.eigger.hassble.ble

/** 센서별 마지막 디코딩 값 (UI 표시용). */
data class SensorLastValue(
    val profileId: String,
    val instanceId: String,
    val sensorKey: String,
    /** 마지막으로 **수신**한 값(표시 문자열). 발행 필터에 막혀 HA에 안 간 값일 수 있다. */
    val value: String,
    /** 마지막으로 이 값을 **수신·디코딩**한 시각. 발행 필터(on_change 등)와 무관하게 갱신된다. */
    val updatedAtMs: Long,
    /** 마지막으로 HA에 **전송**한 시각. 필터가 계속 막으면 수신 시각보다 오래될 수 있다. null이면 아직 미전송. */
    val publishedAtMs: Long? = null,
    /** 마지막으로 HA에 전송한 값. [value]와 다르면 HA는 아직 이 값을 들고 있다(deadband·min_interval). */
    val publishedValue: String? = null,
) {
    companion object {
        /** 새 수신값을 반영한 다음 상태. HA 전송 여부와 무관하게 수신 값·시각은 항상 최신으로 둔다. */
        fun next(
            prev: SensorLastValue?,
            profileId: String,
            instanceId: String,
            sensorKey: String,
            display: String,
            published: Boolean,
            nowMs: Long,
        ): SensorLastValue {
            return SensorLastValue(
                profileId = profileId,
                instanceId = instanceId,
                sensorKey = sensorKey,
                value = display,
                updatedAtMs = nowMs,
                publishedAtMs = if (published) nowMs else prev?.publishedAtMs,
                publishedValue = if (published) display else prev?.publishedValue,
            )
        }
    }
}

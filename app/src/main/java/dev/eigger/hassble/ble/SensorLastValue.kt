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
        /**
         * 새 수신값을 반영한 다음 상태. UI를 갱신할 필요가 없으면 null.
         *
         * 광고는 초당 수십 건이라, HA에 전송되지 않은 수신은 값이 바뀌어도 [refreshMs] 단위로만 반영한다
         * (예전엔 min_interval이 이 역할을 했는데 수신 시각을 따로 기록하면서 그 상한이 사라졌다).
         * 전송된 값은 항상 즉시 반영한다.
         */
        fun next(
            prev: SensorLastValue?,
            profileId: String,
            instanceId: String,
            sensorKey: String,
            display: String,
            published: Boolean,
            nowMs: Long,
            refreshMs: Long,
        ): SensorLastValue? {
            if (!published && prev != null && nowMs - prev.updatedAtMs < refreshMs) return null
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

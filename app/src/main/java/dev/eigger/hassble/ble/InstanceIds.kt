package dev.eigger.hassble.ble

/**
 * 광고 프로필(deviceId)과 HA 인스턴스 id의 관계. 고정·shared 인스턴스는 deviceId 그대로이고,
 * 동적 인스턴스는 `{deviceId}_{12자리 MAC}`이다.
 */
internal object InstanceIds {
    private val NORMALIZED_MAC = Regex("^[0-9A-F]{12}$", RegexOption.IGNORE_CASE)

    /** 접두사만 보면 `car`가 `car_park_…`까지 잡으므로, 접두사 뒤가 정확히 MAC일 때만 같은 프로필로 본다. */
    fun belongsTo(instanceId: String, deviceId: String): Boolean {
        if (instanceId == deviceId) return true
        val suffix = instanceId.removePrefix("${deviceId}_")
        return suffix != instanceId && NORMALIZED_MAC.matches(suffix)
    }

    /** [known] 중 이 프로필의 인스턴스 + deviceId 자신(고정·shared 인스턴스). */
    fun of(deviceId: String, known: Iterable<String>): Set<String> =
        known.filterTo(linkedSetOf(deviceId)) { belongsTo(it, deviceId) }
}

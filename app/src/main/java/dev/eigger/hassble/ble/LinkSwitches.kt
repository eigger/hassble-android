package dev.eigger.hassble.ble

/**
 * OBD/GATT 기기마다 앱이 자동으로 선언하는 HA 스위치.
 *  - `{id}_connection`: 연결 동작 제어 겸 상태. 연결을 시도 중이어도 on이라 자동화가 그 시도를 취소할 수 있다.
 *    실제로 붙어 있는지는 `{id}_link_status`(binary_sensor)가 알려 준다.
 *  - `{id}_auto_connect`: 앱 UI의 "자동 연결" 설정과 같은 값(저장됨).
 */
object LinkSwitches {
    const val CONNECTION_SUFFIX = "_connection"
    const val AUTO_CONNECT_SUFFIX = "_auto_connect"

    enum class Kind { Connection, AutoConnect }

    fun connectionUid(deviceId: String) = "$deviceId$CONNECTION_SUFFIX"
    fun autoConnectUid(deviceId: String) = "$deviceId$AUTO_CONNECT_SUFFIX"

    /** 연결을 시도 중이거나 붙어 있으면 true. */
    fun isActive(state: DeviceLinkState?): Boolean = when (state) {
        DeviceLinkState.Connecting, DeviceLinkState.Scanning,
        DeviceLinkState.Connected, DeviceLinkState.Polling -> true
        DeviceLinkState.Disconnected, DeviceLinkState.Error, null -> false
    }

    fun parse(uniqueId: String): Pair<String, Kind>? = when {
        uniqueId.endsWith(AUTO_CONNECT_SUFFIX) ->
            uniqueId.removeSuffix(AUTO_CONNECT_SUFFIX).takeIf { it.isNotEmpty() }?.let { it to Kind.AutoConnect }
        uniqueId.endsWith(CONNECTION_SUFFIX) ->
            uniqueId.removeSuffix(CONNECTION_SUFFIX).takeIf { it.isNotEmpty() }?.let { it to Kind.Connection }
        else -> null
    }
}

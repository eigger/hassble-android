package dev.eigger.hassble.net

/**
 * [ws_bridge/remove](https://github.com/eigger/hass-ws-bridge/blob/main/docs/PROTOCOL.md) 삭제 범위.
 */
enum class HaRemoveMode(val wireName: String) {
    /** 클라이언트 device.id / unique_id 와 완전 일치하는 대상만 */
    EXACT("exact"),

    /**
     * 대상 id와 일치하거나 `target_` 로 시작하는 하위 id 전부.
     * advertisement instance_mode=mac 프로필 삭제 시 MAC 인스턴스 포함.
     */
    PREFIX("prefix"),
}

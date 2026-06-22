package dev.eigger.hassble.net

/** HA WebSocket 연결 실패 원인 (UI 표시용). */
enum class ConnectionIssue {
    None,
    AuthFailed,
    NetworkError,
    BridgeNotResponding,
}

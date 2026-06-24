package dev.eigger.hassble.service

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class LogType {
    ADV,     // BLE Advertisement
    TX,      // Transmitted packet / command
    RX,      // Received packet
    NOTIF,   // GATT Notification / OBD Response
    LINK     // Link / Connection Status
}

data class LogEntry(
    val timestamp: String,
    val type: LogType,
    val message: String
)

object LiveEventLogger {
    @Volatile
    var isLiveActive: Boolean = false

    private val _logFlow = MutableSharedFlow<LogEntry>(extraBufferCapacity = 500)
    val logFlow = _logFlow.asSharedFlow()

    private val _logs = mutableListOf<LogEntry>()
    val logs: List<LogEntry>
        get() = synchronized(_logs) { ArrayList(_logs) }

    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    fun log(type: LogType, message: String) {
        if (!isLiveActive) return
        val timestamp = dateFormat.format(Date())
        val entry = LogEntry(timestamp, type, message)
        synchronized(_logs) {
            if (_logs.size >= 500) {
                _logs.removeAt(0)
            }
            _logs.add(entry)
        }
        _logFlow.tryEmit(entry)
    }

    fun clearLogs() {
        synchronized(_logs) {
            _logs.clear()
        }
    }
}

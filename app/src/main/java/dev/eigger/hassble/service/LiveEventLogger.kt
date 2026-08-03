package dev.eigger.hassble.service

import android.content.Context
import androidx.annotation.StringRes
import dev.eigger.hassble.config.HassBleDefaults
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.StateFlow
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
    val id: Long,
    val timestamp: String,
    val type: LogType,
    val message: String,
)

object LiveEventLogger {
    val BUFFER_LIMIT_OPTIONS = HassBleDefaults.LOG_BUFFER_LIMIT_OPTIONS
    const val DEFAULT_MAX_LOGS = HassBleDefaults.DEFAULT_LOG_BUFFER_LIMIT

    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun logRes(type: LogType, @StringRes resId: Int, vararg formatArgs: Any?) {
        val ctx = appContext
        if (ctx == null) {
            log(type, "[$resId]")
            return
        }
        log(type, ctx.getString(resId, *formatArgs))
    }

    @Volatile
    var maxLogs: Int = DEFAULT_MAX_LOGS
        private set

    @Volatile
    var includeAdvLogs: Boolean = false
        private set

    private val _includeAdvLogsFlow = MutableStateFlow(false)
    val includeAdvLogsFlow: StateFlow<Boolean> = _includeAdvLogsFlow.asStateFlow()

    private val _logFlow = MutableSharedFlow<LogEntry>(extraBufferCapacity = BUFFER_LIMIT_OPTIONS.max())
    val logFlow = _logFlow.asSharedFlow()

    // ArrayList.removeAt(0)은 O(n) 시프트라 로그가 잦을 때(WS/OBD TX·RX 등) 누적 비용이 커진다.
    // ArrayDeque는 removeAt(0)/removeFirst()가 O(1)이라 같은 링버퍼 용도에 더 저렴하다.
    private val _logs = ArrayDeque<LogEntry>()
    val logs: List<LogEntry>
        get() = synchronized(_logs) { ArrayList(_logs) }

    private var nextLogId = 0L

    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    fun setMaxLogs(limit: Int) {
        val clamped = limit.coerceIn(BUFFER_LIMIT_OPTIONS.min(), BUFFER_LIMIT_OPTIONS.max())
        maxLogs = clamped
        synchronized(_logs) {
            while (_logs.size > maxLogs) {
                _logs.removeAt(0)
            }
        }
    }

    fun setIncludeAdvLogs(enabled: Boolean, purgeExisting: Boolean = false) {
        includeAdvLogs = enabled
        _includeAdvLogsFlow.value = enabled
        if (!enabled && purgeExisting) {
            removeLogsByType(LogType.ADV)
        }
    }

    fun log(type: LogType, message: String) {
        if (type == LogType.ADV && !includeAdvLogs) return
        val timestamp = dateFormat.format(Date())
        val entry = synchronized(_logs) {
            val id = nextLogId++
            LogEntry(id, timestamp, type, message).also { newEntry ->
                while (_logs.size >= maxLogs) {
                    _logs.removeAt(0)
                }
                _logs.add(newEntry)
            }
        }
        _logFlow.tryEmit(entry)
    }

    fun clearLogs() {
        synchronized(_logs) {
            _logs.clear()
        }
    }

    private fun removeLogsByType(type: LogType) {
        synchronized(_logs) {
            _logs.removeAll { it.type == type }
        }
    }
}

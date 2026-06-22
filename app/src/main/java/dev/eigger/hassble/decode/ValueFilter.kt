package dev.eigger.hassble.decode

import dev.eigger.hassble.config.PublishRule
import dev.eigger.hassble.config.parseDurationMs

/**
 * 값 기반 전송 필터 (통신 완화). 디코딩된 값으로 판정하므로 raw dedup보다 정확.
 * 센서당 1개. min_interval / on_change / deadband / heartbeat 적용.
 */
class ValueFilter(rule: PublishRule) {
    private val minIntervalMs = parseDurationMs(rule.minInterval, 0)
    private val heartbeatMs = parseDurationMs(rule.heartbeat, 0)
    private val deadband = rule.deadband
    private val onChange = rule.onChangeOnly

    private var lastAt = 0L
    private var last: Any? = null

    fun allow(value: Any, now: Long = System.currentTimeMillis()): Boolean {
        if (minIntervalMs > 0 && now - lastAt < minIntervalMs) return false
        val heartbeatDue = heartbeatMs > 0 && now - lastAt >= heartbeatMs
        if (!heartbeatDue && onChange && !changed(value)) return false
        lastAt = now
        last = value
        return true
    }

    private fun changed(value: Any): Boolean {
        val prev = last ?: return true
        if (value is Number && prev is Number) {
            val d = kotlin.math.abs(value.toDouble() - prev.toDouble())
            return if (deadband != null) d >= deadband else value.toDouble() != prev.toDouble()
        }
        return value != prev
    }
}

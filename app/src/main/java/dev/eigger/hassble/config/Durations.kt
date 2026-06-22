package dev.eigger.hassble.config

/** "1s" / "500ms" / "30s" / "5m" / "5" → 밀리초. */
fun parseDurationMs(value: String?, defaultMs: Long = 0): Long {
    if (value.isNullOrBlank()) return defaultMs
    val m = Regex("""^\s*(\d+(?:\.\d+)?)\s*(ms|s|m|h)?\s*$""").find(value) ?: return defaultMs
    val n = m.groupValues[1].toDouble()
    val unit = m.groupValues[2].ifEmpty { "s" }
    val mult = when (unit) { "ms" -> 1.0; "s" -> 1000.0; "m" -> 60000.0; "h" -> 3600000.0; else -> 1000.0 }
    return (n * mult).toLong()
}

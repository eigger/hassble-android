package dev.eigger.hassble.decode

import dev.eigger.hassble.config.DataType
import dev.eigger.hassble.config.DecodeConfig
import dev.eigger.hassble.config.Endian
import net.objecthunter.exp4j.Expression
import net.objecthunter.exp4j.ExpressionBuilder
import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap

/**
 * 바이트 → 값 디코더 (앱 측). 광고/notify/OBD 공유.
 *  - decodeStructured : offset/length/type/endian/scale/map (광고/notify)
 *  - evalFormula      : 응답 바이트 a~t (0~19번째) 식 (OBD)
 *  - parseObdResponse : ELM327 응답 hex → (mode, pid, dataBytes)
 */
object Decoder {

    fun decodeStructured(bytes: ByteArray, c: DecodeConfig): Any? {
        if (c.type == DataType.timestamp) return decodeTimestamp(bytes, c.offset)
        if (c.type == DataType.string) {
            val end = if (c.length <= 0) bytes.size else minOf(c.offset + c.length, bytes.size)
            if (c.offset >= end) return null
            return String(bytes, c.offset, end - c.offset, Charsets.US_ASCII).trimEnd(' ', '\u0000')
        }
        if (c.offset + c.length > bytes.size) return null
        val slice = bytes.copyOfRange(c.offset, c.offset + c.length)
        var raw = toLong(slice, c.type, c.endian)
        c.bitmask?.let { raw = raw and it }
        if (c.map.isNotEmpty()) return c.map[raw.toString()] ?: raw.toString()
        return when (c.type) {
            DataType.timestamp -> decodeTimestamp(bytes, c.offset)
            DataType.float32 -> Float.fromBits(raw.toInt()) * c.scale + c.offsetValue
            else -> raw * c.scale + c.offsetValue
        }
    }

    /** formula에서 응답 바이트를 가리키는 변수명. a=0번째 … t=19번째 바이트. */
    private const val BYTE_NAMES = "abcdefghijklmnopqrst"

    private val IDENTIFIER = Regex("[a-zA-Z_]+")

    private val ALL_BYTE_VARS = BYTE_NAMES.map { it.toString() }.toSet()
    private val BYTE_NAME_STRINGS = Array(BYTE_NAMES.length) { BYTE_NAMES[it].toString() }

    private data class CachedFormula(
        val expression: Expression,
        val maxByteIndexNeeded: Int, // 수식이 요구하는 최대 바이트 인덱스 (a=0, b=1, ...) 없으면 -1
        val usedByteIndices: List<Int>,
    )

    private val expressionCache = ConcurrentHashMap<String, CachedFormula>()

    fun evalFormula(formula: String, data: ByteArray): Double {
        val cached = expressionCache.computeIfAbsent(formula) { f ->
            val expr = ExpressionBuilder(f).variables(ALL_BYTE_VARS).build()
            var maxIdx = -1
            val usedIndices = mutableListOf<Int>()
            for (m in IDENTIFIER.findAll(f)) {
                val name = m.value
                if (name.length == 1 && name[0] in BYTE_NAMES) {
                    val idx = BYTE_NAMES.indexOf(name[0])
                    if (idx !in usedIndices) {
                        usedIndices.add(idx)
                    }
                    if (idx > maxIdx) {
                        maxIdx = idx
                    }
                }
            }
            CachedFormula(
                expression = expr,
                maxByteIndexNeeded = maxIdx,
                usedByteIndices = usedIndices,
            )
        }

        // exp4j는 e/pi/π/φ를 내장 상수로 등록한다. 응답이 짧아 'e'가 바인딩되지 않으면
        // 오일러 상수 2.718로 조용히 평가되므로, 미바인딩 바이트 변수는 명시적으로 거른다.
        if (cached.maxByteIndexNeeded >= data.size) {
            val neededIndex = cached.usedByteIndices.firstOrNull { it >= data.size } ?: cached.maxByteIndexNeeded
            val neededChar = BYTE_NAMES[neededIndex]
            throw IllegalArgumentException(
                "formula '$formula' needs byte '$neededChar' but response has only ${data.size} byte(s)",
            )
        }

        val vars = buildMap {
            for (i in data.indices.take(BYTE_NAMES.length)) {
                put(BYTE_NAME_STRINGS[i], (data[i].toInt() and 0xFF).toDouble())
            }
        }

        return synchronized(cached.expression) {
            cached.expression.setVariables(vars).evaluate()
        }
    }

    /** ELM327 응답 hex → (mode, pid, dataBytes). ISO-TP 정규화 후 파싱. */
    fun parseObdResponse(rawHex: String): Triple<String, String, ByteArray>? {
        val normalized = ObdResponseParser.normalizeElm327Response(rawHex)
            ?: rawHex.trim().replace(" ", "").takeIf { it.length >= 4 }
            ?: return null
        return parseObdPayloadHex(normalized)
    }

    /** 이미 정규화된 payload hex (예: 410C1AF8) 파싱. */
    fun parseObdPayloadHex(h: String): Triple<String, String, ByteArray>? {
        if (h.length < 4) return null
        val respMode = h.substring(0, 2).toIntOrNull(16) ?: return null
        val reqMode = respMode - 0x40
        if (reqMode < 0) return null
        val mode = "%02X".format(reqMode)
        val pidLen = if (mode == "22") 4 else 2
        if (h.length < 2 + pidLen) return null
        val pid = h.substring(2, 2 + pidLen).uppercase()
        val data = hexToBytes(h.substring(2 + pidLen)) ?: return null
        return Triple(mode, pid, data)
    }

    private fun toLong(b: ByteArray, type: DataType, endian: Endian): Long {
        val ordered = if (endian == Endian.little) b.reversedArray() else b
        var v = 0L
        for (byte in ordered) v = (v shl 8) or (byte.toLong() and 0xFF)
        return when (type) {
            DataType.int8 -> v.toByte().toLong()
            DataType.int16 -> v.toShort().toLong()
            DataType.int32 -> v.toInt().toLong()
            else -> v
        }
    }

    /** offset부터 4바이트: month, day, hour, minute → ISO 8601 (연도는 현재 연도) + timezone offset. */
    private fun decodeTimestamp(bytes: ByteArray, offset: Int): String? {
        if (offset + 4 > bytes.size) return null
        val month = bytes[offset].toInt() and 0xFF
        val day = bytes[offset + 1].toInt() and 0xFF
        val hour = bytes[offset + 2].toInt() and 0xFF
        val minute = bytes[offset + 3].toInt() and 0xFF
        if (month !in 1..12 || day !in 1..31 || hour !in 0..23 || minute !in 0..59) return null
        val cal = Calendar.getInstance().apply {
            set(Calendar.MONTH, month - 1)
            set(Calendar.DAY_OF_MONTH, day)
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val year = cal.get(Calendar.YEAR)
        val offsetMs = cal.timeZone.getOffset(cal.timeInMillis)
        val offsetHours = Math.abs(offsetMs / 3600000)
        val offsetMinutes = Math.abs((offsetMs / 60000) % 60)
        val sign = if (offsetMs >= 0) "+" else "-"
        val tzOffset = "%s%02d:%02d".format(sign, offsetHours, offsetMinutes)
        return "%04d-%02d-%02dT%02d:%02d:00%s".format(year, month, day, hour, minute, tzOffset)
    }

    fun hexToBytes(h: String): ByteArray? {
        if (h.length % 2 != 0) return null
        return runCatching {
            ByteArray(h.length / 2) { h.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        }.getOrNull()
    }
}

package dev.eigger.hassble.decode

import dev.eigger.hassble.config.DataType
import dev.eigger.hassble.config.DecodeConfig
import dev.eigger.hassble.config.Endian
import net.objecthunter.exp4j.ExpressionBuilder
import java.util.Calendar

/**
 * 바이트 → 값 디코더 (앱 측). 광고/notify/OBD 공유.
 *  - decodeStructured : offset/length/type/endian/scale/map (광고/notify)
 *  - evalFormula      : 응답 바이트 a,b,c,d... 식 (OBD)
 *  - parseObdResponse : ELM327 응답 hex → (mode, pid, dataBytes)
 */
object Decoder {

    fun decodeStructured(bytes: ByteArray, c: DecodeConfig): Any? {
        if (c.offset + c.length > bytes.size) return null
        if (c.type == DataType.timestamp) return decodeTimestamp(bytes, c.offset)
        if (c.type == DataType.string) {
            val slice = bytes.copyOfRange(c.offset, c.offset + c.length)
            return slice.map { (it.toInt() and 0xFF).toChar() }.joinToString("")
        }
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

    fun evalFormula(formula: String, data: ByteArray): Double {
        val names = "abcdefgh"
        val vars = buildMap {
            for (i in data.indices.take(names.length)) {
                put(names[i].toString(), (data[i].toInt() and 0xFF).toDouble())
            }
        }
        return ExpressionBuilder(formula).variables(vars.keys).build().setVariables(vars).evaluate()
    }

    /** "410C1AF8" → (01, 0C, [1A F8]); mode 22는 PID 2바이트. 응답 mode = 요청+0x40. */
    fun parseObdResponse(rawHex: String): Triple<String, String, ByteArray>? {
        val h = rawHex.trim().replace(" ", "")
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

    /** offset부터 4바이트: month, day, hour, minute → ISO 8601 (연도는 현재 연도). */
    private fun decodeTimestamp(bytes: ByteArray, offset: Int): String? {
        if (offset + 4 > bytes.size) return null
        val month = bytes[offset].toInt() and 0xFF
        val day = bytes[offset + 1].toInt() and 0xFF
        val hour = bytes[offset + 2].toInt() and 0xFF
        val minute = bytes[offset + 3].toInt() and 0xFF
        if (month !in 1..12 || day !in 1..31 || hour !in 0..23 || minute !in 0..59) return null
        val year = Calendar.getInstance().get(Calendar.YEAR)
        return "%04d-%02d-%02dT%02d:%02d:00".format(year, month, day, hour, minute)
    }

    fun hexToBytes(h: String): ByteArray? {
        if (h.length % 2 != 0) return null
        return runCatching {
            ByteArray(h.length / 2) { h.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        }.getOrNull()
    }
}

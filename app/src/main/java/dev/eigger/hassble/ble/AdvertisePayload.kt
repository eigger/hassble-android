package dev.eigger.hassble.ble

object AdvertisePayload {
    /** legacy 광고 31바이트 - flags AD(3) - AD 헤더(2) - company ID(2) = 24바이트 */
    const val MAX_MANUFACTURER_PAYLOAD = 24

    private val TOKEN_REGEX = Regex("""\{counter(?::([^}]+))?\}""")

    /** counter는 1바이트 순환 (0~255). */
    fun nextCounter(current: Int): Int = (current + 1) and 0xFF

    /**
     * {counter} → 10진수, {counter:02X} → String.format 적용.
     * 토큰이 없으면 원문 그대로 반환한다.
     */
    fun render(template: String, counter: Int): String {
        return TOKEN_REGEX.replace(template) { m ->
            val fmt = m.groupValues[1]
            if (fmt.isEmpty()) {
                counter.toString()
            } else {
                runCatching { String.format("%$fmt", counter) }.getOrElse { counter.toString() }
            }
        }
    }

    /** 렌더된 hex를 바이트로. 홀수 길이·비 hex 문자면 null. */
    fun toBytes(rendered: String): ByteArray? {
        val clean = rendered.trim()
        if (clean.length % 2 != 0) return null
        val result = ByteArray(clean.length / 2)
        for (i in clean.indices step 2) {
            val high = Character.digit(clean[i], 16)
            val low = Character.digit(clean[i + 1], 16)
            if (high == -1 || low == -1) return null
            result[i / 2] = ((high shl 4) or low).toByte()
        }
        return result
    }

    /** 설정 검증용. 성공하면 null, 실패하면 사람이 읽을 수 있는 사유. */
    fun validationError(template: String): String? {
        if (template.isBlank()) return "payload cannot be blank"
        val testCounters = listOf(0, 255)
        for (c in testCounters) {
            val rendered = try {
                TOKEN_REGEX.replace(template) { m ->
                    val fmt = m.groupValues[1]
                    if (fmt.isEmpty()) c.toString() else String.format("%$fmt", c)
                }
            } catch (e: Exception) {
                return "invalid token format in payload: ${e.message}"
            }
            val bytes = toBytes(rendered)
                ?: return "payload '$rendered' is not a valid hex string"
            if (bytes.isEmpty()) {
                return "payload cannot be empty"
            }
            if (bytes.size > MAX_MANUFACTURER_PAYLOAD) {
                return "payload size (${bytes.size} bytes) exceeds maximum legacy advertisement limit ($MAX_MANUFACTURER_PAYLOAD bytes)"
            }
        }
        return null
    }
}

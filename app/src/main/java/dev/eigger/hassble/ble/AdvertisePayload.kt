package dev.eigger.hassble.ble

import dev.eigger.hassble.config.AdvertisePayloadPhase

object AdvertisePayload {
    /** legacy 광고 31바이트 - flags AD(3) - AD 헤더(2) - company ID(2) = 24바이트 */
    const val MAX_MANUFACTURER_PAYLOAD = 24

    private val TOKEN_REGEX = Regex("""\{(counter|state)(?::([^}]+))?\}""")

    /** counter는 1바이트 순환 (0~255). 0xFF 다음은 0. */
    fun nextCounter(current: Int): Int = (current + 1) and 0xFF

    fun hasStateToken(template: String): Boolean = TOKEN_REGEX.containsMatchIn(template) &&
        TOKEN_REGEX.findAll(template).any { it.groupValues[1] == "state" }

    fun phaseTemplate(basePayload: String, phase: AdvertisePayloadPhase?): String {
        val override = phase?.payload?.trim().orEmpty()
        return override.ifEmpty { basePayload }
    }

    fun phaseState(phase: AdvertisePayloadPhase?): Int = (phase?.state ?: 0) and 0xFF

    /**
     * {counter} / {state} → 10진수, {counter:02X} / {state:02X} → String.format 적용.
     * 토큰이 없으면 원문 그대로 반환한다.
     */
    fun render(template: String, counter: Int, state: Int = 0): String {
        return TOKEN_REGEX.replace(template) { m ->
            val value = if (m.groupValues[1] == "state") state else counter
            val fmt = m.groupValues[2]
            if (fmt.isEmpty()) {
                value.toString()
            } else {
                runCatching { String.format("%$fmt", value) }.getOrElse { value.toString() }
            }
        }
    }

    fun renderPhase(basePayload: String, counter: Int, phase: AdvertisePayloadPhase?): String {
        return render(phaseTemplate(basePayload, phase), counter, phaseState(phase))
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
    fun validationError(template: String, counters: List<Int> = listOf(0, 255), states: List<Int> = listOf(0, 255)): String? {
        if (template.isBlank()) return "payload cannot be blank"
        for (c in counters) {
            for (s in states) {
                val rendered = try {
                    render(template, c, s)
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
        }
        return null
    }
}

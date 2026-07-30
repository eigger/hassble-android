package dev.eigger.hassble.decode

/**
 * ELM327 텍스트 응답 → ISO-TP 재조립 → OBD payload hex.
 * ATH0/ATS0 기준. 멀티프레임(0x10/0x2N)과 단일프레임(0x0N) PCI를 처리한다.
 */
object ObdResponseParser {

    /** ELM327 응답 문자열을 mode+pid+data hex로 정규화. 실패 시 null. */
    fun normalizeElm327Response(response: String): String? {
        val upper = response.uppercase()
        if (upper.contains("NO DATA") || upper.contains("UNABLE TO CONNECT") ||
            upper.contains("BUS INIT") || upper.contains("CAN ERROR")
        ) {
            return null
        }
        val bytes = extractPayloadBytes(response) ?: return null
        if (bytes.isEmpty()) return null
        val payload = reassembleIsotp(bytes)
        if (payload.isEmpty()) return null
        return payload.joinToString("") { "%02X".format(it) }
    }

    /**
     * 정규화된 payload가 `7F <service> <nrc>` 부정 응답이면 사람이 읽을 설명을, 아니면 null.
     *
     * 부정 응답은 정규화 자체는 성공하므로(0x7F가 응답 모드 범위에 들어간다) 값 경로로 흘러가
     * 조용히 버려진다. ECU가 "그 PID는 못 준다"고 답한 것과 아예 응답이 없는 것을 로그에서
     * 구분하려면 여기서 따로 집어내야 한다.
     */
    fun explainNegativeResponse(payloadHex: String): String? {
        val hex = payloadHex.uppercase().filter { it.isDigit() || it in 'A'..'F' }
        if (!hex.startsWith("7F") || hex.length < 6) return null
        val service = hex.substring(2, 4)
        val nrc = hex.substring(4, 6)
        return "negative response to service $service: NRC $nrc ${nrcName(nrc)}"
    }

    private fun nrcName(nrc: String): String = when (nrc) {
        "10" -> "(generalReject)"
        "11" -> "(serviceNotSupported)"
        "12" -> "(subFunctionNotSupported)"
        "13" -> "(incorrectMessageLengthOrInvalidFormat)"
        "21" -> "(busyRepeatRequest)"
        "22" -> "(conditionsNotCorrect)"
        "24" -> "(requestSequenceError)"
        "31" -> "(requestOutOfRange)"
        "33" -> "(securityAccessDenied)"
        "78" -> "(responsePending)"
        else -> "(unknown)"
    }

    /** ELM327 응답에서 16진 바이트열 추출 (줄 번호·공백·프롬프트 제거). */
    fun extractPayloadBytes(response: String): ByteArray? {
        val hex = buildString {
            for (line in response.split('\r', '\n')) {
                var trimmed = line.trim().removePrefix(">").trim()
                if (trimmed.isEmpty()) continue
                if (trimmed.equals("SEARCHING...", ignoreCase = true)) continue
                if (trimmed.matches(Regex("""\d{3}"""))) continue
                if (':' in trimmed) trimmed = trimmed.substringAfter(':').trim()
                for (c in trimmed) {
                    if (c.isDigit() || c in 'a'..'f' || c in 'A'..'F') append(c)
                }
            }
        }
        if (hex.length < 4 || hex.length % 2 != 0) return null
        return Decoder.hexToBytes(hex)
    }

    /**
     * ISO-TP PCI 제거 및 멀티프레임 재조립.
     * 이미 0x40+ 모드 바이트로 시작하면 그대로 반환.
     */
    fun reassembleIsotp(bytes: ByteArray): ByteArray {
        if (bytes.isEmpty()) return bytes
        val b0 = bytes[0].toInt() and 0xFF

        if (b0 in 0x40..0x7F) return bytes

        if ((b0 and 0xF0) == 0x00 && b0 in 0x01..0x0F) {
            val len = b0 and 0x0F
            if (bytes.size >= 1 + len) return bytes.copyOfRange(1, 1 + len)
        }

        for (i in bytes.indices) {
            val pci = bytes[i].toInt() and 0xFF
            if ((pci and 0xF0) == 0x10) return reassembleFromFirstFrame(bytes, i)
        }

        return bytes
    }

    private fun reassembleFromFirstFrame(bytes: ByteArray, start: Int): ByteArray {
        if (start + 2 >= bytes.size) return bytes
        val pci = bytes[start].toInt() and 0xFF
        val totalLen = ((pci and 0x0F) shl 8) or (bytes[start + 1].toInt() and 0xFF)
        val out = mutableListOf<Byte>()
        out.addAll(bytes.copyOfRange(start + 2, minOf(start + 8, bytes.size)).toList())

        var expectedSeq = 1
        var pos = start + 8
        while (out.size < totalLen && pos < bytes.size) {
            val cpci = bytes[pos].toInt() and 0xFF
            if ((cpci and 0xF0) == 0x20) {
                val seq = cpci and 0x0F
                if (seq == expectedSeq) {
                    out.addAll(bytes.copyOfRange(pos + 1, minOf(pos + 8, bytes.size)).toList())
                    expectedSeq = (expectedSeq + 1) and 0x0F
                    pos += 8
                    continue
                }
            }
            pos++
        }
        return out.take(totalLen).toByteArray()
    }
}

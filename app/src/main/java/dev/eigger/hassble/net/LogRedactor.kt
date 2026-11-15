package dev.eigger.hassble.net

/** 라이브 로그(공유/내보내기됨)에 자격 증명이 남지 않도록 JSON 텍스트의 민감한 값을 가린다. */
object LogRedactor {
    private val SENSITIVE = Regex(
        """("(?:access_token|refresh_token|token|password|client_secret|api_key|authorization)"\s*:\s*)"(?:[^"\\]|\\.)*"""",
        RegexOption.IGNORE_CASE,
    )

    fun redact(text: String): String = SENSITIVE.replace(text) { "${it.groupValues[1]}\"***\"" }
}

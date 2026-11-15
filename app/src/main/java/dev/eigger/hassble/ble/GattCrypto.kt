package dev.eigger.hassble.ble

import dev.eigger.hassble.config.GattEncryption
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.withTimeoutOrNull
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * GATT 페이로드 AES-128-CBC 암복호화 (패딩 없음). Ancel BM6/BM7 같은 기기용.
 * 메시지는 정확히 16바이트 블록의 배수여야 하며, 각 notify/write는 독립된 CBC 메시지(IV 고정)로 취급한다.
 */
object GattCrypto {
    const val BLOCK = 16

    private val ZERO_IV = ByteArray(BLOCK)

    fun hexToBytes(hex: String): ByteArray? {
        val h = hex.replace(" ", "")
        if (h.length % 2 != 0 || !h.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
        return ByteArray(h.length / 2) { h.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    fun bytesToHex(bytes: ByteArray): String = bytes.joinToString("") { "%02X".format(it) }

    /** key_hex가 32자 hex(AES-128)이고 iv_hex가 있으면 32자 hex인지. */
    fun isValid(enc: GattEncryption): Boolean =
        hexToBytes(enc.keyHex)?.size == BLOCK && (enc.ivHex?.let { hexToBytes(it)?.size == BLOCK } ?: true)

    private fun keyBytes(enc: GattEncryption) = requireNotNull(hexToBytes(enc.keyHex)) { "invalid key_hex" }
    private fun ivBytes(enc: GattEncryption) = enc.ivHex?.let { requireNotNull(hexToBytes(it)) { "invalid iv_hex" } } ?: ZERO_IV

    private fun run(mode: Int, enc: GattEncryption, data: ByteArray): ByteArray {
        require(data.isNotEmpty() && data.size % BLOCK == 0) { "payload must be a multiple of $BLOCK bytes (was ${data.size})" }
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        cipher.init(mode, SecretKeySpec(keyBytes(enc), "AES"), IvParameterSpec(ivBytes(enc)))
        return cipher.doFinal(data)
    }

    fun encrypt(enc: GattEncryption, plain: ByteArray): ByteArray = run(Cipher.ENCRYPT_MODE, enc, plain)
    fun decrypt(enc: GattEncryption, cipher: ByteArray): ByteArray = run(Cipher.DECRYPT_MODE, enc, cipher)

    /** 암호화가 설정돼 있으면 암호화, 아니면 그대로. */
    fun encryptIfNeeded(enc: GattEncryption?, plain: ByteArray): ByteArray =
        if (enc == null) plain else encrypt(enc, plain)

    /** 수신 payload → 평문. 암호화 설정이 있고 16바이트 배수가 아니면 null(버림). */
    fun decryptIfNeeded(enc: GattEncryption?, payload: ByteArray): ByteArray? {
        if (enc == null) return payload
        if (payload.isEmpty() || payload.size % BLOCK != 0) return null
        return decrypt(enc, payload)
    }

    /** response_prefix 필터. prefix가 null/빈 문자열이면 모두 통과. 대소문자 무시. */
    fun matchesPrefix(hex: String, prefix: String?): Boolean {
        val p = prefix?.replace(" ", "").orEmpty()
        return p.isEmpty() || hex.startsWith(p, ignoreCase = true)
    }

    /**
     * 폴링 응답 대기: notify payload 흐름에서 (복호화 후) prefix를 통과하는 첫 응답의 hex를 돌려준다.
     * 복호화 불가(길이 불일치)·prefix 불일치(예: BM7 ack)는 건너뛴다. 제한 시간 내 없으면 null.
     */
    suspend fun awaitResponse(
        notifications: Flow<ByteArray>,
        enc: GattEncryption?,
        prefix: String?,
        timeoutMs: Long,
        onDropped: (String) -> Unit = {},
    ): String? = withTimeoutOrNull(timeoutMs) {
        notifications.mapNotNull { payload ->
            val plain = runCatching { decryptIfNeeded(enc, payload) }.getOrNull()
            if (plain == null) {
                onDropped("undecryptable payload (${payload.size} bytes)")
                return@mapNotNull null
            }
            val hex = bytesToHex(plain)
            if (matchesPrefix(hex, prefix)) hex else {
                onDropped("filtered by response_prefix: $hex")
                null
            }
        }.firstOrNull()
    }
}

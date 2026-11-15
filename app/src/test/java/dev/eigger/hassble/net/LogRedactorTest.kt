package dev.eigger.hassble.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogRedactorTest {
    @Test
    fun `auth message access_token is redacted`() {
        val out = LogRedactor.redact("""{"type":"auth","access_token":"abc.DEF-123"}""")
        assertEquals("""{"type":"auth","access_token":"***"}""", out)
    }

    @Test
    fun `refresh_token is redacted`() {
        val out = LogRedactor.redact("""{"access_token":"a1","refresh_token":"r2","expires_in":1800}""")
        assertEquals("""{"access_token":"***","refresh_token":"***","expires_in":1800}""", out)
    }

    @Test
    fun `escaped quote inside value does not leak the tail`() {
        val out = LogRedactor.redact("""{"password":"ab\"cd"}""")
        assertEquals("""{"password":"***"}""", out)
    }

    @Test
    fun `normal messages are unchanged`() {
        val msg = """{"id":5,"type":"ws_bridge/state","entity_id":"sensor.x","state":"12.5"}"""
        assertEquals(msg, LogRedactor.redact(msg))
    }

    @Test
    fun `token value is absent from output`() {
        val secret = "SuperSecretToken123"
        val out = LogRedactor.redact("""{"type":"auth","access_token":"$secret"}""")
        assertFalse(out.contains(secret))
        assertTrue(out.contains("***"))
    }
}

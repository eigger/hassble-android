package dev.eigger.hassble.config

import com.charleskorn.kaml.Yaml
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `presence_timeout`(광고 끊김 판정)과 앱이 자동으로 붙이는 진단 엔티티의 예약 키 검증.
 */
class PresenceTimeoutTest {

    private fun parse(devicesYaml: String): GatewayConfig =
        Yaml.default.decodeFromString(GatewayConfig.serializer(), "devices:\n$devicesYaml")

    // trimIndent 없이 2칸 들여쓰기 그대로 둔다 — 아래에서 줄을 덧붙일 때 열이 맞아야 한다.
    private val baseAdv = """
  - id: door
    name: "Door"
    source: advertisement
    match:
      service_data_uuid: "181a"
"""

    @Test
    fun `defaults to 5 minutes and parses to millis`() {
        val d = parse(baseAdv).devices.single()
        assertEquals("5m", d.presenceTimeout)
        assertEquals(300_000L, parseDurationMs(d.presenceTimeout, 0))
        assertTrue(ConfigValidator.validate(parse(baseAdv)).isEmpty())
    }

    @Test
    fun `zero disables without any issue`() {
        val cfg = parse("$baseAdv    presence_timeout: \"0\"\n")
        assertEquals(0L, parseDurationMs(cfg.devices.single().presenceTimeout, 0))
        assertTrue(ConfigValidator.validate(cfg).isEmpty())
    }

    @Test
    fun `unparsable value silently parses to 0 so the validator warns`() {
        val cfg = parse("$baseAdv    presence_timeout: 5min\n")
        assertEquals(0L, parseDurationMs(cfg.devices.single().presenceTimeout, 0))
        val issue = ConfigValidator.validate(cfg).single()
        assertEquals(ValidationLevel.WARNING, issue.level)
        assertEquals("devices[door].presence_timeout", issue.yamlPath)
        assertTrue(issue.message.contains("not a duration"))
    }

    @Test
    fun `isValidDuration mirrors parseDurationMs grammar`() {
        for (ok in listOf("0", "5", "5s", "500ms", "2.5m", "1h", " 30s ")) assertTrue(ok, ConfigValidator.isValidDuration(ok))
        for (bad in listOf("", "5min", "abc", "-1s", "5 minutes")) assertFalse(bad, ConfigValidator.isValidDuration(bad))
    }

    @Test
    fun `presence_timeout on a non-advertisement device warns`() {
        val yaml = """
  - id: obd
    name: "OBD"
    source: obd
    presence_timeout: 1m
    obd:
      mac: "AA:BB:CC:DD:EE:FF"
"""
        val issues = ConfigValidator.validate(parse(yaml))
        assertTrue(issues.any { it.level == ValidationLevel.WARNING && it.yamlPath == "devices[obd].presence_timeout" })
    }

    @Test
    fun `sensor key colliding with the built-in advertisement entity is an error`() {
        val cfg = parse(
            baseAdv +
                "    sensors:\n" +
                "      - key: advertisement\n" +
                "        platform: text_sensor\n" +
                "        source_field: service_data\n" +
                "        decode: { offset: 0, length: 0, type: string }\n"
        )
        val issues = ConfigValidator.validate(cfg)
        assertTrue(issues.any { it.level == ValidationLevel.ERROR && it.sensorKey == "advertisement" })
        assertEquals(setOf("advertisement"), ConfigValidator.errorKeys(issues, "door"))
    }

    @Test
    fun `reserved keys depend on source and advertise block`() {
        val adv = parse(baseAdv).devices.single()
        assertEquals(setOf("advertisement"), ConfigValidator.reservedKeys(adv))
        val gatt = adv.copy(source = Source.gatt_notify, match = null,
            gatt = GattConfig(serviceUuid = "180F", notifyCharUuid = "2A19"))
        assertEquals(setOf("link_status"), ConfigValidator.reservedKeys(gatt))
    }
}

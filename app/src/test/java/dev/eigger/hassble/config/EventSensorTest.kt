package dev.eigger.hassble.config

import com.charleskorn.kaml.Yaml
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EventSensorTest {

    /** [publishLine]은 센서 블록에 그대로 끼워 넣을 한 줄 (비우면 defaults.publish 상속). */
    private fun parse(publishLine: String = ""): GatewayConfig {
        val yaml = """
            devices:
              - id: apt_key_door
                name: "APT Door Event"
                source: advertisement
                instance_mode: shared
                match:
                  manufacturer_id: 861
                  manufacturer_hex_prefix: "02"
                sensors:
                  - key: door_event
                    platform: event
                    event_types: [entering, entered, exiting, idle]
                    source_field: manufacturer_data
                    length: 6
                    $publishLine
                    decode:
                      offset: 4
                      length: 1
                      type: uint8
                      map:
                        "65": "entering"
                        "1": "entered"
                        "64": "exiting"
                        "0": "idle"
        """.trimIndent()
        return Yaml.default.decodeFromString(GatewayConfig.serializer(), yaml)
    }

    private val withHeartbeat = "publish: { heartbeat: 60s }"

    private fun firstSensor(config: GatewayConfig) = config.devices[0].sensors[0]

    private fun replaceEventTypes(config: GatewayConfig, types: List<String>): GatewayConfig {
        val d = config.devices[0]
        return config.copy(devices = listOf(d.copy(sensors = listOf(d.sensors[0].copy(eventTypes = types)))))
    }

    @Test
    fun `event_types is parsed from yaml`() {
        val s = firstSensor(parse(withHeartbeat))
        assertEquals("event", s.platform)
        assertEquals(listOf("entering", "entered", "exiting", "idle"), s.eventTypes)
        assertEquals("60s", s.publish?.heartbeat)
    }

    @Test
    fun `event sensor with heartbeat passes validation`() {
        val issues = ConfigValidator.validate(parse(withHeartbeat))
        assertTrue(issues.toString(), issues.none { it.sensorKey == "door_event" })
    }

    @Test
    fun `event sensor without event_types is an error and gets skipped`() {
        val issues = ConfigValidator.validate(replaceEventTypes(parse(withHeartbeat), emptyList()))
        assertTrue(issues.any { it.level == ValidationLevel.ERROR && it.message.contains("event_types") })
        assertTrue("door_event" in ConfigValidator.errorKeys(issues, "apt_key_door"))
    }

    @Test
    fun `map value missing from event_types warns`() {
        val issues = ConfigValidator.validate(replaceEventTypes(parse(withHeartbeat), listOf("entering")))
        val issue = issues.single {
            it.level == ValidationLevel.WARNING && it.message.contains("missing from event_types")
        }
        assertTrue(issue.message, issue.message.contains("entered"))
        assertTrue(issue.message, issue.message.contains("idle"))
    }

    @Test
    fun `on_change_only without heartbeat warns that repeats are suppressed`() {
        // publish 미지정 → defaults.publish(on_change_only=true, heartbeat 없음) 상속
        val issues = ConfigValidator.validate(parse())
        assertTrue(issues.any {
            it.level == ValidationLevel.WARNING && it.message.contains("on_change_only suppresses repeats")
        })
    }

    @Test
    fun `unparseable heartbeat still warns about suppressed repeats`() {
        // "60sec"은 parseDurationMs 규격(ms|s|m|h) 밖이라 0으로 떨어져 heartbeat가 꺼진다.
        val issues = ConfigValidator.validate(parse("publish: { heartbeat: 60sec }"))
        assertTrue(issues.any { it.message.contains("on_change_only suppresses repeats") })
    }

    @Test
    fun `fingerprint ignores numeric meta that event never declares`() {
        val base = parse(withHeartbeat)
        val d = base.devices[0]
        val withMeta = base.copy(
            devices = listOf(d.copy(sensors = listOf(d.sensors[0].copy(unit = "x", accuracyDecimals = 1)))),
        )
        assertEquals(
            ConfigValidator.computeEffectiveFingerprint(base, "apt_key_door"),
            ConfigValidator.computeEffectiveFingerprint(withMeta, "apt_key_door"),
        )
    }

    @Test
    fun `on_change_only false does not warn`() {
        val issues = ConfigValidator.validate(parse("publish: { on_change_only: false }"))
        assertFalse(issues.any { it.message.contains("on_change_only suppresses repeats") })
    }
}

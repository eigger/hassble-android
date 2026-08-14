package dev.eigger.hassble.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EntityMsgSerializationTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        explicitNulls = false
    }

    @Test
    fun `entity message always includes ws_bridge type`() {
        val text = json.encodeToString(
            EntityMsg.serializer(),
            EntityMsg(
                id = 1,
                uniqueId = "jaalee_jht_F2D5BCDD839C_humidity",
                platform = "sensor",
                name = "Humidity",
                device = DeviceRef("jaalee_jht_F2D5BCDD839C", "Jaalee JHT"),
                deviceClass = "humidity",
                unit = "%",
                stateClass = "measurement",
            ),
        )
        assertTrue(text.contains("\"type\":\"ws_bridge/entity\""))
    }

    @Test
    fun `entity message includes icon when present`() {
        val text = json.encodeToString(
            EntityMsg.serializer(),
            EntityMsg(
                id = 2,
                uniqueId = "parking_floor",
                platform = "sensor",
                name = "Parking Floor",
                icon = "mdi:layers-outline",
            ),
        )
        assertTrue(text.contains("\"icon\":\"mdi:layers-outline\""))
    }

    @Test
    fun `command payload deserialization with value`() {
        val jsonString = """{"kind":"command","unique_id":"switch_01","action":"set_value","value":25.5}"""
        val cmd = json.decodeFromString(CommandPayload.serializer(), jsonString)
        assertEquals("command", cmd.kind)
        assertEquals("switch_01", cmd.uniqueId)
        assertEquals("set_value", cmd.action)
        assertEquals(25.5, cmd.value?.jsonPrimitive?.double ?: 0.0, 0.001)
        assertNull(cmd.params)
    }

    @Test
    fun `command payload deserialization with params`() {
        val jsonString = """{"kind":"command","unique_id":"valve_01","action":"set_valve_position","params":{"position":40}}"""
        val cmd = json.decodeFromString(CommandPayload.serializer(), jsonString)
        assertEquals("command", cmd.kind)
        assertEquals("valve_01", cmd.uniqueId)
        assertEquals("set_valve_position", cmd.action)
        assertNull(cmd.value)
        assertNotNull(cmd.params)
        assertEquals(40, cmd.params?.get("position")?.jsonPrimitive?.double?.toInt())
    }
}

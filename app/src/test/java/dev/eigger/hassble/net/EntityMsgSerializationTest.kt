package dev.eigger.hassble.net

import kotlinx.serialization.json.Json
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
}


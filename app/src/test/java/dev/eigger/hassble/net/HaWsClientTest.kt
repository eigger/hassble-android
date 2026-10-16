package dev.eigger.hassble.net

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import okhttp3.WebSocket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Field
import java.lang.reflect.Method

class HaWsClientTest {

    private val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())

    private fun getPrivateField(obj: Any, fieldName: String): Field {
        var cls: Class<*>? = obj.javaClass
        while (cls != null) {
            try {
                val f = cls.getDeclaredField(fieldName)
                f.isAccessible = true
                return f
            } catch (e: NoSuchFieldException) {
                cls = cls.superclass
            }
        }
        throw NoSuchFieldException(fieldName)
    }

    private fun getPrivateMethod(obj: Any, methodName: String, vararg paramTypes: Class<*>): Method {
        val m = obj.javaClass.getDeclaredMethod(methodName, *paramTypes)
        m.isAccessible = true
        return m
    }

    @Test
    fun `sendStates while disconnected enqueues messages without dropping`() {
        val client = HaWsClient(
            baseUrl = "http://127.0.0.1:8123",
            token = "dummy_token",
            gatewayId = "gw1",
            gatewayName = "Gateway 1",
            scope = scope,
        )

        client.sendStates(listOf("sensor.temp" to 25.0))
        assertEquals(1, client.pendingMessageCount)

        client.sendStates(listOf("sensor.humidity" to 50.0))
        assertEquals(2, client.pendingMessageCount)
    }

    @Test
    fun `triggerReconnection preserves pendingMessages and sets reconnectJob`() {
        val client = HaWsClient(
            baseUrl = "http://127.0.0.1:8123",
            token = "dummy_token",
            gatewayId = "gw1",
            gatewayName = "Gateway 1",
            scope = scope,
        )

        client.sendStates(listOf("sensor.temp" to 25.0))
        assertEquals(1, client.pendingMessageCount)

        // Invoke private triggerReconnection()
        val triggerMethod = getPrivateMethod(client, "triggerReconnection")
        triggerMethod.invoke(client)

        // pendingMessages should NOT be cleared on reconnection trigger
        assertEquals(1, client.pendingMessageCount)

        // reconnectJob should be active
        val reconnectJobField = getPrivateField(client, "reconnectJob")
        val reconnectJob = reconnectJobField.get(client) as? Job
        assertTrue("reconnectJob should be active", reconnectJob?.isActive == true)

        // Second call to triggerReconnection should not replace the active reconnectJob
        val firstJob = reconnectJob
        triggerMethod.invoke(client)
        val currentJob = reconnectJobField.get(client) as? Job
        assertEquals("Active reconnectJob should be retained without duplication", firstJob, currentJob)

        // close() should cancel reconnectJob and clear pendingMessages
        client.close()
        assertFalse("reconnectJob should be cancelled after close()", currentJob?.isActive == true)
        assertEquals(0, client.pendingMessageCount)
    }

    @Test
    fun `disconnect cancels reconnectJob and clears pendingMessages`() {
        val client = HaWsClient(
            baseUrl = "http://127.0.0.1:8123",
            token = "dummy_token",
            gatewayId = "gw1",
            gatewayName = "Gateway 1",
            scope = scope,
        )

        client.sendStates(listOf("sensor.temp" to 25.0))
        val triggerMethod = getPrivateMethod(client, "triggerReconnection")
        triggerMethod.invoke(client)

        val reconnectJobField = getPrivateField(client, "reconnectJob")
        val reconnectJob = reconnectJobField.get(client) as? Job
        assertTrue("reconnectJob should be active", reconnectJob?.isActive == true)

        client.disconnect()
        assertFalse("reconnectJob should be cancelled after disconnect()", reconnectJob?.isActive == true)
        assertEquals(0, client.pendingMessageCount)
    }
}

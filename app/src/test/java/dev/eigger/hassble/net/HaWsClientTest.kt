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

    @Test
    fun `connect with invalid url recovers to Disconnected, sets NetworkError, and schedules reconnect`() {
        val client = HaWsClient(
            baseUrl = "http://invalid url:8123:not:valid",
            token = "dummy_token",
            gatewayId = "gw1",
            gatewayName = "Gateway 1",
            scope = scope,
        )

        client.connect()
        // State should not remain Connecting
        assertEquals(ConnectionState.Disconnected, client.connectionState.value)
        assertEquals(ConnectionIssue.NetworkError, client.connectionIssue.value)

        val reconnectJobField = getPrivateField(client, "reconnectJob")
        val firstReconnectJob = reconnectJobField.get(client) as? Job
        assertTrue("First reconnectJob should be scheduled", firstReconnectJob?.isActive == true)
        client.close()
    }

    @Test
    fun `reconnect loop continues when connect fails inside reconnectJob coroutine`() {
        val client = HaWsClient(
            baseUrl = "http://invalid url:8123:not:valid",
            token = "dummy_token",
            gatewayId = "gw1",
            gatewayName = "Gateway 1",
            scope = scope,
        )

        val delayField = getPrivateField(client, "reconnectDelayMs")
        val reconnectJobField = getPrivateField(client, "reconnectJob")

        // reconnectImmediately() launches a reconnectJob coroutine that calls connect() from INSIDE the coroutine.
        // Because baseUrl is invalid, connect() throws and calls triggerReconnection() while inside that coroutine.
        // Previously, reconnectJob?.isActive == true blocked triggerReconnection() from scheduling the next retry.
        client.reconnectImmediately()

        val scheduledNextJob = reconnectJobField.get(client) as? Job
        assertTrue(
            "Next reconnectJob must be scheduled and active after connect() fails inside reconnectJob",
            scheduledNextJob?.isActive == true,
        )
        assertEquals(
            "reconnectDelayMs should advance from 500ms to 1000ms for the next retry",
            1000L,
            delayField.getLong(client),
        )
        client.close()
    }

    @Test
    fun `Listener processes failure even if callback arrives before ws field assignment`() {
        val client = HaWsClient(
            baseUrl = "http://127.0.0.1:8123",
            token = "dummy_token",
            gatewayId = "gw1",
            gatewayName = "Gateway 1",
            scope = scope,
        )

        val stateField = getPrivateField(client, "_connectionState")
        @Suppress("UNCHECKED_CAST")
        val stateFlow = stateField.get(client) as kotlinx.coroutines.flow.MutableStateFlow<ConnectionState>
        stateFlow.value = ConnectionState.Connecting

        val connectGenField = getPrivateField(client, "connectGen")
        val connectGen = connectGenField.get(client) as java.util.concurrent.atomic.AtomicLong
        val gen = connectGen.incrementAndGet()

        // ws field is still null (simulating callback arriving before ws = http.newWebSocket(...) returns)
        val wsField = getPrivateField(client, "ws")
        assertEquals(null, wsField.get(client))

        val listenerClass = Class.forName("dev.eigger.hassble.net.HaWsClient\$Listener")
        val constructor = listenerClass.getDeclaredConstructor(HaWsClient::class.java, Long::class.javaPrimitiveType)
            .apply { isAccessible = true }
        val currentListener = constructor.newInstance(client, gen) as okhttp3.WebSocketListener

        val dummyWs = object : WebSocket {
            override fun cancel() {}
            override fun close(code: Int, reason: String?): Boolean = true
            override fun queueSize(): Long = 0L
            override fun request(): okhttp3.Request = okhttp3.Request.Builder().url("http://localhost").build()
            override fun send(text: String): Boolean = true
            override fun send(bytes: okio.ByteString): Boolean = true
        }

        // Callback arrives before ws is assigned: must NOT be ignored!
        currentListener.onFailure(dummyWs, java.io.IOException("Connection refused"), null)

        assertEquals(
            "State must transition to Disconnected instead of sticking in Connecting",
            ConnectionState.Disconnected,
            client.connectionState.value,
        )
        assertEquals(ConnectionIssue.NetworkError, client.connectionIssue.value)
        client.close()
    }

    @Test
    fun `Listener ignores callbacks from previous generation after new connect generation starts`() {
        val client = HaWsClient(
            baseUrl = "http://127.0.0.1:8123",
            token = "dummy_token",
            gatewayId = "gw1",
            gatewayName = "Gateway 1",
            scope = scope,
        )

        val connectGenField = getPrivateField(client, "connectGen")
        val connectGen = connectGenField.get(client) as java.util.concurrent.atomic.AtomicLong

        val listenerClass = Class.forName("dev.eigger.hassble.net.HaWsClient\$Listener")
        val constructor = listenerClass.getDeclaredConstructor(HaWsClient::class.java, Long::class.javaPrimitiveType)
            .apply { isAccessible = true }

        // Generation 1 listener
        val gen1 = connectGen.incrementAndGet()
        val staleListener = constructor.newInstance(client, gen1) as okhttp3.WebSocketListener

        // Advance to Generation 2 (simulating a new connect() call)
        val gen2 = connectGen.incrementAndGet()
        val activeListener = constructor.newInstance(client, gen2) as okhttp3.WebSocketListener

        val stateField = getPrivateField(client, "_connectionState")
        @Suppress("UNCHECKED_CAST")
        val stateFlow = stateField.get(client) as kotlinx.coroutines.flow.MutableStateFlow<ConnectionState>
        stateFlow.value = ConnectionState.Connecting

        val dummyWs = object : WebSocket {
            override fun cancel() {}
            override fun close(code: Int, reason: String?): Boolean = true
            override fun queueSize(): Long = 0L
            override fun request(): okhttp3.Request = okhttp3.Request.Builder().url("http://localhost").build()
            override fun send(text: String): Boolean = true
            override fun send(bytes: okio.ByteString): Boolean = true
        }

        // Stale listener (gen1) callbacks arrive late — must be completely ignored
        staleListener.onClosed(dummyWs, 1000, "Late close from old socket")
        assertEquals("Stale onClosed must not change Connecting state", ConnectionState.Connecting, client.connectionState.value)
        assertEquals(ConnectionIssue.None, client.connectionIssue.value)

        staleListener.onFailure(dummyWs, java.io.IOException("Late failure from old socket"), null)
        assertEquals("Stale onFailure must not change Connecting state", ConnectionState.Connecting, client.connectionState.value)
        assertEquals(ConnectionIssue.None, client.connectionIssue.value)

        // Active listener (gen2) callback must be processed
        activeListener.onFailure(dummyWs, java.io.IOException("Active socket failure"), null)
        assertEquals(ConnectionState.Disconnected, client.connectionState.value)
        assertEquals(ConnectionIssue.NetworkError, client.connectionIssue.value)
        client.close()
    }

    @Test
    fun `HaWsClient default http client shares connectionPool and dispatcher with ConfigLoader`() {
        val client = HaWsClient(
            baseUrl = "http://127.0.0.1:8123",
            token = "dummy_token",
            gatewayId = "gw1",
            gatewayName = "Gateway 1",
            scope = scope,
        )

        val httpField = getPrivateField(client, "http")
        val clientHttp = httpField.get(client) as OkHttpClient

        // Shares thread pool and connection pool
        assertTrue(
            "ConnectionPool should be shared with ConfigLoader.sharedHttpClient",
            clientHttp.connectionPool === dev.eigger.hassble.config.ConfigLoader.sharedHttpClient.connectionPool,
        )
        assertTrue(
            "Dispatcher should be shared with ConfigLoader.sharedHttpClient",
            clientHttp.dispatcher === dev.eigger.hassble.config.ConfigLoader.sharedHttpClient.dispatcher,
        )
        assertEquals(30_000, clientHttp.pingIntervalMillis)
    }
}


package dev.eigger.hassble.ble

import dev.eigger.hassble.config.GattConfig
import dev.eigger.hassble.config.GattEncryption
import dev.eigger.hassble.config.GattPoll
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GattPollerTest {
    private val bm7Key = GattEncryption(keyHex = "6c656167656e64fffe30313030303040")
    private val gatt = GattConfig(
        mac = MAC,
        serviceUuid = "fff0",
        notifyCharUuid = "fff4",
        writeCharUuid = "fff3",
        encryption = bm7Key,
        poll = GattPoll(command = POLL_CMD, interval = "60s", responseTimeout = "200ms"),
        responsePrefix = "d1550700",
    )

    private fun b(hex: String) = GattCrypto.hexToBytes(hex)!!
    private fun enc(hex: String) = GattCrypto.encrypt(bm7Key, b(hex))

    /** 연결마다 [replies]의 다음 항목(수신할 notify 목록)을 write 뒤에 내보내는 가짜 링크. */
    private class FakeLink(
        val replies: ArrayDeque<List<ByteArray>> = ArrayDeque(),
        val openFailures: ArrayDeque<Exception> = ArrayDeque(),
    ) : GattPollLink {
        val events = mutableListOf<String>()
        var openCount = 0

        override suspend fun open(): GattPollSession {
            openCount++
            events += "open"
            openFailures.removeFirstOrNull()?.let { throw it }
            val toSend = replies.removeFirstOrNull() ?: emptyList()
            val written = CompletableDeferred<Unit>()
            return object : GattPollSession {
                override suspend fun notifications(): Flow<ByteArray> {
                    events += "subscribe"
                    return flow {
                        written.await()
                        toSend.forEach { emit(it) }
                        awaitCancellation()
                    }
                }

                override suspend fun write(bytes: ByteArray, withResponse: Boolean) {
                    events += "write:${GattCrypto.bytesToHex(bytes).lowercase()}:$withResponse"
                    written.complete(Unit)
                }

                override fun close() {
                    events += "close"
                }
            }
        }
    }

    private class Harness(val link: FakeLink, gatt: GattConfig, maxCycles: Int = Int.MAX_VALUE) {
        val statuses = mutableListOf<DeviceLinkStatus>()
        val sleeps = mutableListOf<Long>()
        val emitted = mutableListOf<String>()
        var waits = 0
        var clock = 1_000L
        val lock = Mutex()
        val poller = GattPoller(
            deviceId = "bm7", mac = MAC, gatt = gatt, link = link, lock = lock,
            onLinkStatus = { statuses += it },
            sleep = { ms ->
                sleeps += ms
                if (sleeps.size >= maxCycles) throw CancellationException("stop")
                clock += ms
            },
            now = { clock },
        )

        fun run(autoReconnect: Boolean = true) = runBlocking {
            try {
                poller.run(waitForDevice = { waits++ }, autoReconnect = autoReconnect) { emitted += it }
            } catch (_: CancellationException) {
            }
        }

        fun states() = statuses.map { it.state }
    }

    @Test
    fun `first cycle subscribes before writing the encrypted command and closes`() {
        val link = FakeLink(ArrayDeque(listOf(listOf(enc(REPLY)))))
        val h = Harness(link, gatt)
        h.run(autoReconnect = false)

        assertEquals(listOf(REPLY.uppercase()), h.emitted)
        assertEquals(listOf("open", "subscribe", "write:$BM7_POLL_CIPHER:true", "close"), link.events)
        assertEquals(1, h.waits)
        assertEquals(
            listOf(DeviceLinkState.Connecting, DeviceLinkState.Connected, DeviceLinkState.Polling, DeviceLinkState.Idle),
            h.states(),
        )
    }

    @Test
    fun `healthy cycles skip advertisement wait and stay idle`() {
        val link = FakeLink(ArrayDeque(List(3) { listOf(enc(REPLY)) }))
        val h = Harness(link, gatt, maxCycles = 3)
        h.run()

        assertEquals(3, h.emitted.size)
        assertEquals("only the first cycle waits for an advertisement", 1, h.waits)
        // 첫 주기 이후엔 Idle만 보고 → HA link_status / connection 스위치가 깜빡이지 않는다
        assertEquals(listOf(DeviceLinkState.Idle, DeviceLinkState.Idle), h.states().takeLast(2))
        assertEquals(4 + 2, h.statuses.size)
        assertTrue(h.statuses.all { it.state.isLinkUp() || it.state == DeviceLinkState.Connecting })
    }

    @Test
    fun `ack is skipped and the data reply is returned`() {
        val link = FakeLink(ArrayDeque(listOf(listOf(enc(ACK), ByteArray(5), enc(REPLY)))))
        val h = Harness(link, gatt)
        h.run(autoReconnect = false)
        assertEquals(listOf(REPLY.uppercase()), h.emitted)
    }

    @Test
    fun `timeout reports no response, closes, and the next cycle waits for the device again`() {
        val link = FakeLink(ArrayDeque(listOf(listOf(enc(ACK)), listOf(enc(REPLY)))))
        val h = Harness(link, gatt, maxCycles = 2)
        h.run()

        val failure = h.statuses.first { it.state == DeviceLinkState.Disconnected }
        assertEquals("no response", failure.errorMessage)
        assertEquals(2, link.events.count { it == "close" })
        assertEquals(2, h.waits)
        assertEquals(listOf(REPLY.uppercase()), h.emitted)
        assertEquals(DeviceLinkState.Idle, h.statuses.last().state)
    }

    @Test
    fun `connect failure is reported and the loop keeps going`() {
        val link = FakeLink(
            replies = ArrayDeque(listOf(listOf(enc(REPLY)))),
            openFailures = ArrayDeque(listOf(IllegalStateException("gatt 133"))),
        )
        val h = Harness(link, gatt, maxCycles = 2)
        h.run()

        assertEquals("gatt 133", h.statuses.first { it.state == DeviceLinkState.Disconnected }.errorMessage)
        assertEquals(2, link.openCount)
        assertEquals(2, h.waits)
        assertEquals(1, h.emitted.size)
    }

    @Test
    fun `failure after a healthy cycle reports intermediate states again`() {
        val link = FakeLink(ArrayDeque(listOf(listOf(enc(REPLY)), emptyList(), listOf(enc(REPLY)))))
        val h = Harness(link, gatt, maxCycles = 3)
        h.run()

        val states = h.states()
        val failedAt = states.indexOf(DeviceLinkState.Disconnected)
        assertTrue(failedAt > 0)
        // 실패 다음 주기는 다시 Connecting부터 보고한다
        assertEquals(DeviceLinkState.Connecting, states[failedAt + 1])
        assertEquals(2, h.waits)
    }

    @Test
    fun `sleep accounts for time spent in the cycle`() {
        val link = FakeLink(ArrayDeque(listOf(listOf(enc(REPLY)))))
        val h = Harness(link, gatt, maxCycles = 1)
        val slowLink = object : GattPollLink {
            override suspend fun open(): GattPollSession {
                h.clock += 15_000
                return link.open()
            }
        }
        val poller = GattPoller(
            "bm7", MAC, gatt, slowLink, Mutex(), onLinkStatus = {},
            sleep = { h.sleeps += it; throw CancellationException("stop") }, now = { h.clock },
        )
        runBlocking {
            try {
                poller.run({}, autoReconnect = true) {}
            } catch (_: CancellationException) {
            }
        }
        assertEquals(listOf(45_000L), h.sleeps)
    }

    @Test
    fun `downstream errors propagate instead of being retried`() {
        val link = FakeLink(ArrayDeque(listOf(listOf(enc(REPLY)))))
        val h = Harness(link, gatt)
        val boom = IllegalStateException("sink broke")
        val thrown = runCatching {
            runBlocking {
                h.poller.run({}, autoReconnect = true) { throw DownstreamException(boom) }
            }
        }.exceptionOrNull()
        assertEquals(boom, thrown)
    }

    @Test
    fun `without encryption the command is written as is`() {
        val plainGatt = gatt.copy(encryption = null, responsePrefix = null)
        val link = FakeLink(ArrayDeque(listOf(listOf(b("aabb")))))
        val h = Harness(link, plainGatt)
        h.run(autoReconnect = false)
        assertTrue(link.events.contains("write:$POLL_CMD:true"))
        assertEquals(listOf("AABB"), h.emitted)
    }

    @Test
    fun `control write is encrypted, closes, and waits for an in-flight poll`() = runBlocking {
        val link = FakeLink()
        val h = Harness(link, gatt)
        h.lock.lock()
        val job = launch { h.poller.writeControl(b(POLL_CMD)) }
        yield()
        assertEquals("must wait while a poll cycle holds the lock", 0, link.openCount)
        h.lock.unlock()
        job.join()
        assertEquals(listOf("open", "write:$BM7_POLL_CIPHER:true", "close"), link.events)
        assertFalse(h.lock.isLocked)
    }

    @Test
    fun `pollOnce returns null on timeout`() = runBlocking {
        val link = FakeLink(ArrayDeque(listOf(emptyList())))
        val h = Harness(link, gatt)
        assertNull(h.poller.pollOnce(quiet = true))
        assertEquals("close", link.events.last())
    }

    companion object {
        const val MAC = "E0:4E:7A:00:00:01"
        const val POLL_CMD = "d1550700000000000000000000000000"
        const val BM7_POLL_CIPHER = "586d7b2377c6924dcd750acb29f5bf8d"
        const val REPLY = "d155070017010004ab00000000020000"
        const val ACK = "d15507ff000000000000000000000000"
    }
}

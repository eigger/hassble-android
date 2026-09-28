package dev.eigger.hassble.net

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class PendingMessageQueueTest {

    @Test
    fun `drain returns in order and empties the queue`() {
        val q = PendingMessageQueue()
        q.add("a"); q.add("b")
        assertEquals(listOf("a", "b"), q.drain())
        assertEquals(0, q.size)
    }

    @Test
    fun `over capacity drops the oldest and reports the running total`() {
        val q = PendingMessageQueue(capacity = 2)
        assertEquals(0L, q.add("a"))
        assertEquals(0L, q.add("b"))
        assertEquals(1L, q.add("c"))
        assertEquals(2L, q.add("d"))
        assertEquals(listOf("c", "d"), q.drain())
    }

    @Test
    fun `concurrent add and drain never throw or lose messages`() {
        // 예전 ArrayList는 스캔 스레드 add와 OkHttp 스레드 drain이 겹치면 예외가 날 수 있었다.
        val q = PendingMessageQueue(capacity = 1_000_000)
        val pool = Executors.newFixedThreadPool(4)
        val start = CountDownLatch(1)
        val drained = java.util.concurrent.ConcurrentLinkedQueue<String>()
        val perWriter = 20_000
        repeat(3) { w ->
            pool.execute { start.await(); repeat(perWriter) { q.add("$w-$it") } }
        }
        pool.execute { start.await(); repeat(2_000) { drained.addAll(q.drain()) } }
        start.countDown()
        pool.shutdown()
        pool.awaitTermination(30, TimeUnit.SECONDS)
        drained.addAll(q.drain())
        assertEquals(3 * perWriter, drained.size)
    }
}

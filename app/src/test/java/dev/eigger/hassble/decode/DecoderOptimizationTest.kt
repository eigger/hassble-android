package dev.eigger.hassble.decode

import dev.eigger.hassble.config.DataType
import dev.eigger.hassble.config.DecodeConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Field
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class DecoderOptimizationTest {

    private fun getExpressionCache(): ConcurrentHashMap<*, *> {
        val field: Field = Decoder::class.java.getDeclaredField("expressionCache")
        field.isAccessible = true
        return field.get(Decoder) as ConcurrentHashMap<*, *>
    }

    @Test
    fun `expressionCache stores parsed expressions`() {
        val formula = "(a * 10) + b"
        val data = byteArrayOf(0x02, 0x05) // a=2, b=5 -> 25
        val result = Decoder.evalFormula(formula, data)
        assertEquals(25.0, result, 0.001)

        val cache = getExpressionCache()
        assertTrue("expressionCache must contain the evaluated formula", cache.containsKey(formula))
    }

    @Test
    fun `concurrent evalFormula calls are thread-safe and produce consistent results`() {
        val formula = "(a * 256 + b) / 4"
        val threads = 8
        val iterationsPerThread = 500
        val pool = Executors.newFixedThreadPool(threads)
        val latch = CountDownLatch(1)
        val errorCount = AtomicInteger(0)

        repeat(threads) { threadIndex ->
            pool.execute {
                latch.await()
                try {
                    repeat(iterationsPerThread) {
                        val high = (threadIndex * 10) and 0xFF
                        val low = it and 0xFF
                        val expected = (high * 256 + low) / 4.0
                        val actual = Decoder.evalFormula(formula, byteArrayOf(high.toByte(), low.toByte()))
                        if (Math.abs(expected - actual) > 0.001) {
                            errorCount.incrementAndGet()
                        }
                    }
                } catch (e: Exception) {
                    errorCount.incrementAndGet()
                }
            }
        }

        latch.countDown()
        pool.shutdown()
        assertTrue("Thread pool did not finish in time", pool.awaitTermination(10, TimeUnit.SECONDS))
        assertEquals("There should be no concurrent evaluation errors", 0, errorCount.get())
    }

    @Test
    fun `decodeStructured string decoding removes trailing spaces and nulls efficiently`() {
        val bytes = "HELLO WORLD \u0000".toByteArray(Charsets.US_ASCII)
        val config = DecodeConfig(offset = 0, length = bytes.size, type = DataType.string)
        val result = Decoder.decodeStructured(bytes, config)
        assertEquals("HELLO WORLD", result)
    }

    @Test
    fun `evalFormula correctly rejects missing byte with exact error message`() {
        val formula = "g * 65536 + h * 256 + i"
        val data = byteArrayOf(0x01, 0x02) // only a, b provided; needs g, h, i
        try {
            Decoder.evalFormula(formula, data)
            org.junit.Assert.fail("Should throw IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertEquals("formula '$formula' needs byte 'g' but response has only 2 byte(s)", e.message)
        }
    }

    @Test
    fun `formula with no byte variables evaluates successfully`() {
        val formula = "42 + 10"
        val result = Decoder.evalFormula(formula, byteArrayOf())
        assertEquals(52.0, result, 0.001)
    }
}

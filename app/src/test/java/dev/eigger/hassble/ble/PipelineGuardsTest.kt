package dev.eigger.hassble.ble

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 소스 flow의 재시도 루프가 collector 예외를 삼키지 않는지. 삼키면 Flow가 그 뒤 모든 emit을
 * 거부해, 스캔은 도는데 수신값이 하나도 처리되지 않는 상태가 재시작 전까지 이어진다.
 */
class PipelineGuardsTest {

    /** 실제 소스와 같은 모양: while { try { emit } catch (Exception) { 재시도 } } */
    private fun retryingSource(guarded: Boolean): Flow<Int> = flow {
        var i = 0
        while (i < 5) {
            try {
                if (guarded) emitDownstream(i) else emit(i)
                i++
            } catch (e: CancellationException) {
                throw e
            } catch (e: DownstreamException) {
                throw e.cause
            } catch (e: Exception) {
                i++ // 재시도 (기기 쪽 오류라고 보고 다음으로)
            }
        }
    }

    @Test
    fun `collector exception propagates as the original exception`() {
        val boom = IllegalStateException("boom")
        try {
            runBlocking { retryingSource(guarded = true).collect { if (it == 1) throw boom } }
            fail("expected the collector exception")
        } catch (e: IllegalStateException) {
            assertSame(boom, e)
        }
    }

    @Test
    fun `unguarded retry loop poisons every later emit`() {
        // 이 PR 이전의 동작을 고정해 둔다: collector 예외를 한 번 삼키면 이후 emit이 전부
        // transparency 위반으로 실패하고, 그 예외도 같은 catch가 삼켜 flow는 "정상"처럼 돌지만
        // collector에는 아무것도 도착하지 않는다.
        val received = mutableListOf<Int>()
        val swallowed = mutableListOf<Exception>()
        runBlocking {
            flow {
                var i = 0
                while (i < 5) {
                    try {
                        emit(i)
                        i++
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        swallowed += e
                        i++
                    }
                }
            }.collect {
                received += it
                if (it == 1) throw IllegalArgumentException("boom")
            }
        }
        assertEquals(listOf(0, 1), received)
        assertTrue(swallowed.first() is IllegalArgumentException)
        assertTrue(swallowed.drop(1).all { it is IllegalStateException && it.message!!.contains("transparency") })
        assertEquals(4, swallowed.size)
    }

    @Test
    fun `source errors are still retried when the collector is fine`() {
        val values = runBlocking {
            flow {
                var attempts = 0
                while (attempts < 3) {
                    try {
                        attempts++
                        if (attempts == 1) throw java.io.IOException("device glitch")
                        emitDownstream(attempts)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: DownstreamException) {
                        throw e.cause
                    } catch (e: Exception) {
                        // retry
                    }
                }
            }.toList()
        }
        assertEquals(listOf(2, 3), values)
    }

    @Test
    fun `take and first still cancel cleanly through emitDownstream`() {
        // take/first는 CancellationException 하위(AbortFlowException)로 상류를 끊는다.
        // emitDownstream이 이를 DownstreamException으로 감싸면 소비자가 정상 종료 대신 예외를 받는다.
        val values = runBlocking {
            flow { var i = 0; while (true) emitDownstream(i++) }.take(3).toList()
        }
        assertEquals(listOf(0, 1, 2), values)
        val first = runBlocking {
            flow { var i = 10; while (true) emitDownstream(i++) }.first()
        }
        assertEquals(10, first)
    }

    @Test
    fun `error log prints the trace once then only every Nth repeat`() {
        val lines = mutableListOf<String>()
        val log = PipelineErrorLog({ lines += it }, repeatEvery = 3)
        repeat(7) { log.record("reading handling (device=p)", NumberFormatException("NaN $it")) }
        log.record("reading handling (device=q)", NumberFormatException("NaN"))
        assertEquals(8L, log.totalCount)
        // 메시지가 매번 달라도(값·인덱스 등) 같은 종류로 묶여야 한다.
        // p: 1회째 전체, 3·6회째 횟수 / q: 1회째 전체
        assertEquals(4, lines.size)
        assertTrue(lines[0].startsWith("[Error] reading handling (device=p) failed"))
        assertTrue(lines[0].contains("NumberFormatException"))
        assertTrue(lines[1].contains("repeated 3 times"))
        assertTrue(lines[2].contains("repeated 6 times"))
        assertTrue(lines[3].startsWith("[Error] reading handling (device=q) failed"))
    }

    @Test
    fun `detail shows only in the first log and the callback follows each log line`() {
        val lines = mutableListOf<String>()
        var callbacks = 0
        val log = PipelineErrorLog({ lines += it }, repeatEvery = 2, onLogged = { callbacks++ })
        log.record("HA command handling", IllegalArgumentException("x"), detail = "event={a}")
        log.record("HA command handling", IllegalArgumentException("y"), detail = "event={b}")
        log.record("HA command handling", IllegalStateException("z"), detail = "event={c}")
        // IAE 1회째(전체) · IAE 2회째(반복 알림) · ISE 1회째(전체)
        assertEquals(3, lines.size)
        assertTrue(lines[0].contains("(event={a})"))
        assertTrue(lines[1].contains("repeated 2 times"))
        // 알림 건수 갱신도 로그와 같은 빈도로 불린다(패킷마다가 아니라).
        assertEquals(3, callbacks)
    }
}

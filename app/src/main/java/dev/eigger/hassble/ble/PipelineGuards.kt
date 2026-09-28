package dev.eigger.hassble.ble

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.FlowCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 소스 flow(광고 스캔, GATT, OBD)는 `while { try { … emit(…) … } catch (e: Exception) { 재시도 } }`
 * 꼴의 재시도 루프다. 그런데 emit()은 collector(수신값 처리)를 그 자리에서 실행하므로, collector가
 * 던진 예외까지 이 catch가 삼키게 된다. Flow는 이를 exception transparency 위반으로 보고 그 뒤의
 * **모든** emit()에서 IllegalStateException을 던진다. 결과적으로 스캔·연결은 살아 있고 앱은 "연결됨"인데
 * 수신값이 하나도 처리되지 않는 상태가, flow를 새로 수집하는 게이트웨이 재시작 전까지 이어졌다.
 *
 * emit을 [emitDownstream]으로 감싸고, 재시도 루프의 `catch (e: Exception)` 앞에서
 * `catch (e: DownstreamException) { throw e.cause }`로 원래 예외를 그대로 내보낸다.
 */
internal class DownstreamException(override val cause: Throwable) : RuntimeException(cause)

internal suspend fun <T> FlowCollector<T>.emitDownstream(value: T) {
    try {
        emit(value)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        throw DownstreamException(e)
    }
}

/**
 * 수신값·HA 명령 한 건을 처리하다 난 예외를 기록한다. 같은 예외가 패킷마다 반복되면 로그가 넘치므로
 * 처음 한 번은 스택트레이스를, 그 뒤로는 [repeatEvery]번마다 횟수만 남긴다.
 */
internal class PipelineErrorLog(
    private val log: (String) -> Unit,
    private val repeatEvery: Long = 100,
    private val maxTraceLines: Int = 25,
) {
    private val counts = ConcurrentHashMap<String, AtomicLong>()
    private val total = AtomicLong(0)

    val totalCount: Long get() = total.get()

    fun record(context: String, e: Throwable) {
        total.incrementAndGet()
        val key = "$context|${e::class.java.name}|${e.message}"
        val n = counts.getOrPut(key) { AtomicLong(0) }.incrementAndGet()
        when {
            n == 1L -> {
                val trace = e.stackTraceToString().lineSequence().take(maxTraceLines).joinToString("\n")
                log("[Error] $context failed — dropped this one and kept going:\n$trace")
            }
            n % repeatEvery == 0L ->
                log("[Error] $context: same error repeated $n times: ${e::class.java.simpleName}: ${e.message}")
        }
    }
}

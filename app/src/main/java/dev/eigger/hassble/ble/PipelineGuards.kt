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
 *
 * 같은 예외인지는 context + 예외 클래스로만 가른다. 메시지(인덱스·오프셋 등)나 이벤트 내용까지 키에
 * 넣으면 값마다 새 키가 생겨 맵이 끝없이 커지고, 변형마다 스택트레이스가 찍혀 로그 버퍼를 덮는다.
 * 그래서 [context]에는 가변 데이터를 넣지 말고, 첫 로그에만 보일 내용은 detail로 넘긴다.
 * [onLogged]는 로그를 남길 때마다(새 종류의 첫 발생, 그리고 [repeatEvery]번째 반복마다) 불린다.
 * 알림의 오류 건수를 갱신하는 데 쓴다 — 패킷마다 부르면 알림 갱신이 너무 잦다.
 */
internal class PipelineErrorLog(
    private val log: (String) -> Unit,
    private val repeatEvery: Long = 100,
    private val maxTraceLines: Int = 25,
    private val onLogged: () -> Unit = {},
) {
    private val counts = ConcurrentHashMap<String, AtomicLong>()
    private val total = AtomicLong(0)

    val totalCount: Long get() = total.get()

    fun record(context: String, e: Throwable, detail: String? = null) {
        total.incrementAndGet()
        val key = "$context|${e::class.java.name}"
        val n = counts.getOrPut(key) { AtomicLong(0) }.incrementAndGet()
        when {
            n == 1L -> {
                val trace = e.stackTraceToString().lineSequence().take(maxTraceLines).joinToString("\n")
                val detailLine = detail?.let { " ($it)" } ?: ""
                log("[Error] $context failed$detailLine — dropped this one and kept going:\n$trace")
                onLogged()
            }
            n % repeatEvery == 0L -> {
                log("[Error] $context: same error repeated $n times: ${e::class.java.simpleName}: ${e.message}")
                onLogged()
            }
        }
    }
}

package dev.eigger.hassble.net

/**
 * WS가 연결되지 않은 동안 보낼 메시지를 모아 두는 큐.
 *
 * 예전엔 일반 ArrayList였는데, 스캔 스레드(수신값 전송)와 OkHttp 스레드(재연결 시 비우기·flush)가
 * 동시에 건드려 WiFi↔LTE 전환 같은 재연결 순간에 예외가 날 수 있었다. 그 예외가 수신값 처리로
 * 올라가면 수신 처리 전체가 멈췄다. 모든 접근을 한 락으로 묶고, 오래 끊겨 있을 때 끝없이 쌓이지 않게
 * [capacity]를 넘으면 가장 오래된 것부터 버린다(상태 값은 최신 것만 의미 있다).
 */
internal class PendingMessageQueue(private val capacity: Int = 2_000) {
    private val lock = Any()
    private val items = ArrayDeque<String>()
    private var dropped = 0L

    /** 추가. 용량 초과로 버린 게 있으면 지금까지 버린 누적 수, 없으면 0. */
    fun add(text: String): Long = synchronized(lock) {
        items.addLast(text)
        var droppedNow = false
        while (items.size > capacity) {
            items.removeFirst()
            dropped++
            droppedNow = true
        }
        if (droppedNow) dropped else 0L
    }

    /** 전부 꺼내고 비운다. */
    fun drain(): List<String> = synchronized(lock) {
        val out = items.toList()
        items.clear()
        out
    }

    fun clear() = synchronized(lock) { items.clear() }

    val size: Int get() = synchronized(lock) { items.size }

    /**
     * [block]을 락 안에서 실행한다. "연결됐으면 보내고 아니면 쌓기"를 flush와 원자적으로 만들기 위해 쓴다.
     * 락 밖에서 상태를 보고 쌓으면, 그 사이 flush가 끝나 메시지가 다음 재연결까지 큐에 갇힌다.
     */
    fun <T> withLock(block: () -> T): T = synchronized(lock) { block() }
}

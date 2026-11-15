package dev.eigger.hassble.ble

import dev.eigger.hassble.config.GattConfig
import dev.eigger.hassble.config.GattPoll
import dev.eigger.hassble.config.parseDurationMs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** poll 기기와의 연결 1회. 실제 BLE(Nordic)와 테스트용 가짜 구현이 있다. */
interface GattPollLink {
    /** 연결 + 서비스 탐색. 필요한 캐릭터리스틱이 없으면 예외. */
    suspend fun open(): GattPollSession
}

interface GattPollSession {
    /** notify 활성화(CCCD write)를 마친 뒤 수신 흐름을 돌려준다. */
    suspend fun notifications(): Flow<ByteArray>
    suspend fun write(bytes: ByteArray, withResponse: Boolean)
    fun close()
}

/**
 * gatt.poll 기기의 연결-폴링-해제 주기. BLE 의존부는 [GattPollLink]로 분리해 단위 테스트한다.
 *
 * - 주기가 성공하면 다음 주기까지 [DeviceLinkState.Idle]. 성공이 이어지는 동안에는 광고 대기와
 *   Connecting/Connected/Polling 보고를 생략해 HA link_status / connection 스위치가 깜빡이지 않는다.
 * - 실패(예외·시간 초과)하면 Disconnected(오류 메시지)로 보고하고, 다음 주기는 광고 대기부터.
 * - 컨트롤 write([writeControl])는 같은 [lock]으로 주기와 직렬화한다.
 */
class GattPoller(
    private val deviceId: String,
    private val mac: String,
    private val gatt: GattConfig,
    private val link: GattPollLink,
    private val lock: Mutex,
    private val onLinkStatus: (DeviceLinkStatus) -> Unit,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val now: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = {},
) {
    private val poll: GattPoll = requireNotNull(gatt.poll) { "GattPoller requires gatt.poll" }
    val intervalMs: Long = parseDurationMs(poll.interval, 60_000).coerceAtLeast(MIN_DELAY_MS)
    private val timeoutMs: Long = parseDurationMs(poll.responseTimeout, 10_000)

    suspend fun run(
        waitForDevice: suspend () -> Unit,
        autoReconnect: Boolean,
        emit: suspend (String) -> Unit,
    ) {
        var healthy = false
        while (currentCoroutineContext().isActive) {
            val cycleStartMs = now()
            try {
                // OBD 쪽과 같은 이유로 waitForDevice()도 try 안에 둔다.
                if (!healthy) waitForDevice()
                val hex = lock.withLock { pollOnce(quiet = healthy) }
                if (hex != null) {
                    emit(hex)
                    healthy = true
                    onLinkStatus(DeviceLinkStatus(deviceId, DeviceLinkState.Idle, mac, now()))
                } else {
                    log("no valid response")
                    healthy = false
                    onLinkStatus(DeviceLinkStatus(deviceId, DeviceLinkState.Disconnected, mac, errorMessage = "no response"))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: DownstreamException) {
                // 수신값 처리 쪽 예외 — 재시도로 삼키면 이후 모든 emit이 실패한다. 그대로 내보낸다.
                throw e.cause
            } catch (e: Exception) {
                log("poll cycle failed: ${e.message}")
                healthy = false
                onLinkStatus(DeviceLinkStatus(deviceId, DeviceLinkState.Disconnected, mac, errorMessage = e.message))
            }
            if (!autoReconnect) break
            sleep((intervalMs - (now() - cycleStartMs)).coerceAtLeast(MIN_DELAY_MS))
        }
    }

    /**
     * 한 주기: 연결 → notify 활성화·수집 시작 → (암호화된) 명령 write → prefix를 통과한 첫 응답.
     * 응답 hex(복호화됨)를 돌려주고, 시간 초과면 null. 연결은 항상 닫는다.
     * quiet: 직전 주기가 성공했으면 중간 상태(Connecting/Connected/Polling)를 보고하지 않는다.
     */
    suspend fun pollOnce(quiet: Boolean): String? = coroutineScope {
        val command = requireNotNull(GattCrypto.hexToBytes(poll.command)) { "poll.command is not valid hex" }
        val payload = GattCrypto.encryptIfNeeded(gatt.encryption, command)

        if (!quiet) onLinkStatus(DeviceLinkStatus(deviceId, DeviceLinkState.Connecting, mac))
        val session = link.open()
        try {
            if (!quiet) onLinkStatus(DeviceLinkStatus(deviceId, DeviceLinkState.Connected, mac))
            // notify 활성화를 write보다 먼저 끝내고, 수집도 write 전에 시작해 응답을 놓치지 않는다.
            val notifications = session.notifications()
            val channel = Channel<ByteArray>(Channel.UNLIMITED)
            val notifyJob = launch(start = CoroutineStart.UNDISPATCHED) {
                notifications.collect { channel.trySend(it) }
            }
            try {
                if (!quiet) onLinkStatus(DeviceLinkStatus(deviceId, DeviceLinkState.Polling, mac))
                session.write(payload, poll.writeWithResponse)
                GattCrypto.awaitResponse(
                    notifications = channel.receiveAsFlow(),
                    enc = gatt.encryption,
                    prefix = gatt.responsePrefix,
                    timeoutMs = timeoutMs,
                    onDropped = log,
                )
            } finally {
                notifyJob.cancel()
                channel.close()
            }
        } finally {
            session.close()
        }
    }

    /** 컨트롤 write: 주기와 겹치지 않게 잠근 뒤 1회 연결해서 (암호화해) 쓰고 끊는다. */
    suspend fun writeControl(plain: ByteArray) {
        val bytes = GattCrypto.encryptIfNeeded(gatt.encryption, plain)
        lock.withLock {
            val session = link.open()
            try {
                session.write(bytes, poll.writeWithResponse)
            } finally {
                session.close()
            }
        }
    }

    companion object {
        const val MIN_DELAY_MS = 1_000L
    }
}

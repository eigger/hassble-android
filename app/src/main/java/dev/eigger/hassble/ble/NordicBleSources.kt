package dev.eigger.hassble.ble

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import dev.eigger.hassble.config.DeviceConfig
import dev.eigger.hassble.config.SensorConfig
import dev.eigger.hassble.config.Source
import dev.eigger.hassble.config.SourceField
import dev.eigger.hassble.config.parseDurationMs
import dev.eigger.hassble.decode.ObdResponseParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import no.nordicsemi.android.kotlin.ble.client.main.callback.ClientBleGatt
import no.nordicsemi.android.kotlin.ble.client.main.service.ClientBleGattCharacteristic
import no.nordicsemi.android.kotlin.ble.core.data.util.DataByteArray
import no.nordicsemi.android.kotlin.ble.core.data.BleWriteType
import no.nordicsemi.android.kotlin.ble.scanner.BleScanner
import no.nordicsemi.android.kotlin.ble.core.scanner.BleScanMode
import no.nordicsemi.android.kotlin.ble.core.scanner.BleScannerSettings
import dev.eigger.hassble.config.BleScanModeOption
import dev.eigger.hassble.service.LiveEventLogger
import dev.eigger.hassble.service.LogType
import java.util.UUID

private const val TAG = "HassBleSources"

/**
 * 경로 A: 광고 passive scan.
 * BleScanner(context).scan() 결과를 설정 필터(namePrefix, serviceDataUuid 등)와 대조하여
 * 매칭되는 기기의 페이로드 데이터를 추출하여 방출합니다.
 */
class NordicAdvertisementScanner(private val context: Context) : AdvertisementScanner {
    private var scanJob: Job? = null

    override fun scan(devices: List<DeviceConfig>, scanMode: BleScanModeOption): Flow<RawReading> = flow {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "BLUETOOTH_SCAN permission not granted")
            LiveEventLogger.log(LogType.LINK, "BLE scan failed: BLUETOOTH_SCAN permission not granted")
            return@flow
        }

        val scanner = BleScanner(context)
        Log.d(TAG, "Starting Nordic BLE scan for ${devices.size} advertisement profiles")
        LiveEventLogger.log(LogType.LINK, "Starting Nordic BLE scan for ${devices.size} profiles...")

        val nativeScanMode = when (scanMode) {
            BleScanModeOption.LOW_POWER -> BleScanMode.SCAN_MODE_LOW_POWER
            BleScanModeOption.BALANCED -> BleScanMode.SCAN_MODE_BALANCED
            BleScanModeOption.LOW_LATENCY -> BleScanMode.SCAN_MODE_LOW_LATENCY
        }
        val scanSettings = BleScannerSettings(
            scanMode = nativeScanMode,
            legacy = true,
        )
        LiveEventLogger.log(LogType.LINK, "BLE scan mode: ${scanMode.label}")

        try {
            scanner.scan(settings = scanSettings).collect { result ->
                val deviceName = result.device.name ?: ""
                val deviceAddress = result.device.address
                val scanRecord = result.data?.scanRecord
                val serviceData = scanRecord?.serviceData ?: emptyMap()
                val advertisedServiceUuids = scanRecord?.serviceUuids.orEmpty()
                val manufacturerData = scanRecord?.manufacturerSpecificData
                val rawBytes = scanRecord?.bytes

                if (LiveEventLogger.isLiveActive) {
                    val mfrHex = manufacturerData?.let {
                        val list = mutableListOf<String>()
                        for (i in 0 until it.size()) {
                            val id = it.keyAt(i)
                            val bytes = it.valueAt(i).value
                            list.add("0x%04X: %s".format(id, bytes.joinToString("") { String.format("%02X", it) }))
                        }
                        list.joinToString(", ")
                    }
                    val svcHex = serviceData.entries.joinToString(", ") { (key, value) ->
                        "${key.uuid.toString().takeLast(8).uppercase()}: ${value.value.joinToString("") { String.format("%02X", it) }}"
                    }
                    val logMsg = buildString {
                        append("addr=$deviceAddress")
                        if (deviceName.isNotBlank()) append(", name='$deviceName'")
                        if (!mfrHex.isNullOrBlank()) append(", mfr=[$mfrHex]")
                        if (svcHex.isNotBlank()) append(", svc=[$svcHex]")
                    }
                    LiveEventLogger.log(LogType.ADV, logMsg)
                }

                if ((manufacturerData != null && manufacturerData.size() > 0) || serviceData.isNotEmpty()) {
                    val mfrIds = (0 until (manufacturerData?.size() ?: 0)).map { manufacturerData!!.keyAt(it) }
                    val svcUuids = serviceData.keys.map { it.uuid.toString().takeLast(8) }
                    Log.d(TAG, "ADV addr=$deviceAddress name='$deviceName' mfr=$mfrIds svc=$svcUuids")
                }

                for (d in devices) {
                    if (d.source != Source.advertisement) continue
                    val match = d.match ?: continue

                    if (!AdvertisementMatcher.matches(
                            match,
                            deviceAddress,
                            deviceName,
                            hasServiceUuid = { uuid ->
                                val target = uuid.uppercase()
                                serviceData.keys.any { it.uuid.toString().uppercase().contains(target) }
                                    || advertisedServiceUuids.any {
                                        it.uuid.toString().uppercase().contains(target)
                                    }
                            },
                            manufacturerPayload = { id ->
                                manufacturerData?.get(id)?.value?.takeIf { it.isNotEmpty() }
                            },
                        )
                    ) {
                        if (match.manufacturerId != null || match.serviceDataUuid != null) {
                            Log.d(TAG, "  NO MATCH profile=${d.id} mfrId=${match.manufacturerId} svcUuid=${match.serviceDataUuid}")
                        }
                        continue
                    }

                    val manufacturerHex = resolveManufacturerHex(manufacturerData, match.manufacturerId)
                    val serviceDataHex = match.serviceDataUuid?.let { uuid ->
                        val target = uuid.uppercase()
                        serviceData.entries.firstOrNull { (key, _) ->
                            key.uuid.toString().uppercase().contains(target)
                        }?.value?.value?.let { AdvertisementMatcher.bytesToHex(it) }
                    }
                    val fullScanHex = rawBytes?.value?.let { bytesToHex(it) }
                    val primaryField = d.sensors.firstOrNull()?.sourceField ?: SourceField.raw
                    val primaryHex = when (primaryField) {
                        SourceField.service_data -> serviceDataHex
                        SourceField.manufacturer_data -> manufacturerHex
                        SourceField.raw -> fullScanHex
                    }
                    if (primaryHex.isNullOrBlank()) {
                        Log.d(TAG, "  MATCHED ${d.id} addr=$deviceAddress but $primaryField is null (mfr=$manufacturerHex)")
                        continue
                    }
                    Log.i(TAG, "MATCHED ${d.id} addr=$deviceAddress mfr=${manufacturerHex?.take(16)} svc=${serviceDataHex?.take(16)}")

                    emit(
                        RawReading(
                            deviceId = d.id,
                            source = "advertisement",
                            rawHex = primaryHex,
                            macAddress = deviceAddress,
                            deviceName = deviceName.takeIf { it.isNotBlank() },
                            manufacturerHex = manufacturerHex,
                            serviceDataHex = serviceDataHex,
                            fullScanHex = fullScanHex,
                        ),
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in BleScanner stream", e)
            LiveEventLogger.log(LogType.LINK, "BLE scanner error: ${e.localizedMessage}")
        }
    }

    override fun stop() {
        scanJob?.cancel()
        scanJob = null
    }

    private fun bytesToHex(bytes: ByteArray): String =
        bytes.joinToString("") { String.format("%02X", it) }

    private fun resolveManufacturerHex(
        manufacturerData: android.util.SparseArray<no.nordicsemi.android.kotlin.ble.core.data.util.DataByteArray>?,
        preferredId: Int?,
    ): String? {
        preferredId?.let { id ->
            manufacturerData?.get(id)?.value
                ?.takeIf { it.isNotEmpty() }
                ?.let { return bytesToHex(it) }
        }
        if (manufacturerData == null || manufacturerData.size() == 0) return null
        var best: ByteArray? = null
        for (i in 0 until manufacturerData.size()) {
            val payload = manufacturerData.valueAt(i)?.value ?: continue
            if (payload.isEmpty()) continue
            if (best == null || payload.size > best.size) best = payload
        }
        return best?.let { bytesToHex(it) }
    }
}

/**
 * 경로 B: GATT notify + write.
 * ClientBleGatt.connect로 수동 바인딩된 MAC 주소로 연결 후 지정된 캐릭터리스틱 노티피케이션을 구독합니다.
 */
class NordicGattNotifySource(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onLinkStatus: (DeviceLinkStatus) -> Unit = {},
) : GattNotifySource {
    private val activeConnections = mutableMapOf<String, ClientBleGatt>()

    override fun connect(device: DeviceConfig): Flow<RawReading> = flow {
        val mac = device.gatt?.mac ?: return@flow
        val serviceUuidStr = device.gatt.serviceUuid
        val notifyCharUuidStr = device.gatt.notifyCharUuid

        try {
            Log.d(TAG, "Connecting to GATT device ${device.id} at $mac")
            onLinkStatus(DeviceLinkStatus(device.id, DeviceLinkState.Connecting, mac))
            val client = ClientBleGatt.connect(context, mac, scope)
            activeConnections[device.id] = client
            onLinkStatus(DeviceLinkStatus(device.id, DeviceLinkState.Connected, mac))

            val services = client.discoverServices()
            val service = services.findService(UUID.fromString(serviceUuidStr))
            val characteristic = service?.findCharacteristic(UUID.fromString(notifyCharUuidStr))

            if (characteristic != null) {
                Log.d(TAG, "Subscribing to notifications on $notifyCharUuidStr")
                characteristic.getNotifications().collect { bytes ->
                    onLinkStatus(DeviceLinkStatus(device.id, DeviceLinkState.Polling, mac, System.currentTimeMillis()))
                    val hex = bytes.value.joinToString("") { String.format("%02X", it) }
                    emit(RawReading(deviceId = device.id, source = "gatt_notify", rawHex = hex))
                }
            } else {
                Log.e(TAG, "Notify characteristic $notifyCharUuidStr not found")
                onLinkStatus(DeviceLinkStatus(device.id, DeviceLinkState.Error, mac, errorMessage = "Notify char not found"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed connecting/subscribing to GATT device ${device.id}", e)
            onLinkStatus(DeviceLinkStatus(device.id, DeviceLinkState.Error, mac, errorMessage = e.message))
        }
    }

    override suspend fun write(device: DeviceConfig, hex: String) {
        val client = activeConnections[device.id] ?: return
        val serviceUuidStr = device.gatt?.serviceUuid ?: return
        val writeCharUuidStr = device.gatt.writeCharUuid ?: return
        val bytes = hexToBytes(hex) ?: return

        try {
            val services = client.discoverServices()
            val service = services.findService(UUID.fromString(serviceUuidStr))
            val characteristic = service?.findCharacteristic(UUID.fromString(writeCharUuidStr))
            characteristic?.write(DataByteArray(bytes), BleWriteType.NO_RESPONSE)
        } catch (e: Exception) {
            Log.e(TAG, "Error writing to GATT device ${device.id}", e)
        }
    }

    override fun disconnect(deviceId: String) {
        activeConnections.remove(deviceId)?.disconnect()
    }

    private fun hexToBytes(hex: String): ByteArray? {
        val cleanHex = hex.replace(" ", "")
        if (cleanHex.length % 2 != 0) return null
        return ByteArray(cleanHex.length / 2) { i ->
            cleanHex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }
}

/**
 * 경로 C: OBD (ELM327) 폴링 — ESPHome ble_elm327과 동일한 단일 TX 큐 패턴.
 */
class NordicElm327Source(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onLinkStatus: (DeviceLinkStatus) -> Unit = {},
) : Elm327Source {
    private val activeConnections = mutableMapOf<String, ClientBleGatt>()
    private val responseMutex = Mutex()
    private var pendingDeferred: CompletableDeferred<String>? = null
    private val sessionJobs = mutableMapOf<String, Job>()

    private data class PollTarget(
        val sensor: SensorConfig,
        var nextPollAtMs: Long = 0L,
    )

    private data class TxItem(
        val cmd: String,
        val pollTarget: PollTarget? = null,
    )

    override fun connect(device: DeviceConfig, enabledKeys: Set<String>): Flow<RawReading> = flow {
        val mac = device.obd?.mac ?: return@flow
        while (currentCoroutineContext().isActive) {
            try {
                runObdSession(this, device, enabledKeys, mac)
                break
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "OBD session lost for ${device.id}, reconnecting in ${RECONNECT_DELAY_MS}ms", e)
                onLinkStatus(
                    DeviceLinkStatus(device.id, DeviceLinkState.Error, mac, errorMessage = e.message),
                )
                teardown(device.id)
                delay(RECONNECT_DELAY_MS)
                onLinkStatus(DeviceLinkStatus(device.id, DeviceLinkState.Connecting, mac))
            }
        }
    }

    private suspend fun runObdSession(
        collector: FlowCollector<RawReading>,
        device: DeviceConfig,
        enabledKeys: Set<String>,
        mac: String,
    ) {
        val obd = device.obd ?: return
        val txDelayMs = parseDurationMs(obd.txDelay, 50L)
        val serviceUuidStr = obd.serviceUuid
        val txCharUuidStr = obd.txCharUuid
        val rxCharUuidStr = obd.rxCharUuid

        Log.d(TAG, "Connecting to OBD reader ${device.id} at $mac")
        onLinkStatus(DeviceLinkStatus(device.id, DeviceLinkState.Connecting, mac))
        val client = ClientBleGatt.connect(context, mac, scope)
        activeConnections[device.id] = client
        onLinkStatus(DeviceLinkStatus(device.id, DeviceLinkState.Connected, mac))

        val services = client.discoverServices()
        val service = services.findService(UUID.fromString(serviceUuidStr))
        val txChar = service?.findCharacteristic(UUID.fromString(txCharUuidStr))
        val rxChar = service?.findCharacteristic(UUID.fromString(rxCharUuidStr))
            ?: throw IllegalStateException("OBD RX characteristic not found")

        if (txChar == null) throw IllegalStateException("OBD TX characteristic not found")

        val responseBuffer = StringBuilder()
        val rxJob = scope.launch {
            rxChar.getNotifications().collect { bytes ->
                val chunk = String(bytes.value, Charsets.US_ASCII)
                responseBuffer.append(chunk)
                if (responseBuffer.contains(">")) {
                    val fullResponse = responseBuffer.toString().trim()
                    responseBuffer.clear()
                    pendingDeferred?.complete(fullResponse)
                }
            }
        }
        sessionJobs[device.id] = rxJob

        val targets = device.sensors
            .filter { it.key in enabledKeys && it.pid != null }
            .map { PollTarget(it, System.currentTimeMillis()) }
        if (targets.isEmpty()) {
            onLinkStatus(DeviceLinkStatus(device.id, DeviceLinkState.Connected, mac))
            return
        }

        val txQueue = ArrayDeque<TxItem>()
        for (cmd in Elm327Source.BASE_INIT) txQueue.add(TxItem(cmd))
        for (cmd in obd.initCommands) {
            if (!isDuplicateInit(cmd)) txQueue.add(TxItem(cmd))
        }

        var elmReady = false
        var currentPreCommands: List<String> = emptyList()
        var lastTxAtMs = 0L
        var collectIdx = 0

        try {
            while (currentCoroutineContext().isActive) {
                val now = System.currentTimeMillis()

                if (txQueue.isNotEmpty() && now - lastTxAtMs >= txDelayMs) {
                    val item = txQueue.removeFirst()
                    val resp = sendCommand(txChar, item.cmd)
                    lastTxAtMs = System.currentTimeMillis()

                    if (!elmReady && item.pollTarget == null && txQueue.isEmpty()) {
                        elmReady = true
                        onLinkStatus(DeviceLinkStatus(device.id, DeviceLinkState.Polling, mac))
                        Log.d(TAG, "OBD dongle ready for ${device.id}")
                    }

                    if (elmReady && item.pollTarget != null && resp != null) {
                        ObdResponseParser.normalizeElm327Response(resp)?.let { hex ->
                            onLinkStatus(
                                DeviceLinkStatus(device.id, DeviceLinkState.Polling, mac, System.currentTimeMillis()),
                            )
                            collector.emit(
                                RawReading(deviceId = device.id, source = "obd", rawHex = hex),
                            )
                        }
                    }
                    continue
                }

                if (!elmReady) {
                    delay(POLL_LOOP_MS)
                    continue
                }

                if (txQueue.isEmpty()) {
                    val n = targets.size
                    var scheduled = false
                    for (i in 0 until n) {
                        val target = targets[(collectIdx + i) % n]
                        if (now >= target.nextPollAtMs) {
                            val sensor = target.sensor
                            if (sensor.preCommands != currentPreCommands) {
                                sensor.preCommands.forEach { txQueue.add(TxItem(it)) }
                                currentPreCommands = sensor.preCommands
                            }
                            txQueue.add(TxItem("${sensor.mode}${sensor.pid}", target))
                            target.nextPollAtMs = now + parseDurationMs(sensor.updateInterval, 60_000L)
                            collectIdx = (collectIdx + i + 1) % n
                            scheduled = true
                            break
                        }
                    }
                    if (!scheduled) delay(POLL_LOOP_MS)
                } else {
                    delay(POLL_LOOP_MS)
                }
            }
        } finally {
            rxJob.cancel()
            sessionJobs.remove(device.id)
        }
    }

    private suspend fun sendCommand(
        txChar: ClientBleGattCharacteristic,
        cmd: String,
    ): String? = responseMutex.withLock {
        val deferred = CompletableDeferred<String>()
        pendingDeferred = deferred
        val payload = (cmd + "\r").toByteArray(Charsets.US_ASCII)
        val timeoutMs = if (cmd.length >= 6) MULTIFRAME_TIMEOUT_MS else SINGLE_FRAME_TIMEOUT_MS

        try {
            txChar.write(DataByteArray(payload), BleWriteType.NO_RESPONSE)
            val resp = withTimeoutOrNull(timeoutMs) { deferred.await() }
            pendingDeferred = null
            return resp
        } catch (e: Exception) {
            Log.e(TAG, "Error executing OBD command $cmd", e)
            pendingDeferred = null
            return null
        }
    }

    override suspend fun write(device: DeviceConfig, hex: String) {
        val client = activeConnections[device.id] ?: return
        val serviceUuidStr = device.obd?.serviceUuid ?: return
        val txCharUuidStr = device.obd.txCharUuid
        val bytes = hexToBytes(hex) ?: return

        try {
            val services = client.discoverServices()
            val service = services.findService(UUID.fromString(serviceUuidStr))
            val characteristic = service?.findCharacteristic(UUID.fromString(txCharUuidStr))
            characteristic?.write(DataByteArray(bytes), BleWriteType.NO_RESPONSE)
        } catch (e: Exception) {
            Log.e(TAG, "Error writing raw hex to OBD dongle ${device.id}", e)
        }
    }

    override fun disconnect(deviceId: String) {
        teardown(deviceId)
    }

    private fun teardown(deviceId: String) {
        sessionJobs.remove(deviceId)?.cancel()
        activeConnections.remove(deviceId)?.disconnect()
    }

    private fun isDuplicateInit(cmd: String): Boolean {
        val normalized = normalizeCommand(cmd)
        return Elm327Source.BASE_INIT.any { normalizeCommand(it) == normalized }
    }

    private fun normalizeCommand(cmd: String): String =
        cmd.filter { !it.isWhitespace() }.lowercase()

    private fun hexToBytes(hex: String): ByteArray? {
        val cleanHex = hex.replace(" ", "")
        if (cleanHex.length % 2 != 0) return null
        return ByteArray(cleanHex.length / 2) { i ->
            cleanHex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    companion object {
        private const val RECONNECT_DELAY_MS = 5_000L
        private const val POLL_LOOP_MS = 10L
        private const val SINGLE_FRAME_TIMEOUT_MS = 2_000L
        private const val MULTIFRAME_TIMEOUT_MS = 5_000L
    }
}

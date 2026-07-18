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
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
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
import no.nordicsemi.android.kotlin.ble.core.data.GattConnectionState
import no.nordicsemi.android.kotlin.ble.scanner.BleScanner
import no.nordicsemi.android.kotlin.ble.core.scanner.BleScanMode
import no.nordicsemi.android.kotlin.ble.core.scanner.BleScannerSettings
import no.nordicsemi.android.kotlin.ble.core.scanner.BleScanFilter
import no.nordicsemi.android.kotlin.ble.core.scanner.FilteredManufacturerData
import no.nordicsemi.android.kotlin.ble.core.scanner.FilteredServiceData
import dev.eigger.hassble.config.BleScanModeOption
import dev.eigger.hassble.R
import dev.eigger.hassble.service.LiveEventLogger
import dev.eigger.hassble.service.LogType
import java.util.UUID

private fun uuidFrom(str: String): UUID {
    val s = str.trim()
    return when (s.length) {
        4 -> UUID.fromString("0000$s-0000-1000-8000-00805F9B34FB")
        8 -> UUID.fromString("$s-0000-1000-8000-00805F9B34FB")
        else -> UUID.fromString(s)
    }
}

private const val TAG = "HassBleSources"

/**
 * 경로 A: 광고 passive scan.
 * BleScanner(context).scan() 결과를 설정 필터(namePrefix, serviceDataUuid 등)와 대조하여
 * 매칭되는 기기의 페이로드 데이터를 추출하여 방출합니다.
 */
class NordicAdvertisementScanner(private val context: Context) : AdvertisementScanner {
    private var scanJob: Job? = null
    private val scanner by lazy { BleScanner(context) }

    // Simple cache to merge ADV_IND and SCAN_RSP data per MAC address
    private val manufacturerDataCache = mutableMapOf<String, android.util.SparseArray<no.nordicsemi.android.kotlin.ble.core.data.util.DataByteArray>>()
    private val serviceDataCache = mutableMapOf<String, Map<android.os.ParcelUuid, no.nordicsemi.android.kotlin.ble.core.data.util.DataByteArray>>()
    private val serviceUuidsCache = mutableMapOf<String, List<android.os.ParcelUuid>>()
    private val cacheTimestamps = mutableMapOf<String, Long>()

    // Shared throttle guard: Android counts scan starts per-app across ALL scan sessions
    // (scan() for advertisement devices, scanForMac() for OBD reconnect-wait), not per callback.
    // A single tracker here ensures both paths respect the same 5-starts-per-30s system limit.
    private val scanStartTimesMutex = Mutex()
    private val scanStartTimes = ArrayDeque<Long>()

    private suspend fun awaitScanThrottleSlot() {
        scanStartTimesMutex.withLock {
            val now = System.currentTimeMillis()
            while (scanStartTimes.isNotEmpty() && now - scanStartTimes.first() >= 30_000L) {
                scanStartTimes.removeFirst()
            }
            if (scanStartTimes.size >= 4) {
                val waitMs = (scanStartTimes.first() + 30_000L) - System.currentTimeMillis() + 100L
                if (waitMs > 0) {
                    LiveEventLogger.log(LogType.LINK, "BLE scan throttle guard: waiting ${waitMs}ms")
                    delay(waitMs)
                }
                val after = System.currentTimeMillis()
                while (scanStartTimes.isNotEmpty() && after - scanStartTimes.first() >= 30_000L) {
                    scanStartTimes.removeFirst()
                }
            }
            scanStartTimes.addLast(System.currentTimeMillis())
        }
    }

    private fun getShortUuid(uuid: java.util.UUID): String {
        val s = uuid.toString().uppercase()
        return if (s.endsWith("-0000-1000-8000-00805F9B34FB")) {
            s.substring(4, 8)
        } else {
            s
        }
    }

    override fun scan(
        devices: List<DeviceConfig>,
        scanMode: BleScanModeOption,
        unfiltered: Boolean
    ): Flow<RawReading> = flow {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "BLUETOOTH_SCAN permission not granted")
            LiveEventLogger.log(LogType.LINK, "BLE scan failed: BLUETOOTH_SCAN permission not granted")
            return@flow
        }

        val scanner = this@NordicAdvertisementScanner.scanner
        Log.d(TAG, "Starting Nordic BLE scan for ${devices.size} advertisement profiles (unfiltered=$unfiltered)")
        LiveEventLogger.log(LogType.LINK, "Starting Nordic BLE scan for ${devices.size} profiles (unfiltered=$unfiltered)...")

        val nativeScanMode = when (scanMode) {
            BleScanModeOption.LOW_POWER -> BleScanMode.SCAN_MODE_LOW_POWER
            BleScanModeOption.BALANCED -> BleScanMode.SCAN_MODE_BALANCED
            BleScanModeOption.LOW_LATENCY -> BleScanMode.SCAN_MODE_LOW_LATENCY
        }
        val scanSettings = BleScannerSettings(
            scanMode = nativeScanMode,
            legacy = true,
        )
        val scanFilters = if (unfiltered) emptyList() else buildScanFilters(devices)
        LiveEventLogger.log(LogType.LINK, "BLE scan mode: ${scanMode.label}, filters: ${scanFilters.size}")

        while (currentCoroutineContext().isActive) {
            awaitScanThrottleSlot()

            try {
                scanner.scan(filters = scanFilters, settings = scanSettings).collect { result ->
                    val deviceName = result.device.name ?: ""
                    val deviceAddress = result.device.address
                    val scanRecord = result.data?.scanRecord
                    val isConnectable = result.data?.isConnectable

                    // Update caches with new data if available
                    var cacheUpdated = false
                    scanRecord?.manufacturerSpecificData?.let {
                        if (it.size() > 0) {
                            manufacturerDataCache[deviceAddress] = it
                            cacheUpdated = true
                        }
                    }
                    scanRecord?.serviceData?.let {
                        if (it.isNotEmpty()) {
                            serviceDataCache[deviceAddress] = it
                            cacheUpdated = true
                        }
                    }
                    scanRecord?.serviceUuids?.let {
                        if (it.isNotEmpty()) {
                            serviceUuidsCache[deviceAddress] = it
                            cacheUpdated = true
                        }
                    }
                    if (cacheUpdated) {
                        cacheTimestamps[deviceAddress] = System.currentTimeMillis()
                    }
                    cleanupOldCaches()

                    // Use merged data for matching and decoding
                    val manufacturerData = manufacturerDataCache[deviceAddress] ?: scanRecord?.manufacturerSpecificData
                    val serviceData = serviceDataCache[deviceAddress] ?: scanRecord?.serviceData ?: emptyMap()
                    val advertisedServiceUuids = serviceUuidsCache[deviceAddress] ?: scanRecord?.serviceUuids.orEmpty()
                    val rawBytes = scanRecord?.bytes

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
                        "${getShortUuid(key.uuid)}: ${value.value.joinToString("") { String.format("%02X", it) }}"
                    }
                    val logMsg = buildString {
                        append("addr=$deviceAddress")
                        if (deviceName.isNotBlank()) append(", name='$deviceName'")
                        if (!mfrHex.isNullOrBlank()) append(", mfr=[$mfrHex]")
                        if (svcHex.isNotBlank()) append(", svc=[$svcHex]")
                        isConnectable?.let { append(", connectable=$it") }
                    }
                    LiveEventLogger.log(LogType.ADV, logMsg)

                    if ((manufacturerData != null && manufacturerData.size() > 0) || serviceData.isNotEmpty()) {
                        val mfrIds = (0 until (manufacturerData?.size() ?: 0)).map { manufacturerData!!.keyAt(it) }
                        val svcUuids = serviceData.keys.map { getShortUuid(it.uuid) }
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
                                isConnectable = isConnectable,
                            ),
                        )
                    }
                }
                // Scan ended without exception — restart immediately (throttle guard above handles rate)
                Log.w(TAG, "BLE scanner flow ended, restarting...")
                LiveEventLogger.log(LogType.LINK, "BLE scan ended, restarting...")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Error in BleScanner stream, restarting...", e)
                LiveEventLogger.log(LogType.LINK, "BLE scan error: ${e.localizedMessage}, restarting...")
            }
        }
    }

    override fun scanForMac(mac: String, scanMode: BleScanModeOption): Flow<Unit> = flow {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "BLUETOOTH_SCAN permission not granted for scanForMac")
            return@flow
        }
        val normalizedMac = mac.uppercase().replace("-", ":")
        val nativeScanMode = when (scanMode) {
            BleScanModeOption.LOW_POWER -> BleScanMode.SCAN_MODE_LOW_POWER
            BleScanModeOption.BALANCED -> BleScanMode.SCAN_MODE_BALANCED
            BleScanModeOption.LOW_LATENCY -> BleScanMode.SCAN_MODE_LOW_LATENCY
        }
        val filters = listOf(BleScanFilter(deviceAddress = normalizedMac))
        val scanner = this@NordicAdvertisementScanner.scanner
        awaitScanThrottleSlot()
        scanner.scan(filters = filters, settings = BleScannerSettings(scanMode = nativeScanMode, legacy = true)).collect { result ->
            val addr = result.device.address?.uppercase()?.replace("-", ":") ?: return@collect
            if (addr == normalizedMac) emit(Unit)
        }
    }

    override fun stop() {
        scanJob?.cancel()
        scanJob = null
        manufacturerDataCache.clear()
        serviceDataCache.clear()
        serviceUuidsCache.clear()
        cacheTimestamps.clear()
    }

    // Derive scan filters from device configs to prevent Android opportunistic-mode throttling
    // in background. A non-empty filter list keeps the scan active even when screen is off.
    // Falls back to empty list (unfiltered) if any device has no filterable property.
    private fun buildScanFilters(devices: List<DeviceConfig>): List<BleScanFilter> {
        val filters = mutableListOf<BleScanFilter>()
        for (d in devices) {
            if (d.source != Source.advertisement) continue
            val match = d.match ?: continue
            when {
                match.mac != null ->
                    filters.add(BleScanFilter(deviceAddress = match.mac.uppercase()))
                match.serviceDataUuid != null ->
                    filters.add(BleScanFilter(
                        serviceData = FilteredServiceData(
                            uuid = android.os.ParcelUuid(uuidFrom(match.serviceDataUuid)),
                            data = DataByteArray(ByteArray(0))
                        )
                    ))
                match.manufacturerId != null -> {
                    // Empty data bytes (ByteArray(0)) is ambiguous: some BLE chips interpret it
                    // as "payload must be empty", silently dropping all non-empty payloads.
                    // Use manufacturer_hex_prefix bytes as the filter when available — this gives
                    // a reliable, non-empty hardware filter that also enables background scanning.
                    val prefixBytes = match.manufacturerHexPrefix
                        ?.chunked(2)
                        ?.mapNotNull { it.toIntOrNull(16)?.toByte() }
                        ?.toByteArray()
                        ?.takeIf { it.isNotEmpty() }
                    filters.add(BleScanFilter(
                        manufacturerData = FilteredManufacturerData(
                            id = match.manufacturerId,
                            data = DataByteArray(prefixBytes ?: ByteArray(0))
                        )
                    ))
                }
                else -> return emptyList()
            }
        }
        return filters
    }

    private fun cleanupOldCaches() {
        val now = System.currentTimeMillis()
        val expiredThreshold = 60_000L // 1 minute
        val expiredAddresses = cacheTimestamps.filter { now - it.value > expiredThreshold }.keys
        if (expiredAddresses.isNotEmpty()) {
            for (addr in expiredAddresses) {
                manufacturerDataCache.remove(addr)
                serviceDataCache.remove(addr)
                serviceUuidsCache.remove(addr)
                cacheTimestamps.remove(addr)
            }
            LiveEventLogger.logRes(LogType.LINK, R.string.log_ble_cache_expired, expiredAddresses.size)
        }
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

    override fun connect(
        device: DeviceConfig,
        waitForDevice: suspend () -> Unit,
        autoReconnect: Boolean,
    ): Flow<RawReading> = flow {
        val mac = device.gatt?.mac ?: return@flow
        val serviceUuidStr = device.gatt.serviceUuid
        val notifyCharUuidStr = device.gatt.notifyCharUuid

        try {
            while (currentCoroutineContext().isActive) {
                waitForDevice()
                try {
                    Log.d(TAG, "Connecting to GATT device ${device.id} at $mac")
                    onLinkStatus(DeviceLinkStatus(device.id, DeviceLinkState.Connecting, mac))
                    val client = ClientBleGatt.connect(context, mac, scope)
                    activeConnections[device.id] = client
                    onLinkStatus(DeviceLinkStatus(device.id, DeviceLinkState.Connected, mac))

                    val services = client.discoverServices()
                    val service = services.findService(uuidFrom(serviceUuidStr))
                    val characteristic = service?.findCharacteristic(uuidFrom(notifyCharUuidStr))

                    if (characteristic != null) {
                        Log.d(TAG, "Subscribing to notifications on $notifyCharUuidStr")
                        characteristic.getNotifications().collect { bytes ->
                            onLinkStatus(DeviceLinkStatus(device.id, DeviceLinkState.Polling, mac, System.currentTimeMillis()))
                            val hex = bytes.value.joinToString("") { String.format("%02X", it) }
                            emit(RawReading(deviceId = device.id, source = "gatt_notify", rawHex = hex))
                        }
                        onLinkStatus(DeviceLinkStatus(device.id, DeviceLinkState.Disconnected, mac))
                        activeConnections.remove(device.id)
                        if (!autoReconnect) break
                        delay(3_000)
                    } else {
                        throw IllegalStateException("Notify characteristic $notifyCharUuidStr not found")
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "GATT session ended for ${device.id}: ${e.message}")
                    onLinkStatus(
                        DeviceLinkStatus(device.id, DeviceLinkState.Disconnected, mac, errorMessage = e.message),
                    )
                    activeConnections.remove(device.id)
                    if (!autoReconnect) break
                    delay(3_000)
                }
            }
        } finally {
            disconnect(device.id)
        }
    }

    override suspend fun write(device: DeviceConfig, hex: String) {
        val client = activeConnections[device.id] ?: return
        val serviceUuidStr = device.gatt?.serviceUuid ?: return
        val writeCharUuidStr = device.gatt.writeCharUuid ?: return
        val bytes = hexToBytes(hex) ?: return

        try {
            val services = client.discoverServices()
            val service = services.findService(uuidFrom(serviceUuidStr))
            val characteristic = service?.findCharacteristic(uuidFrom(writeCharUuidStr))
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
    private val onLinkStatus: (DeviceLinkStatus) -> Unit = {},
) : Elm327Source {
    private val activeConnections = java.util.concurrent.ConcurrentHashMap<String, ClientBleGatt>()
    private val deviceMutexes = java.util.concurrent.ConcurrentHashMap<String, Mutex>()
    private val pendingDeferreds = java.util.concurrent.ConcurrentHashMap<String, CompletableDeferred<String>>()

    private data class PollTarget(
        val sensor: SensorConfig,
        var nextPollAtMs: Long = 0L,
    )

    private data class TxItem(
        val cmd: String,
        val pollTarget: PollTarget? = null,
    )

    override fun connect(
        device: DeviceConfig,
        enabledKeys: Set<String>,
        waitForDevice: suspend () -> Unit,
        autoReconnect: Boolean,
    ): Flow<RawReading> = flow {
        val mac = device.obd?.mac ?: return@flow
        try {
            while (currentCoroutineContext().isActive) {
                waitForDevice()
                try {
                    runObdSession(this, device, enabledKeys, mac)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "OBD session ended for ${device.id}: ${e.message}")
                    onLinkStatus(
                        DeviceLinkStatus(device.id, DeviceLinkState.Disconnected, mac, errorMessage = e.message),
                    )
                    teardown(device.id)
                    if (!autoReconnect) break
                    delay(3_000)
                }
            }
        } finally {
            teardown(device.id)
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

        // coroutineScope로 GATT 연결·rxJob·폴 루프를 단일 구조화 스코프로 묶는다.
        // 폴 루프 예외나 외부 Job 취소 시 rxJob과 GATT 내부 코루틴이 함께 정리된다.
        coroutineScope {
            val client = ClientBleGatt.connect(context, mac, this)
            activeConnections[device.id] = client
            onLinkStatus(DeviceLinkStatus(device.id, DeviceLinkState.Connected, mac))

            launch {
                client.connectionState.collect { st ->
                    if (st == GattConnectionState.STATE_DISCONNECTED) {
                        onLinkStatus(DeviceLinkStatus(device.id, DeviceLinkState.Disconnected, mac))
                        throw IOException("BLE disconnected")
                    }
                }
            }

            val services = client.discoverServices()
            val service = services.findService(uuidFrom(serviceUuidStr))
            val txChar = service?.findCharacteristic(uuidFrom(txCharUuidStr))
            val rxChar = service?.findCharacteristic(uuidFrom(rxCharUuidStr))
                ?: throw IllegalStateException("OBD RX characteristic not found")

            if (txChar == null) throw IllegalStateException("OBD TX characteristic not found")

            val responseBuffer = StringBuilder()
            val rxJob = launch {
                rxChar.getNotifications().collect { bytes ->
                    val chunk = String(bytes.value, Charsets.US_ASCII)
                    responseBuffer.append(chunk)
                    if (responseBuffer.contains(">")) {
                        val fullResponse = responseBuffer.toString().trim()
                        responseBuffer.clear()
                        pendingDeferreds[device.id]?.complete(fullResponse)
                    }
                }
            }

            val targets = device.sensors
                .filter { it.key in enabledKeys && it.pid != null }
                .map { PollTarget(it, System.currentTimeMillis()) }
            if (targets.isEmpty()) {
                onLinkStatus(DeviceLinkStatus(device.id, DeviceLinkState.Connected, mac))
                rxJob.cancel()
                return@coroutineScope
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
                while (isActive) {
                    val now = System.currentTimeMillis()

                    if (txQueue.isNotEmpty() && now - lastTxAtMs >= txDelayMs) {
                        val item = txQueue.removeFirst()
                        val resp = sendCommand(device.id, txChar, item.cmd)
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
            }
        }
    }

    private suspend fun sendCommand(
        deviceId: String,
        txChar: ClientBleGattCharacteristic,
        cmd: String,
    ): String? {
        val mutex = deviceMutexes.getOrPut(deviceId) { Mutex() }
        return mutex.withLock {
            val deferred = CompletableDeferred<String>()
            pendingDeferreds[deviceId] = deferred
            val payload = (cmd + "\r").toByteArray(Charsets.US_ASCII)
            val timeoutMs = if (cmd.length >= 6) MULTIFRAME_TIMEOUT_MS else SINGLE_FRAME_TIMEOUT_MS

            try {
                txChar.write(DataByteArray(payload), BleWriteType.NO_RESPONSE)
            } catch (e: CancellationException) {
                pendingDeferreds.remove(deviceId)
                throw e
            } catch (e: Exception) {
                pendingDeferreds.remove(deviceId)
                Log.w(TAG, "OBD write error — connection likely lost: ${e.message}")
                throw IOException("BLE write failed: ${e.message}", e)
            }

            val resp = withTimeoutOrNull(timeoutMs) { deferred.await() }
            pendingDeferreds.remove(deviceId)
            resp
        }
    }

    override suspend fun write(device: DeviceConfig, hex: String) {
        val client = activeConnections[device.id] ?: return
        val serviceUuidStr = device.obd?.serviceUuid ?: return
        val txCharUuidStr = device.obd.txCharUuid
        val bytes = hexToBytes(hex) ?: return

        try {
            val services = client.discoverServices()
            val service = services.findService(uuidFrom(serviceUuidStr))
            val characteristic = service?.findCharacteristic(uuidFrom(txCharUuidStr))
            characteristic?.write(DataByteArray(bytes), BleWriteType.NO_RESPONSE)
        } catch (e: Exception) {
            Log.e(TAG, "Error writing raw hex to OBD dongle ${device.id}", e)
        }
    }

    override fun disconnect(deviceId: String) {
        teardown(deviceId)
    }

    private fun teardown(deviceId: String) {
        // rxJob은 coroutineScope 내 자식 코루틴이므로 외부 Job 취소 시 자동 정리됨.
        // 명시적 disconnect만 처리한다.
        activeConnections.remove(deviceId)?.disconnect()
        deviceMutexes.remove(deviceId)
        pendingDeferreds.remove(deviceId)
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

        private const val POLL_LOOP_MS = 10L
        private const val SINGLE_FRAME_TIMEOUT_MS = 2_000L
        private const val MULTIFRAME_TIMEOUT_MS = 5_000L
    }
}

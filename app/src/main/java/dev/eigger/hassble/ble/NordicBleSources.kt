package dev.eigger.hassble.ble

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import dev.eigger.hassble.config.DeviceConfig
import dev.eigger.hassble.config.Source
import dev.eigger.hassble.config.parseDurationMs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import no.nordicsemi.android.kotlin.ble.client.main.callback.ClientBleGatt
import no.nordicsemi.android.kotlin.ble.client.main.service.ClientBleGattCharacteristic
import no.nordicsemi.android.kotlin.ble.core.data.util.DataByteArray
import no.nordicsemi.android.kotlin.ble.core.data.BleWriteType
import no.nordicsemi.android.kotlin.ble.scanner.BleScanner
import java.util.UUID

private const val TAG = "HassBleSources"

/**
 * 경로 A: 광고 passive scan.
 * BleScanner(context).scan() 결과를 설정 필터(namePrefix, serviceDataUuid 등)와 대조하여
 * 매칭되는 기기의 페이로드 데이터를 추출하여 방출합니다.
 */
class NordicAdvertisementScanner(private val context: Context) : AdvertisementScanner {
    private var scanJob: Job? = null

    override fun scan(devices: List<DeviceConfig>): Flow<RawReading> = flow {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "BLUETOOTH_SCAN permission not granted")
            return@flow
        }

        val scanner = BleScanner(context)
        Log.d(TAG, "Starting Nordic BLE scan for ${devices.size} advertisement profiles")

        try {
            scanner.scan().collect { result ->
                val deviceName = result.device.name ?: ""
                val deviceAddress = result.device.address
                val scanRecord = result.data?.scanRecord
                val serviceData = scanRecord?.serviceData ?: emptyMap()
                val manufacturerData = scanRecord?.manufacturerSpecificData
                val rawBytes = scanRecord?.bytes

                for (d in devices) {
                    if (d.source != Source.advertisement) continue
                    val match = d.match ?: continue

                    var isMatch = false
                    if (match.mac != null && match.mac.equals(deviceAddress, ignoreCase = true)) {
                        isMatch = true
                    }
                    if (!isMatch && match.serviceDataUuid != null) {
                        val targetUuid = match.serviceDataUuid.uppercase()
                        val hasUuid = serviceData.keys.any { 
                            it.uuid.toString().uppercase().contains(targetUuid) 
                        }
                        if (hasUuid) isMatch = true
                    }
                    if (!isMatch && match.namePrefix != null && deviceName.startsWith(match.namePrefix, ignoreCase = true)) {
                        isMatch = true
                    }
                    if (!isMatch && match.manufacturerId != null && manufacturerData != null) {
                        if (manufacturerData.get(match.manufacturerId) != null) {
                            isMatch = true
                        }
                    }

                    if (isMatch) {
                        val rawHex = when (d.sensors.firstOrNull()?.sourceField) {
                            dev.eigger.hassble.config.SourceField.service_data -> {
                                val entry = serviceData.entries.firstOrNull { 
                                    match.serviceDataUuid == null || it.key.uuid.toString().uppercase().contains(match.serviceDataUuid.uppercase())
                                }
                                entry?.value?.value?.let { bytesToHex(it) }
                            }
                            dev.eigger.hassble.config.SourceField.manufacturer_data -> {
                                val key = match.manufacturerId
                                if (key != null && manufacturerData != null) {
                                    manufacturerData.get(key)?.value?.let { bytesToHex(it) }
                                } else null
                            }
                            else -> rawBytes?.value?.let { bytesToHex(it) }
                        }

                        if (rawHex != null) {
                            emit(RawReading(
                                deviceId = d.id,
                                source = "advertisement",
                                rawHex = rawHex,
                                macAddress = deviceAddress,
                                deviceName = deviceName.takeIf { it.isNotBlank() },
                            ))
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in BleScanner stream", e)
        }
    }

    override fun stop() {
        scanJob?.cancel()
        scanJob = null
    }

    private fun bytesToHex(bytes: ByteArray): String =
        bytes.joinToString("") { String.format("%02X", it) }
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
 * 경로 C: OBD (ELM327) 폴링.
 * 바인딩된 OBD BLE 동글에 연결한 뒤 rx/tx 캐릭터리스틱을 통해 쿼리-응답 루프를 돌며 센서 값들을 폴링합니다.
 */
class NordicElm327Source(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onLinkStatus: (DeviceLinkStatus) -> Unit = {},
) : Elm327Source {
    private val activeConnections = mutableMapOf<String, ClientBleGatt>()
    private val responseMutex = Mutex()
    private var pendingDeferred: CompletableDeferred<String>? = null
    private val activeJobs = mutableListOf<Job>()

    override fun connect(device: DeviceConfig, enabledKeys: Set<String>): Flow<RawReading> = flow {
        val mac = device.obd?.mac ?: return@flow
        val serviceUuidStr = device.obd.serviceUuid
        val txCharUuidStr = device.obd.txCharUuid
        val rxCharUuidStr = device.obd.rxCharUuid
        val txDelayMs = parseDurationMs(device.obd.txDelay, 50L)

        try {
            Log.d(TAG, "Connecting to OBD reader ${device.id} at $mac")
            onLinkStatus(DeviceLinkStatus(device.id, DeviceLinkState.Connecting, mac))
            val client = ClientBleGatt.connect(context, mac, scope)
            activeConnections[device.id] = client
            onLinkStatus(DeviceLinkStatus(device.id, DeviceLinkState.Connected, mac))

            val services = client.discoverServices()
            val service = services.findService(UUID.fromString(serviceUuidStr))
            val txChar = service?.findCharacteristic(UUID.fromString(txCharUuidStr))
            val rxChar = service?.findCharacteristic(UUID.fromString(rxCharUuidStr))

            if (txChar != null && rxChar != null) {
                // rx notifications 수집 코루틴 가동
                val responseBuffer = StringBuilder()
                val rxJob = scope.launch {
                    try {
                        rxChar.getNotifications().collect { bytes ->
                            val chunk = String(bytes.value, Charsets.US_ASCII)
                            responseBuffer.append(chunk)
                            if (responseBuffer.contains(">")) {
                                val fullResponse = responseBuffer.toString().trim()
                                responseBuffer.clear()
                                pendingDeferred?.complete(fullResponse)
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "OBD notify stream error", e)
                    }
                }
                activeJobs.add(rxJob)

                // 1. ELM327 Base Initialization
                Log.d(TAG, "Initializing OBD dongle...")
                for (cmd in Elm327Source.BASE_INIT) {
                    sendCommand(client, txChar, cmd, txDelayMs)
                }

                // 2. Custom Configuration Init Commands
                for (cmd in device.obd.initCommands) {
                    sendCommand(client, txChar, cmd, txDelayMs)
                }

                // 3. 센서별 개별 폴링 코루틴 예약
                onLinkStatus(DeviceLinkStatus(device.id, DeviceLinkState.Polling, mac))
                for (sensor in device.sensors) {
                    if (sensor.key !in enabledKeys) continue
                    val intervalMs = parseDurationMs(sensor.updateInterval, 60000L)
                    val mode = sensor.mode
                    val pid = sensor.pid ?: continue
                    val cmd = "$mode$pid"

                    val pollJob = scope.launch {
                        while (true) {
                            delay(intervalMs)
                            val rawResponse = sendCommand(client, txChar, cmd, txDelayMs)
                            if (rawResponse != null) {
                                onLinkStatus(DeviceLinkStatus(device.id, DeviceLinkState.Polling, mac, System.currentTimeMillis()))
                                val cleanHex = rawResponse.replace("\r", "")
                                    .replace("\n", "")
                                    .replace(" ", "")
                                    .replace(">", "")
                                
                                emit(RawReading(deviceId = device.id, source = "obd", rawHex = cleanHex))
                            }
                        }
                    }
                    activeJobs.add(pollJob)
                }
            } else {
                Log.e(TAG, "OBD tx ($txCharUuidStr) or rx ($rxCharUuidStr) characteristic not found")
                onLinkStatus(DeviceLinkStatus(device.id, DeviceLinkState.Error, mac, errorMessage = "TX/RX char not found"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed connecting/subscribing to OBD dongle ${device.id}", e)
            onLinkStatus(DeviceLinkStatus(device.id, DeviceLinkState.Error, mac, errorMessage = e.message))
        }
    }

    private suspend fun sendCommand(
        client: ClientBleGatt,
        txChar: ClientBleGattCharacteristic,
        cmd: String,
        delayMs: Long
    ): String? = responseMutex.withLock {
        val deferred = CompletableDeferred<String>()
        pendingDeferred = deferred
        val payload = (cmd + "\r").toByteArray(Charsets.US_ASCII)

        try {
            txChar.write(DataByteArray(payload), BleWriteType.NO_RESPONSE)
            val resp = withTimeoutOrNull(2000) { deferred.await() }
            pendingDeferred = null
            delay(delayMs)
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
        activeJobs.forEach { it.cancel() }
        activeJobs.clear()
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

package dev.eigger.hassble.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertisingSet
import android.bluetooth.le.AdvertisingSetCallback
import android.bluetooth.le.AdvertisingSetParameters
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import dev.eigger.hassble.config.AdvertiseConfig
import dev.eigger.hassble.config.AdvertiseModeOption
import dev.eigger.hassble.config.AdvertiseTxPowerOption
import dev.eigger.hassble.config.parseDurationMs
import dev.eigger.hassble.service.LiveEventLogger
import dev.eigger.hassble.service.LogType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

class AndroidBleAdvertiser(
    private val context: Context,
    private val scope: CoroutineScope,
) : BleAdvertiser {

    private class Session(
        val config: AdvertiseConfig,
        val callback: AdvertisingSetCallback,
        var advertisingSet: AdvertisingSet? = null,
        var job: Job? = null,
        var counter: Int,
        val onCounter: (Int) -> Unit,
        val onStopped: (AdvertiseStopReason) -> Unit,
        @Volatile var isStarted: Boolean = false,
    )

    private val sessions = ConcurrentHashMap<String, Session>()

    private fun hasAdvertisePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_ADVERTISE,
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_ADMIN,
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun getBluetoothAdvertiser(): BluetoothLeAdvertiser? {
        val manager = context.getSystemService(BluetoothManager::class.java) ?: return null
        val adapter = manager.adapter ?: return null
        if (!adapter.isEnabled) return null
        return adapter.bluetoothLeAdvertiser
    }

    // Permission check has already been performed in hasAdvertisePermission() before calling
    // BluetoothLeAdvertiser methods. Any runtime revocation surfaces as SecurityException and is
    // caught cleanly.
    @SuppressLint("MissingPermission")
    override fun start(
        deviceId: String,
        config: AdvertiseConfig,
        counterSeed: Int,
        onCounter: (Int) -> Unit,
        onStopped: (AdvertiseStopReason) -> Unit,
    ) {
        // If already advertising for this device, stop existing session first
        if (sessions.containsKey(deviceId)) {
            stop(deviceId, AdvertiseStopReason.Manual)
        }

        if (!hasAdvertisePermission()) {
            LiveEventLogger.log(
                LogType.TX,
                "BLE Advertise failed: BLUETOOTH_ADVERTISE permission not granted (device=$deviceId)",
            )
            onStopped(AdvertiseStopReason.Error)
            return
        }

        val advertiser = getBluetoothAdvertiser()
        if (advertiser == null) {
            val manager = context.getSystemService(BluetoothManager::class.java)
            val adapter = manager?.adapter
            val reason = when {
                adapter == null || !adapter.isEnabled -> "Bluetooth is disabled"
                else -> "BLE advertisement not supported on this device"
            }
            LiveEventLogger.log(LogType.TX, "BLE Advertise failed: $reason (device=$deviceId)")
            onStopped(AdvertiseStopReason.Error)
            return
        }

        val initialData = buildAdvertiseData(config, counterSeed)
        if (initialData == null) {
            LiveEventLogger.log(
                LogType.TX,
                "BLE Advertise failed: invalid payload template '${config.payload}' (device=$deviceId)",
            )
            onStopped(AdvertiseStopReason.Error)
            return
        }

        val parameters = AdvertisingSetParameters.Builder()
            .setLegacyMode(true)
            .setScannable(config.scannable)
            .setConnectable(config.connectable)
            .setInterval(
                when (config.mode) {
                    AdvertiseModeOption.low_power -> AdvertisingSetParameters.INTERVAL_HIGH
                    AdvertiseModeOption.balanced -> AdvertisingSetParameters.INTERVAL_MEDIUM
                    AdvertiseModeOption.low_latency -> AdvertisingSetParameters.INTERVAL_LOW
                },
            )
            .setTxPowerLevel(
                when (config.txPower) {
                    AdvertiseTxPowerOption.ultra_low -> AdvertisingSetParameters.TX_POWER_ULTRA_LOW
                    AdvertiseTxPowerOption.low -> AdvertisingSetParameters.TX_POWER_LOW
                    AdvertiseTxPowerOption.medium -> AdvertisingSetParameters.TX_POWER_MEDIUM
                    AdvertiseTxPowerOption.high -> AdvertisingSetParameters.TX_POWER_HIGH
                },
            )
            .build()

        var sessionRef: Session? = null

        val callback = object : AdvertisingSetCallback() {
            override fun onAdvertisingSetStarted(
                advertisingSet: AdvertisingSet?,
                txPower: Int,
                status: Int,
            ) {
                val currentSession = sessionRef ?: return
                if (status == ADVERTISE_SUCCESS) {
                    currentSession.advertisingSet = advertisingSet
                    currentSession.isStarted = true
                    val hex = AdvertisePayload.render(config.payload, currentSession.counter)
                    LiveEventLogger.log(
                        LogType.TX,
                        "BLE Advertise started: device=$deviceId, txPower=$txPower, hex=$hex",
                    )
                } else {
                    val statusText = when (status) {
                        ADVERTISE_FAILED_DATA_TOO_LARGE -> "DATA_TOO_LARGE(1)"
                        ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "TOO_MANY_ADVERTISERS(2)"
                        ADVERTISE_FAILED_ALREADY_STARTED -> "ALREADY_STARTED(3)"
                        ADVERTISE_FAILED_INTERNAL_ERROR -> "INTERNAL_ERROR(4)"
                        ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "FEATURE_UNSUPPORTED(5)"
                        else -> "STATUS_$status"
                    }
                    LiveEventLogger.log(
                        LogType.TX,
                        "BLE Advertise start failed: device=$deviceId, reason=$statusText",
                    )
                    stop(deviceId, AdvertiseStopReason.Error)
                }
            }

            override fun onAdvertisingSetStopped(advertisingSet: AdvertisingSet?) {
                LiveEventLogger.log(LogType.TX, "BLE Advertise stopped: device=$deviceId")
            }
        }

        val session = Session(
            config = config,
            callback = callback,
            counter = counterSeed,
            onCounter = onCounter,
            onStopped = onStopped,
        )
        sessionRef = session
        sessions[deviceId] = session

        try {
            advertiser.startAdvertisingSet(parameters, initialData, null, null, null, callback)
        } catch (e: Exception) {
            LiveEventLogger.log(
                LogType.TX,
                "BLE Advertise start exception: device=$deviceId, error=${e.message}",
            )
            sessions.remove(deviceId)
            onStopped(AdvertiseStopReason.Error)
            return
        }

        session.job = scope.launch {
            val timeoutMs = parseDurationMs(config.timeout, 15_000)
            val repeatMs = config.repeatInterval?.let { parseDurationMs(it, 0) }?.takeIf { it > 0 }

            val completedNormally = withTimeoutOrNull(timeoutMs) {
                if (repeatMs != null) {
                    while (isActive) {
                        delay(repeatMs)
                        session.counter = AdvertisePayload.nextCounter(session.counter)
                        session.onCounter(session.counter)
                        val updatedData = buildAdvertiseData(config, session.counter)
                        if (updatedData != null) {
                            val hex = AdvertisePayload.render(config.payload, session.counter)
                            LiveEventLogger.log(
                                LogType.TX,
                                "BLE Advertise updated: device=$deviceId, counter=${session.counter}, hex=$hex",
                            )
                            runCatching {
                                session.advertisingSet?.setAdvertisingData(updatedData)
                            }
                        }
                    }
                } else {
                    awaitCancellation()
                }
            }
            if (completedNormally == null) {
                stop(deviceId, AdvertiseStopReason.Timeout)
            }
        }
    }

    // Permission check has already been performed before session creation. Any security exception
    // when stopping is ignored cleanly.
    @SuppressLint("MissingPermission")
    override fun stop(deviceId: String, reason: AdvertiseStopReason) {
        val session = sessions.remove(deviceId) ?: return
        session.job?.cancel()
        session.job = null

        val advertiser = getBluetoothAdvertiser()
        if (advertiser != null && (session.advertisingSet != null || session.isStarted)) {
            runCatching { advertiser.stopAdvertisingSet(session.callback) }
        }
        session.onStopped(reason)
    }

    override fun stopAll() {
        val keys = sessions.keys().toList()
        for (key in keys) {
            stop(key, AdvertiseStopReason.Shutdown)
        }
    }

    override fun isAdvertising(deviceId: String): Boolean {
        return sessions.containsKey(deviceId)
    }

    private fun buildAdvertiseData(config: AdvertiseConfig, counter: Int): AdvertiseData? {
        val renderedHex = AdvertisePayload.render(config.payload, counter)
        val bytes = AdvertisePayload.toBytes(renderedHex) ?: return null
        return AdvertiseData.Builder()
            .addManufacturerData(config.manufacturerId, bytes)
            .setIncludeDeviceName(config.includeDeviceName)
            .setIncludeTxPowerLevel(false)
            .build()
    }
}

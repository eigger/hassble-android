package dev.eigger.hassble.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertisingSet
import android.bluetooth.le.AdvertisingSetCallback
import android.bluetooth.le.AdvertisingSetParameters
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat
import dev.eigger.hassble.R
import dev.eigger.hassble.config.AdvertiseConfig
import dev.eigger.hassble.config.AdvertiseModeOption
import dev.eigger.hassble.config.AdvertisePayloadPhase
import dev.eigger.hassble.config.AdvertiseTxPowerOption
import dev.eigger.hassble.config.parseDurationMs
import dev.eigger.hassble.service.LiveEventLogger
import dev.eigger.hassble.service.LogType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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

    private val advertiserScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private class Session(
        val config: AdvertiseConfig,
        val callback: AdvertisingSetCallback,
        var advertisingSet: AdvertisingSet? = null,
        var job: Job? = null,
        var wakeLock: PowerManager.WakeLock? = null,
        var counter: Int,
        val onCounter: (Int) -> Unit,
        val onStopped: (AdvertiseStopReason) -> Unit,
        @Volatile var isStarted: Boolean = false,
        @Volatile var stopRequested: Boolean = false,
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
    ): Boolean {
        // If already advertising for this device, stop existing session first
        if (sessions.containsKey(deviceId)) {
            stop(deviceId, AdvertiseStopReason.Manual)
        }

        if (!hasAdvertisePermission()) {
            val msg = "${context.getString(R.string.log_advertise_no_permission)} (device=$deviceId)"
            LiveEventLogger.log(LogType.TX, msg)
            onStopped(AdvertiseStopReason.Error)
            return false
        }

        val advertiser = getBluetoothAdvertiser()
        if (advertiser == null) {
            val manager = context.getSystemService(BluetoothManager::class.java)
            val adapter = manager?.adapter
            val reason = when {
                adapter == null || !adapter.isEnabled -> "Bluetooth is disabled"
                else -> context.getString(R.string.log_advertise_unsupported)
            }
            LiveEventLogger.log(LogType.TX, "BLE Advertise failed: $reason (device=$deviceId)")
            onStopped(AdvertiseStopReason.Error)
            return false
        }

        val phases = config.payloadPhases
        val initialPhase = phases.firstOrNull()
        val initialData = buildAdvertiseData(config, counterSeed, initialPhase)
        if (initialData == null) {
            LiveEventLogger.log(
                LogType.TX,
                "BLE Advertise failed: invalid payload template '${config.payload}' (device=$deviceId)",
            )
            onStopped(AdvertiseStopReason.Error)
            return false
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
                if (sessions[deviceId] !== currentSession || currentSession.stopRequested) {
                    // Session was cancelled or replaced before start callback arrived.
                    // Stop newly started hardware advertising set while the callback wrapper mapping is intact.
                    if (status == ADVERTISE_SUCCESS) {
                        val advertiser = getBluetoothAdvertiser()
                        runCatching { advertiser?.stopAdvertisingSet(this) }
                    }
                    return
                }

                if (status == ADVERTISE_SUCCESS) {
                    currentSession.advertisingSet = advertisingSet
                    currentSession.isStarted = true
                    val hex = AdvertisePayload.renderPhase(config.payload, currentSession.counter, initialPhase)
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

            override fun onAdvertisingEnabled(
                advertisingSet: AdvertisingSet?,
                enable: Boolean,
                status: Int,
            ) {
                val currentSession = sessionRef ?: return
                if (sessions[deviceId] !== currentSession) return
                if (!enable) {
                    val isConnectable = config.connectable
                    val msg = if (isConnectable) {
                        "BLE Advertise disabled (duration ended or peer connected): device=$deviceId, status=$status"
                    } else {
                        "BLE Advertise hardware duration ended: device=$deviceId, status=$status"
                    }
                    LiveEventLogger.log(LogType.TX, msg)
                    stop(deviceId, AdvertiseStopReason.Timeout)
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
        session.onCounter(session.counter)

        val timeoutMs = parseDurationMs(config.timeout, 15_000)
        val pm = context.getSystemService(PowerManager::class.java)
        val wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "hassble:ble_advertise_$deviceId")?.apply {
            setReferenceCounted(false)
        }
        runCatching {
            wakeLock?.acquire(timeoutMs + 2_000L)
        }
        session.wakeLock = wakeLock

        session.job = scope.launch {
            val repeatMs = if (phases.isEmpty()) {
                config.repeatInterval?.let { parseDurationMs(it, 0) }?.takeIf { it > 0 }
            } else {
                null
            }

            val completedNormally = withTimeoutOrNull(timeoutMs) {
                when {
                    phases.isNotEmpty() -> {
                        while (sessions[deviceId] === session && isActive && !session.isStarted) {
                            delay(20)
                        }
                        if (sessions[deviceId] !== session || !session.isStarted) {
                            return@withTimeoutOrNull true
                        }
                        for ((index, phase) in phases.withIndex()) {
                            if (sessions[deviceId] !== session) return@withTimeoutOrNull true
                            if (index > 0) {
                                updateAdvertisingData(session, deviceId, phase)
                            }
                            val phaseMs = parseDurationMs(phase.duration, 0).takeIf { it > 0 } ?: continue
                            delay(phaseMs)
                        }
                    }
                    repeatMs != null -> {
                        while (isActive) {
                            delay(repeatMs)
                            if (sessions[deviceId] !== session) return@withTimeoutOrNull true
                            session.counter = AdvertisePayload.nextCounter(session.counter)
                            session.onCounter(session.counter)
                            updateAdvertisingData(session, deviceId, phase = null)
                        }
                    }
                    else -> awaitCancellation()
                }
            }
            if (sessions[deviceId] !== session) return@launch
            if (completedNormally == null || phases.isNotEmpty()) {
                stop(deviceId, AdvertiseStopReason.Timeout)
            }
        }

        try {
            // duration is in units of 10ms (1 to 65535, 0 = unlimited).
            // If timeout exceeds hardware limit (~655.35s), pass 0 (unlimited) and rely on the coroutine timer.
            val rawUnits = (timeoutMs + 9) / 10
            val durationUnits = if (rawUnits in 1..65535) rawUnits.toInt() else 0
            advertiser.startAdvertisingSet(parameters, initialData, null, null, null, durationUnits, 0, callback)
        } catch (e: Exception) {
            LiveEventLogger.log(
                LogType.TX,
                "BLE Advertise start exception: device=$deviceId, error=${e.message}",
            )
            stop(deviceId, AdvertiseStopReason.Error)
            return false
        }

        return true
    }

    // Permission check has already been performed before session creation. Any security exception
    // when stopping is ignored cleanly.
    @SuppressLint("MissingPermission")
    override fun stop(deviceId: String, reason: AdvertiseStopReason) {
        val session = sessions.remove(deviceId) ?: return
        session.stopRequested = true
        session.job?.cancel()
        session.job = null

        val advertiser = getBluetoothAdvertiser()
        if (advertiser != null && session.isStarted) {
            runCatching { advertiser.stopAdvertisingSet(session.callback) }
        }

        try {
            session.onStopped(reason)
        } finally {
            val wl = session.wakeLock
            session.wakeLock = null
            if (wl != null && wl.isHeld) {
                // Keep wakeLock held briefly on dedicated advertiserScope so background network flush (OkHttp WS) can complete
                advertiserScope.launch {
                    delay(500)
                    if (wl.isHeld) {
                        runCatching { wl.release() }
                    }
                }
            }
        }
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

    private fun updateAdvertisingData(session: Session, deviceId: String, phase: AdvertisePayloadPhase?) {
        val updatedData = buildAdvertiseData(session.config, session.counter, phase) ?: return
        val advertisingSet = session.advertisingSet
        if (advertisingSet == null) {
            LiveEventLogger.log(
                LogType.TX,
                "BLE Advertise update skipped: device=$deviceId, advertising set not ready",
            )
            return
        }
        val hex = AdvertisePayload.renderPhase(session.config.payload, session.counter, phase)
        val stateNote = phase?.state?.let { ", state=$it" } ?: ""
        LiveEventLogger.log(
            LogType.TX,
            "BLE Advertise updated: device=$deviceId, counter=${session.counter}$stateNote, hex=$hex",
        )
        runCatching {
            advertisingSet.setAdvertisingData(updatedData)
        }
    }

    private fun buildAdvertiseData(
        config: AdvertiseConfig,
        counter: Int,
        phase: AdvertisePayloadPhase? = null,
    ): AdvertiseData? {
        val renderedHex = AdvertisePayload.renderPhase(config.payload, counter, phase)
        val bytes = AdvertisePayload.toBytes(renderedHex) ?: return null
        return AdvertiseData.Builder()
            .addManufacturerData(config.manufacturerId, bytes)
            .setIncludeDeviceName(config.includeDeviceName)
            .setIncludeTxPowerLevel(false)
            .build()
    }
}

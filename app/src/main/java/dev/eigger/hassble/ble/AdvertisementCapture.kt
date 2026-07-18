package dev.eigger.hassble.ble

import android.annotation.SuppressLint
import android.bluetooth.le.ScanResult

/**
 * BLE 광고 스캔 결과에서 마법사용 payload를 추출한다.
 */
object AdvertisementCapture {

    data class CapturedAdvertisement(
        val name: String,
        val address: String,
        val rssi: Int,
        val manufacturerId: Int?,
        val manufacturerHex: String?,
        val serviceUuid: String?,
        val serviceDataHex: String?,
        val fullScanHex: String?,
    ) {
        fun payloadHex(field: dev.eigger.hassble.config.SourceField): String? = when (field) {
            dev.eigger.hassble.config.SourceField.manufacturer_data -> manufacturerHex
            dev.eigger.hassble.config.SourceField.service_data -> serviceDataHex
            dev.eigger.hassble.config.SourceField.raw -> fullScanHex
        }
    }

    // BLUETOOTH_CONNECT is required for BluetoothDevice.getName() on API 31+. Safe here: this is
    // only reached from a ScanCallback fired by an active scan, which the caller already gates
    // behind a BLUETOOTH_SCAN permission check (see AdvertisementWizardDialog's startScan calls).
    @SuppressLint("MissingPermission")
    fun fromScanResult(result: ScanResult, unknownName: String): CapturedAdvertisement {
        val record = result.scanRecord
        val name = result.device.name?.takeIf { it.isNotBlank() } ?: unknownName
        val address = result.device.address
        val rssi = result.rssi

        var manufacturerId: Int? = null
        var manufacturerHex: String? = null
        val mfg = record?.manufacturerSpecificData
        if (mfg != null && mfg.size() > 0) {
            var bestId = mfg.keyAt(0)
            var bestPayload = mfg.get(bestId)
            for (i in 1 until mfg.size()) {
                val id = mfg.keyAt(i)
                val payload = mfg.get(id)
                if (payload != null && (bestPayload == null || payload.size > bestPayload.size)) {
                    bestId = id
                    bestPayload = payload
                }
            }
            manufacturerId = bestId
            manufacturerHex = bestPayload?.takeIf { it.isNotEmpty() }?.let { AdvertisementMatcher.bytesToHex(it) }
        }

        var serviceUuid: String? = null
        var serviceDataHex: String? = null
        record?.serviceUuids?.forEach { parcelUuid ->
            val data = record.getServiceData(parcelUuid) ?: return@forEach
            if (data.isEmpty()) return@forEach
            val short = shortUuid(parcelUuid.uuid)
            if (serviceDataHex == null || data.size > (serviceDataHex!!.length / 2)) {
                serviceUuid = short
                serviceDataHex = AdvertisementMatcher.bytesToHex(data)
            }
        }

        val fullScanHex = record?.bytes?.takeIf { it.isNotEmpty() }?.let { AdvertisementMatcher.bytesToHex(it) }

        return CapturedAdvertisement(
            name = name,
            address = address,
            rssi = rssi,
            manufacturerId = manufacturerId,
            manufacturerHex = manufacturerHex,
            serviceUuid = serviceUuid,
            serviceDataHex = serviceDataHex,
            fullScanHex = fullScanHex,
        )
    }

    private fun shortUuid(uuid: java.util.UUID): String {
        val s = uuid.toString().uppercase()
        return if (s.endsWith("-0000-1000-8000-00805F9B34FB")) {
            s.substring(4, 8)
        } else {
            s
        }
    }
}

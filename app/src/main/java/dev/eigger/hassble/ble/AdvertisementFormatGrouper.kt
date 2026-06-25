package dev.eigger.hassble.ble

/**
 * 동일 광고 포맷(제조사/서비스 payload 패턴)끼리 묶어
 * MAC·포맷을 모를 때 대표 샘플을 고르기 쉽게 한다.
 */
object AdvertisementFormatGrouper {

    data class FormatGroup(
        val signature: String,
        val label: String,
        /** decode/match 템플릿용 — 그룹 내 최고 RSSI 샘플 */
        val representative: AdvertisementCapture.CapturedAdvertisement,
        val devices: List<AdvertisementCapture.CapturedAdvertisement>,
    ) {
        val deviceCount: Int get() = devices.size
        val bestRssi: Int get() = representative.rssi
    }

    fun formatSignature(c: AdvertisementCapture.CapturedAdvertisement): String {
        val mfrId = c.manufacturerId?.let { "%04X".format(it) } ?: "-"
        val svc = c.serviceUuid?.uppercase() ?: "-"
        val mfrPrefix = c.manufacturerHex?.take(20)?.uppercase() ?: "-"
        val svcPrefix = c.serviceDataHex?.take(16)?.uppercase() ?: "-"
        return "$mfrId|$svc|$mfrPrefix|$svcPrefix"
    }

    fun formatLabel(c: AdvertisementCapture.CapturedAdvertisement): String {
        val parts = mutableListOf<String>()
        c.manufacturerId?.let { parts.add("mfr 0x%04X".format(it)) }
        c.serviceUuid?.let { parts.add("svc $it") }
        if (parts.isEmpty()) parts.add("raw")
        return parts.joinToString(" · ")
    }

    fun groupByFormat(captures: List<AdvertisementCapture.CapturedAdvertisement>): List<FormatGroup> =
        captures
            .groupBy { formatSignature(it) }
            .map { (sig, list) ->
                val sorted = list.sortedByDescending { it.rssi }
                val rep = sorted.first()
                FormatGroup(
                    signature = sig,
                    label = formatLabel(rep),
                    representative = rep,
                    devices = sorted,
                )
            }
            .sortedByDescending { it.bestRssi }

    fun filterByQuery(
        query: String,
        captures: List<AdvertisementCapture.CapturedAdvertisement>,
    ): List<AdvertisementCapture.CapturedAdvertisement> {
        val q = query.trim()
        if (q.isBlank()) return captures
        return captures.filter { c ->
            c.name.contains(q, ignoreCase = true) ||
                c.address.contains(q, ignoreCase = true) ||
                formatLabel(c).contains(q, ignoreCase = true) ||
                (c.manufacturerHex?.contains(q, ignoreCase = true) == true) ||
                (c.serviceUuid?.contains(q, ignoreCase = true) == true)
        }
    }

    fun filterGroupsByQuery(groups: List<FormatGroup>, query: String): List<FormatGroup> {
        val q = query.trim()
        if (q.isBlank()) return groups
        return groups.filter { g ->
            g.label.contains(q, ignoreCase = true) ||
                g.devices.any { filterByQuery(q, listOf(it)).isNotEmpty() }
        }
    }

    /** 신호가 강한 후보만 (가까운 기기 추정) */
    fun filterNearby(
        captures: List<AdvertisementCapture.CapturedAdvertisement>,
        marginDb: Int = 12,
        maxCount: Int = 12,
    ): List<AdvertisementCapture.CapturedAdvertisement> {
        if (captures.isEmpty()) return emptyList()
        val sorted = captures.sortedByDescending { it.rssi }
        val peak = sorted.first().rssi
        return sorted.filter { it.rssi >= peak - marginDb }.take(maxCount)
    }

    fun filterNearbyGroups(groups: List<FormatGroup>, marginDb: Int = 12): List<FormatGroup> {
        if (groups.isEmpty()) return emptyList()
        val peak = groups.maxOf { it.bestRssi }
        return groups.filter { it.bestRssi >= peak - marginDb }
    }
}

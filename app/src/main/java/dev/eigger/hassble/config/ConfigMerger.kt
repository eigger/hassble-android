package dev.eigger.hassble.config

object ConfigMerger {

    fun merge(base: GatewayConfig, extraDevices: List<DeviceConfig>): GatewayConfig {
        if (extraDevices.isEmpty()) return base
        val existingIds = base.devices.map { it.id }.toMutableSet()
        val merged = base.devices.toMutableList()
        for (device in extraDevices) {
            val unique = ensureUniqueId(device, existingIds)
            existingIds += unique.id
            merged += unique
        }
        return base.copy(devices = merged)
    }

    fun ensureUniqueId(device: DeviceConfig, existingIds: Set<String>): DeviceConfig {
        if (device.id !in existingIds) return device
        var suffix = 2
        var candidate = "${device.id}_$suffix"
        while (candidate in existingIds) {
            suffix++
            candidate = "${device.id}_$suffix"
        }
        return device.copy(id = candidate, name = "${device.name} ($suffix)")
    }

    fun effectiveConfig(remote: GatewayConfig?, draftDevices: List<DeviceConfig>): GatewayConfig? {
        if (remote == null && draftDevices.isEmpty()) return null
        val base = remote ?: GatewayConfig()
        return merge(base, draftDevices.filter { d -> base.devices.none { it.id == d.id } })
    }
}

package dev.eigger.hassble.ble

import dev.eigger.hassble.config.AdvertisementInstanceMode
import dev.eigger.hassble.config.DeviceConfig
import dev.eigger.hassble.config.Source
import dev.eigger.hassble.net.HaRemoveMode

/** ws_bridge/remove mode: MAC 인스턴스 advertisement 프로필은 prefix 삭제. */
fun haRemoveModeForDevice(d: DeviceConfig?): HaRemoveMode = when {
    d != null &&
        d.source == Source.advertisement &&
        d.instanceMode == AdvertisementInstanceMode.mac &&
        d.match?.mac.isNullOrBlank() ->
        HaRemoveMode.PREFIX
    else -> HaRemoveMode.EXACT
}

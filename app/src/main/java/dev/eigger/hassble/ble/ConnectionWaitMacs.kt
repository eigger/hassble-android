package dev.eigger.hassble.ble

import dev.eigger.hassble.config.GatewayConfig
import dev.eigger.hassble.config.Source

/**
 * 자동 재연결하는 OBD/GATT 기기의 MAC. 메인 스캔 필터에 합쳐 재연결 대기에 별도 스캔을 쓰지 않는다.
 * 연결 중에도 필터에 남겨 둔다 — 필터를 바꾸려면 스캔 재시작이 필요해, 끊길 때마다 재시작하느니
 * 그 기기의 광고를 계속 받는 편(무시됨)이 싸다.
 */
object ConnectionWaitMacs {
    /** 앱에서 바인딩한 MAC([bound])이 설정의 MAC보다 우선한다. 대문자 `AA:BB:..` 꼴로 정규화. */
    fun of(
        config: GatewayConfig,
        bound: Map<String, String>,
        autoConnectDisabled: Set<String>,
    ): Set<String> = config.devices.asSequence()
        .filter { (it.source == Source.obd || it.source == Source.gatt_notify) && it.id !in autoConnectDisabled }
        .mapNotNull { d ->
            bound[d.id]?.takeIf { it.isNotBlank() }
                ?: (if (d.source == Source.obd) d.obd?.mac else d.gatt?.mac)?.takeIf { it.isNotBlank() }
        }
        .map { it.uppercase().replace("-", ":") }
        .toSet()
}

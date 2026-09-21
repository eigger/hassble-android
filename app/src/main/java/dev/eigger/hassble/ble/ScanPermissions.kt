package dev.eigger.hassble.ble

/**
 * BLE 광고 스캔에 필요한 런타임 권한. API 분기를 순수 함수로 빼서 단위 테스트가 가능하게 한다.
 *
 * - API 31+: BLUETOOTH_SCAN. 이 앱은 neverForLocation을 쓰지 않으므로(표준 비콘 포맷 수신 위해)
 *   ACCESS_FINE_LOCATION도 있어야 결과가 온다. 없으면 startScan은 성공하지만 결과 0건이라
 *   idle watchdog만 헛돈다.
 * - API 26~30: BLUETOOTH_SCAN이라는 권한 자체가 없어 checkSelfPermission이 항상 DENIED다.
 *   ACCESS_FINE_LOCATION만 본다.
 */
object ScanPermissions {
    const val BLUETOOTH_SCAN = "android.permission.BLUETOOTH_SCAN"
    const val ACCESS_FINE_LOCATION = "android.permission.ACCESS_FINE_LOCATION"

    fun required(sdkInt: Int): List<String> =
        if (sdkInt >= 31) listOf(BLUETOOTH_SCAN, ACCESS_FINE_LOCATION) else listOf(ACCESS_FINE_LOCATION)

    /** [isGranted]가 false를 주는 필수 권한 목록. 비어 있으면 스캔 가능. */
    fun missing(sdkInt: Int, isGranted: (String) -> Boolean): List<String> =
        required(sdkInt).filterNot(isGranted)
}

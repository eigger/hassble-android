package dev.eigger.hassble.ble

import android.app.KeyguardManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock

/**
 * 화면 꺼짐·Doze·배터리 최적화·앱 대기 버킷을 한 줄로 요약한다. 백그라운드에서 광고 수신이
 * 끊길 때 "OS가 앱을 재운 것인가, 필터 문제인가"를 로그만으로 가르기 위한 진단용이다.
 */
object PowerDiagnostics {
    fun describe(context: Context): String = runCatching {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        buildString {
            append("screenOn=${pm.isInteractive}")
            append(", keyguardLocked=${(context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager)?.isKeyguardLocked}")
            append(", appVisible=${AppVisibility.isVisible}")
            append(", bgLocation=${hasBackgroundLocation(context)}")
            append(", doze=${pm.isDeviceIdleMode}")
            append(", powerSave=${pm.isPowerSaveMode}")
            append(", ignoringBatteryOpt=${pm.isIgnoringBatteryOptimizations(context.packageName)}")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
                usm?.let { append(", standbyBucket=${it.appStandbyBucket}") }
            }
            append(", sdk=${Build.VERSION.SDK_INT}, ${Build.MANUFACTURER} ${Build.MODEL}")
        }
    }.getOrElse { "power state unavailable: ${it.message}" }

    /** 백그라운드 위치 권한("항상 허용")이 있는가. API 28 이하엔 이 권한이 없고 위치가 항상 유효하다. */
    fun hasBackgroundLocation(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            context.checkSelfPermission(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    /**
     * 지금 BLE 스캔 세션을 새로 시작해도 결과를 받을 수 있는가. 이 앱은 neverForLocation을 쓰지 않아 스캔에
     * 위치 appop이 필요한데, "앱 사용 중에만 허용"이면 앱이 화면에 보이지 않을 때(화면 꺼짐·잠금화면·홈 화면
     * 포함) 새로 시작한 세션은 거부돼 시작 직후 몇 건만 받고 죽는다. 화면에 보일 때 시작한 세션은 이후 앱이
     * 숨겨져도 살아 있다(adb 실측). "항상 허용"이면 상태와 무관하게 시작할 수 있다.
     */
    private fun readCanStartScan(context: Context): Boolean =
        hasBackgroundLocation(context) || AppVisibility.isVisible

    @Volatile private var checkedMs = 0L
    @Volatile private var canStart = true

    /** 캐시를 거치지 않고 지금 상태를 읽는다(세션 시작처럼 한 번만 정확해야 할 때). 캐시도 갱신한다. */
    fun canStartScanFresh(context: Context): Boolean {
        canStart = readCanStartScan(context)
        checkedMs = SystemClock.elapsedRealtime()
        return canStart
    }

    /** 스캔 결과마다 불려도 호출이 1초에 한 번만 나가도록 캐시한다. */
    fun canStartScan(context: Context): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (now - checkedMs >= 1_000L) {
            canStart = readCanStartScan(context)
            checkedMs = now
        }
        return canStart
    }
}

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

    /**
     * 화면이 켜져 있고 잠금화면(키가드)도 없는가. 삼성 단말에서 스캔은 "화면 켜짐"이 아니라 이 상태를 기준으로
     * 동작한다 — 화면이 켜졌어도 잠금화면이 떠 있는 동안 새로 시작한 세션은 시작 직후 몇 건만 받고 죽고,
     * 잠금이 풀린 상태에서 시작한 세션은 이후 화면이 꺼지거나 잠겨도 살아 있다(adb 실측).
     */
    private fun readUserActive(context: Context): Boolean = runCatching {
        val interactive = (context.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive
        val locked = (context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager)?.isKeyguardLocked ?: false
        interactive && !locked
    }.getOrDefault(true)

    @Volatile private var checkedMs = 0L
    @Volatile private var userActive = true

    /** 캐시를 거치지 않고 지금 상태를 읽는다(세션 시작처럼 한 번만 정확해야 할 때). 캐시도 갱신한다. */
    fun isUserActiveFresh(context: Context): Boolean {
        userActive = readUserActive(context)
        checkedMs = SystemClock.elapsedRealtime()
        return userActive
    }

    /** 스캔 결과마다 불려도 binder 호출이 1초에 한 번만 나가도록 캐시한다. */
    fun isUserActive(context: Context): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (now - checkedMs >= 1_000L) {
            userActive = readUserActive(context)
            checkedMs = now
        }
        return userActive
    }
}

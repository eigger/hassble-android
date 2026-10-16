package dev.eigger.hassble.ble

import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.PowerManager

/**
 * 화면 꺼짐·Doze·배터리 최적화·앱 대기 버킷을 한 줄로 요약한다. 백그라운드에서 광고 수신이
 * 끊길 때 "OS가 앱을 재운 것인가, 필터 문제인가"를 로그만으로 가르기 위한 진단용이다.
 */
object PowerDiagnostics {
    fun describe(context: Context): String = runCatching {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        buildString {
            append("screenOn=${pm.isInteractive}")
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

    /** 캐시를 거치지 않고 지금 화면 상태를 읽는다(세션 시작처럼 한 번만 정확해야 할 때). 캐시도 갱신한다. */
    fun isScreenOnFresh(context: Context): Boolean {
        screenOn = runCatching {
            (context.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive
        }.getOrDefault(true)
        screenCheckedMs = System.currentTimeMillis()
        return screenOn
    }

    @Volatile private var screenCheckedMs = 0L
    @Volatile private var screenOn = true

    /** 스캔 결과마다 불려도 binder 호출이 1초에 한 번만 나가도록 캐시한다. */
    fun isScreenOn(context: Context): Boolean {
        val now = System.currentTimeMillis()
        if (now - screenCheckedMs >= 1_000L) {
            screenOn = runCatching {
                (context.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive
            }.getOrDefault(true)
            screenCheckedMs = now
        }
        return screenOn
    }
}

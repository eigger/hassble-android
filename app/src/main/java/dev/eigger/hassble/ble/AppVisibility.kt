package dev.eigger.hassble.ble

import android.app.Activity
import android.app.Application
import android.os.Bundle

/**
 * 앱 화면(Activity)이 하나라도 보이는지. 백그라운드 위치 권한이 없으면 BLE 스캔은 "앱이 화면에 보일 때
 * 시작한" 세션만 결과를 받는다(위치 appop이 foreground 모드라 숨은 상태에서 시작한 세션은 거부돼 죽는다).
 * 스캐너 watchdog이 "지금 스캔을 새로 시작해도 되는가"를 판단하는 데 쓴다.
 */
object AppVisibility {
    @Volatile var isVisible: Boolean = false
        private set
    private var startedActivities = 0

    fun register(app: Application) {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                startedActivities++
                isVisible = true
            }

            override fun onActivityStopped(activity: Activity) {
                startedActivities = (startedActivities - 1).coerceAtLeast(0)
                isVisible = startedActivities > 0
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }
}

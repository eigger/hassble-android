package dev.eigger.hassble.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import dev.eigger.hassble.service.LiveEventLogger
import dev.eigger.hassble.service.LogType

/**
 * Android는 광고에 임의 Complete Local Name을 넣는 API가 없고,
 * Bluetooth 이름을 공장값으로 되돌리는 API도 없다.
 *
 * 그래서 이름을 바꾸기 **전에** 당시 값을 SharedPreferences에 초기값으로 남긴다(commit).
 * 광고가 끝나거나 프로세스가 다시 뜨면 그 초기값으로 setName 한다.
 * 세션 메모리 백업이 아니라, 디스크 초기값 + 시작 시 초기화다.
 */
object BluetoothAdapterNameGuard {
    private const val PREFS = "hassble_bt_adapter_name"
    private const val KEY_INITIAL = "initial_name"
    private const val KEY_OVERRIDDEN = "overridden"

    private val lock = Any()

    /** 이 프로세스에서 지금 광고용 이름으로 바꿔 둔 상태. 강제종료 후에는 false. */
    @Volatile
    private var overrideActiveInProcess = false

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun adapter(context: Context) =
        context.applicationContext.getSystemService(BluetoothManager::class.java)?.adapter

    private fun hasConnectPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                context.applicationContext,
                Manifest.permission.BLUETOOTH_CONNECT,
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(
                context.applicationContext,
                Manifest.permission.BLUETOOTH_ADMIN,
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * 초기값을 디스크에 남긴 뒤 [desired]로 바꾼다.
     * commit이 성공한 다음에만 setName 해서, 그 사이 강제종료돼도 다음 기동에서 초기화할 수 있다.
     */
    @SuppressLint("MissingPermission")
    fun applyOverride(context: Context, desired: String): Boolean {
        val app = context.applicationContext
        val bt = adapter(app) ?: return false
        if (!bt.isEnabled || !hasConnectPermission(app)) return false

        synchronized(lock) {
            val store = prefs(app)
            if (!store.getBoolean(KEY_OVERRIDDEN, false)) {
                val initial = bt.name.orEmpty()
                val saved = store.edit()
                    .putString(KEY_INITIAL, initial)
                    .putBoolean(KEY_OVERRIDDEN, true)
                    .commit()
                if (!saved) return false
            }
            if (bt.name != desired && !bt.setName(desired)) {
                return false
            }
            overrideActiveInProcess = true
            return true
        }
    }

    /**
     * 저장해 둔 초기값으로 되돌린다.
     * [force]가 아니면, 이 프로세스에서 광고 중인 동안에는 건드리지 않는다.
     * 강제종료 후 재기동은 in-process 플래그가 꺼져 있으므로 prefs의 overridden만 보고 초기화한다.
     */
    @SuppressLint("MissingPermission")
    fun resetToInitial(context: Context, force: Boolean = false): Boolean {
        if (overrideActiveInProcess && !force) return true

        val app = context.applicationContext
        synchronized(lock) {
            val store = prefs(app)
            if (!store.getBoolean(KEY_OVERRIDDEN, false)) return true

            val initial = store.getString(KEY_INITIAL, null) ?: run {
                store.edit().putBoolean(KEY_OVERRIDDEN, false).commit()
                overrideActiveInProcess = false
                return true
            }

            val bt = adapter(app)
            if (bt == null || !bt.isEnabled || !hasConnectPermission(app)) {
                return false
            }

            val ok = initial.isEmpty() || bt.name == initial || bt.setName(initial)
            if (!ok) return false

            store.edit()
                .putBoolean(KEY_OVERRIDDEN, false)
                .remove(KEY_INITIAL)
                .commit()
            overrideActiveInProcess = false
            LiveEventLogger.log(
                LogType.TX,
                "BLE adapter name reset to initial '$initial'",
            )
            return true
        }
    }

    fun onBluetoothStateChanged(context: Context, state: Int) {
        if (state == BluetoothAdapter.STATE_ON) {
            resetToInitial(context)
        }
    }
}

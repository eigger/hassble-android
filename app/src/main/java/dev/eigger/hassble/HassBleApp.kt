package dev.eigger.hassble

import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import dev.eigger.hassble.ble.BluetoothAdapterNameGuard
import dev.eigger.hassble.service.LiveEventLogger

class HassBleApp : Application() {
    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
            BluetoothAdapterNameGuard.onBluetoothStateChanged(context, state)
        }
    }

    override fun onCreate() {
        super.onCreate()
        LiveEventLogger.init(this)
        BluetoothAdapterNameGuard.resetToInitial(this)
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        ContextCompat.registerReceiver(
            this,
            bluetoothStateReceiver,
            filter,
            ContextCompat.RECEIVER_EXPORTED,
        )
    }
    // TODO: DI 컨테이너 / 싱글톤(ConfigRepository, MqttTransport, ObdPresetStore) 초기화
}

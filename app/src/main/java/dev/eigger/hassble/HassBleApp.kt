package dev.eigger.hassble

import android.app.Application
import dev.eigger.hassble.service.LiveEventLogger

class HassBleApp : Application() {
    override fun onCreate() {
        super.onCreate()
        LiveEventLogger.init(this)
    }
    // TODO: DI 컨테이너 / 싱글톤(ConfigRepository, MqttTransport, ObdPresetStore) 초기화
}

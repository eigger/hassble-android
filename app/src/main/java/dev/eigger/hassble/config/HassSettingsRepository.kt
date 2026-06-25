package dev.eigger.hassble.config

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "hassble_settings")

class HassSettingsRepository(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        private val KEY_HA_URL = stringPreferencesKey("ha_url")
        private val KEY_HA_TOKEN = stringPreferencesKey("ha_token")
        private val KEY_HA_REFRESH_TOKEN = stringPreferencesKey("ha_refresh_token")
        private val KEY_HA_AUTH_STATE = stringPreferencesKey("ha_auth_state")
        private val KEY_HA_TOKEN_LAST_REFRESHED = longPreferencesKey("ha_token_last_refreshed")
        private val KEY_GIT_URL = stringPreferencesKey("git_url")
        private val KEY_GIT_TOKEN = stringPreferencesKey("git_token")
        private val KEY_BOUND_DEVICES = stringPreferencesKey("bound_devices")
        private val KEY_START_ON_BOOT = booleanPreferencesKey("start_on_boot")
        private val KEY_ENABLED_SENSORS = stringPreferencesKey("enabled_sensors")
        private val KEY_ENABLED_SENSORS_INITIALIZED = booleanPreferencesKey("enabled_sensors_initialized")
        private val KEY_ALL_KNOWN_SENSORS = stringPreferencesKey("all_known_sensors")
        private val KEY_ONBOARDING_COMPLETE = booleanPreferencesKey("onboarding_complete")
        private val KEY_SCAN_MODE = stringPreferencesKey("ble_scan_mode")
        private val KEY_DISABLED_DEVICES = stringPreferencesKey("disabled_devices")
        private val KEY_KNOWN_DEVICE_IDS = stringPreferencesKey("known_device_ids")
        private val KEY_AUTO_CONNECT_DISABLED = stringPreferencesKey("auto_connect_disabled")
        private val KEY_ENTITY_FINGERPRINTS = stringPreferencesKey("entity_fingerprints")
        private val KEY_DRAFT_DEVICES = stringPreferencesKey("draft_devices")
        private val KEY_LOG_BUFFER_LIMIT = intPreferencesKey("log_buffer_limit")
    }

    val haUrl: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_HA_URL] ?: "https://"
    }

    val startOnBoot: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_START_ON_BOOT] ?: true
    }

    val haToken: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_HA_TOKEN] ?: ""
    }

    val haRefreshToken: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_HA_REFRESH_TOKEN] ?: ""
    }

    val haAuthState: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_HA_AUTH_STATE] ?: ""
    }

    val haTokenLastRefreshed: Flow<Long> = context.dataStore.data.map { prefs ->
        prefs[KEY_HA_TOKEN_LAST_REFRESHED] ?: 0L
    }

    val gitUrl: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_GIT_URL].orEmpty().ifBlank { HassBleDefaults.GIT_CONFIG_URL }
    }

    val gitToken: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[KEY_GIT_TOKEN]
    }

    val boundDevices: Flow<Map<String, String>> = context.dataStore.data.map { prefs ->
        val jsonStr = prefs[KEY_BOUND_DEVICES] ?: return@map emptyMap()
        runCatching {
            json.decodeFromString(MapSerializer(String.serializer(), String.serializer()), jsonStr)
        }.getOrDefault(emptyMap())
    }

    val enabledSensors: Flow<Set<String>> = context.dataStore.data.map { prefs ->
        val jsonStr = prefs[KEY_ENABLED_SENSORS] ?: return@map emptySet()
        runCatching {
            json.decodeFromString(ListSerializer(String.serializer()), jsonStr).toSet()
        }.getOrDefault(emptySet())
    }

    val enabledSensorsInitialized: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_ENABLED_SENSORS_INITIALIZED] ?: false
    }

    val onboardingComplete: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_ONBOARDING_COMPLETE] ?: false
    }

    val scanMode: Flow<BleScanModeOption> = context.dataStore.data.map { prefs ->
        runCatching { BleScanModeOption.valueOf(prefs[KEY_SCAN_MODE] ?: "") }
            .getOrDefault(BleScanModeOption.BALANCED)
    }

    val logBufferLimit: Flow<Int> = context.dataStore.data.map { prefs ->
        normalizeLogBufferLimit(prefs[KEY_LOG_BUFFER_LIMIT] ?: HassBleDefaults.DEFAULT_LOG_BUFFER_LIMIT)
    }

    suspend fun saveLogBufferLimit(limit: Int) {
        context.dataStore.edit { prefs ->
            prefs[KEY_LOG_BUFFER_LIMIT] = normalizeLogBufferLimit(limit)
        }
    }

    private fun normalizeLogBufferLimit(limit: Int): Int =
        limit.takeIf { it in HassBleDefaults.LOG_BUFFER_LIMIT_OPTIONS } ?: HassBleDefaults.DEFAULT_LOG_BUFFER_LIMIT

    val disabledDevices: Flow<Set<String>> = context.dataStore.data.map { prefs ->
        runCatching {
            json.decodeFromString(ListSerializer(String.serializer()), prefs[KEY_DISABLED_DEVICES] ?: "[]").toSet()
        }.getOrDefault(emptySet())
    }

    suspend fun setDeviceDisabled(deviceId: String, disabled: Boolean) {
        context.dataStore.edit { prefs ->
            val current = runCatching {
                json.decodeFromString(ListSerializer(String.serializer()), prefs[KEY_DISABLED_DEVICES] ?: "[]").toMutableSet()
            }.getOrDefault(mutableSetOf())
            if (disabled) current.add(deviceId) else current.remove(deviceId)
            prefs[KEY_DISABLED_DEVICES] = json.encodeToString(ListSerializer(String.serializer()), current.sorted())
        }
    }

    suspend fun clearDisabledDevices() {
        context.dataStore.edit { prefs -> prefs.remove(KEY_DISABLED_DEVICES) }
    }

    val autoConnectDisabled: Flow<Set<String>> = context.dataStore.data.map { prefs ->
        runCatching {
            json.decodeFromString(ListSerializer(String.serializer()), prefs[KEY_AUTO_CONNECT_DISABLED] ?: "[]").toSet()
        }.getOrDefault(emptySet())
    }

    suspend fun setAutoConnectDisabled(deviceId: String, disabled: Boolean) {
        context.dataStore.edit { prefs ->
            val current = runCatching {
                json.decodeFromString(ListSerializer(String.serializer()), prefs[KEY_AUTO_CONNECT_DISABLED] ?: "[]").toMutableSet()
            }.getOrDefault(mutableSetOf())
            if (disabled) current.add(deviceId) else current.remove(deviceId)
            prefs[KEY_AUTO_CONNECT_DISABLED] = json.encodeToString(ListSerializer(String.serializer()), current.sorted())
        }
    }

    suspend fun initAutoConnectFromConfig(devices: List<DeviceConfig>) {
        context.dataStore.edit { prefs ->
            val current = runCatching {
                json.decodeFromString(ListSerializer(String.serializer()), prefs[KEY_AUTO_CONNECT_DISABLED] ?: "[]").toMutableSet()
            }.getOrDefault(mutableSetOf())
            // config에서 auto_connect: false인 기기는 강제로 disabled에 추가
            // config에서 auto_connect: true(기본)인 기기는 기존 사용자 설정 유지
            devices.filter { it.source == Source.gatt_notify || it.source == Source.obd }
                .forEach { d ->
                    val autoConnect = when (d.source) {
                        Source.gatt_notify -> d.gatt?.autoConnect != false
                        Source.obd -> d.obd?.autoConnect != false
                        else -> true
                    }
                    if (!autoConnect) current.add(d.id) else current.remove(d.id)
                }
            prefs[KEY_AUTO_CONNECT_DISABLED] = json.encodeToString(ListSerializer(String.serializer()), current.sorted())
        }
    }

    suspend fun getRemovedDeviceIds(newConfigIds: Set<String>): Set<String> {
        val prefs = context.dataStore.data.first()
        val known = runCatching {
            json.decodeFromString(ListSerializer(String.serializer()), prefs[KEY_KNOWN_DEVICE_IDS] ?: "[]").toSet()
        }.getOrDefault(emptySet())
        return known - newConfigIds
    }

    suspend fun updateKnownDeviceIds(ids: Set<String>) {
        context.dataStore.edit { prefs ->
            prefs[KEY_KNOWN_DEVICE_IDS] = json.encodeToString(ListSerializer(String.serializer()), ids.sorted())
        }
    }

    suspend fun saveScanMode(mode: BleScanModeOption) {
        context.dataStore.edit { prefs ->
            prefs[KEY_SCAN_MODE] = mode.name
        }
    }

    suspend fun setOnboardingComplete(complete: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_ONBOARDING_COMPLETE] = complete
        }
    }

    suspend fun saveHaSettings(url: String, token: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_HA_URL] = url
            prefs[KEY_HA_TOKEN] = token
        }
    }

    suspend fun saveHaRefreshToken(token: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_HA_REFRESH_TOKEN] = token
        }
    }

    suspend fun clearHaRefreshToken() {
        context.dataStore.edit { prefs ->
            prefs.remove(KEY_HA_REFRESH_TOKEN)
        }
    }

    suspend fun saveHaAuthState(state: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_HA_AUTH_STATE] = state
        }
    }

    suspend fun clearHaAuthState() {
        context.dataStore.edit { prefs ->
            prefs.remove(KEY_HA_AUTH_STATE)
        }
    }

    suspend fun saveHaTokenLastRefreshed(time: Long) {
        context.dataStore.edit { prefs ->
            prefs[KEY_HA_TOKEN_LAST_REFRESHED] = time
        }
    }

    suspend fun saveStartOnBoot(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_START_ON_BOOT] = enabled
        }
    }

    suspend fun saveGitSettings(url: String, token: String?) {
        context.dataStore.edit { prefs ->
            prefs[KEY_GIT_URL] = url
            if (token != null) {
                prefs[KEY_GIT_TOKEN] = token
            } else {
                prefs.remove(KEY_GIT_TOKEN)
            }
        }
    }

    suspend fun syncEnabledSensors(config: GatewayConfig) {
        val issues = ConfigValidator.validate(config)
        val errorKeys = issues
            .filter { it.level == ValidationLevel.ERROR && it.sensorKey != null }
            .map { "${it.deviceId}/${it.sensorKey}" }
            .toSet()
        val allKeys = config.allSensorKeys() - errorKeys
        if (allKeys.isEmpty() && errorKeys.isEmpty()) return
        context.dataStore.edit { prefs ->
            val initialized = prefs[KEY_ENABLED_SENSORS_INITIALIZED] ?: false
            val current = runCatching {
                val jsonStr = prefs[KEY_ENABLED_SENSORS] ?: "[]"
                json.decodeFromString(ListSerializer(String.serializer()), jsonStr).toSet()
            }.getOrDefault(emptySet())
            val allKnown = runCatching {
                val jsonStr = prefs[KEY_ALL_KNOWN_SENSORS] ?: "[]"
                json.decodeFromString(ListSerializer(String.serializer()), jsonStr).toSet()
            }.getOrDefault(emptySet())

            when {
                !initialized || current.isEmpty() -> {
                    prefs[KEY_ENABLED_SENSORS] = json.encodeToString(
                        ListSerializer(String.serializer()),
                        allKeys.sorted(),
                    )
                    prefs[KEY_ALL_KNOWN_SENSORS] = json.encodeToString(
                        ListSerializer(String.serializer()),
                        config.allSensorKeys().sorted(),
                    )
                    prefs[KEY_ENABLED_SENSORS_INITIALIZED] = true
                }
                else -> {
                    val newKeys = allKeys - allKnown
                    val pruned = (current.intersect(allKeys) + newKeys) - errorKeys
                    prefs[KEY_ENABLED_SENSORS] = json.encodeToString(
                        ListSerializer(String.serializer()),
                        pruned.sorted(),
                    )
                    prefs[KEY_ALL_KNOWN_SENSORS] = json.encodeToString(
                        ListSerializer(String.serializer()),
                        config.allSensorKeys().sorted(),
                    )
                }
            }
        }
    }

    suspend fun loadDraftDevices(): List<DeviceConfig> {
        val prefs = context.dataStore.data.first()
        val jsonStr = prefs[KEY_DRAFT_DEVICES] ?: return emptyList()
        return runCatching {
            json.decodeFromString(ListSerializer(DeviceConfig.serializer()), jsonStr)
        }.getOrDefault(emptyList())
    }

    suspend fun saveDraftDevices(devices: List<DeviceConfig>) {
        context.dataStore.edit { prefs ->
            if (devices.isEmpty()) {
                prefs.remove(KEY_DRAFT_DEVICES)
            } else {
                prefs[KEY_DRAFT_DEVICES] = json.encodeToString(
                    ListSerializer(DeviceConfig.serializer()),
                    devices,
                )
            }
        }
    }

    suspend fun addDraftDevice(device: DeviceConfig) {
        val current = loadDraftDevices().toMutableList()
        current.removeAll { it.id == device.id }
        current += device
        saveDraftDevices(current)
    }

    suspend fun removeDraftDevice(deviceId: String) {
        val remaining = loadDraftDevices().filter { it.id != deviceId }
        val stringList = ListSerializer(String.serializer())
        val stringMap = MapSerializer(String.serializer(), String.serializer())
        context.dataStore.edit { prefs ->
            // draft 본체 제거
            if (remaining.isEmpty()) {
                prefs.remove(KEY_DRAFT_DEVICES)
            } else {
                prefs[KEY_DRAFT_DEVICES] = json.encodeToString(
                    ListSerializer(DeviceConfig.serializer()),
                    remaining,
                )
            }

            // 바인딩(MAC) 제거
            prefs[KEY_BOUND_DEVICES]?.let { raw ->
                val map = runCatching { json.decodeFromString(stringMap, raw) }
                    .getOrDefault(emptyMap()).toMutableMap()
                if (map.remove(deviceId) != null) {
                    prefs[KEY_BOUND_DEVICES] = json.encodeToString(stringMap, map)
                }
            }

            // 센서 enable 설정 제거 (deviceId/ 접두사)
            val sensorPrefix = "$deviceId/"
            prefs[KEY_ENABLED_SENSORS]?.let { raw ->
                val set = runCatching { json.decodeFromString(stringList, raw).toMutableList() }
                    .getOrDefault(mutableListOf())
                if (set.removeAll { it.startsWith(sensorPrefix) }) {
                    prefs[KEY_ENABLED_SENSORS] = json.encodeToString(stringList, set.sorted())
                }
            }
            prefs[KEY_ALL_KNOWN_SENSORS]?.let { raw ->
                val set = runCatching { json.decodeFromString(stringList, raw).toMutableList() }
                    .getOrDefault(mutableListOf())
                if (set.removeAll { it.startsWith(sensorPrefix) }) {
                    prefs[KEY_ALL_KNOWN_SENSORS] = json.encodeToString(stringList, set.sorted())
                }
            }

            // disabled / auto-connect 목록에서 제거
            for (key in listOf(KEY_DISABLED_DEVICES, KEY_AUTO_CONNECT_DISABLED)) {
                prefs[key]?.let { raw ->
                    val set = runCatching { json.decodeFromString(stringList, raw).toMutableList() }
                        .getOrDefault(mutableListOf())
                    if (set.remove(deviceId)) {
                        prefs[key] = json.encodeToString(stringList, set.sorted())
                    }
                }
            }

            // known id / fingerprint 제거
            prefs[KEY_KNOWN_DEVICE_IDS]?.let { raw ->
                val set = runCatching { json.decodeFromString(stringList, raw).toMutableList() }
                    .getOrDefault(mutableListOf())
                if (set.remove(deviceId)) {
                    prefs[KEY_KNOWN_DEVICE_IDS] = json.encodeToString(stringList, set.sorted())
                }
            }
            prefs[KEY_ENTITY_FINGERPRINTS]?.let { raw ->
                val map = runCatching { json.decodeFromString(stringMap, raw) }
                    .getOrDefault(emptyMap()).toMutableMap()
                if (map.remove(deviceId) != null) {
                    prefs[KEY_ENTITY_FINGERPRINTS] = json.encodeToString(stringMap, map)
                }
            }
        }
    }

    suspend fun clearDraftDevices() {
        saveDraftDevices(emptyList())
    }

    suspend fun setSensorEnabled(sensorKey: String, enabled: Boolean) {
        context.dataStore.edit { prefs ->
            val currentSet = runCatching {
                val currentJson = prefs[KEY_ENABLED_SENSORS] ?: "[]"
                json.decodeFromString(ListSerializer(String.serializer()), currentJson).toMutableSet()
            }.getOrDefault(mutableSetOf())
            if (enabled) {
                currentSet += sensorKey
            } else {
                currentSet -= sensorKey
            }
            prefs[KEY_ENABLED_SENSORS] = json.encodeToString(
                ListSerializer(String.serializer()),
                currentSet.sorted(),
            )
            prefs[KEY_ENABLED_SENSORS_INITIALIZED] = true
        }
    }

    suspend fun setSensorsEnabled(sensorKeys: Collection<String>, enabled: Boolean) {
        if (sensorKeys.isEmpty()) return
        context.dataStore.edit { prefs ->
            val currentSet = runCatching {
                val currentJson = prefs[KEY_ENABLED_SENSORS] ?: "[]"
                json.decodeFromString(ListSerializer(String.serializer()), currentJson).toMutableSet()
            }.getOrDefault(mutableSetOf())
            if (enabled) {
                currentSet += sensorKeys
            } else {
                currentSet -= sensorKeys.toSet()
            }
            prefs[KEY_ENABLED_SENSORS] = json.encodeToString(
                ListSerializer(String.serializer()),
                currentSet.sorted(),
            )
            prefs[KEY_ENABLED_SENSORS_INITIALIZED] = true
        }
    }

    suspend fun bindDevice(deviceId: String, macAddress: String) {
        context.dataStore.edit { prefs ->
            val currentJson = prefs[KEY_BOUND_DEVICES] ?: "{}"
            val currentMap = runCatching {
                json.decodeFromString(MapSerializer(String.serializer(), String.serializer()), currentJson)
            }.getOrDefault(emptyMap()).toMutableMap()

            currentMap[deviceId] = macAddress
            val newJson = json.encodeToString(MapSerializer(String.serializer(), String.serializer()), currentMap)
            prefs[KEY_BOUND_DEVICES] = newJson
        }
    }

    suspend fun unbindDevice(deviceId: String) {
        context.dataStore.edit { prefs ->
            val currentJson = prefs[KEY_BOUND_DEVICES] ?: "{}"
            val currentMap = runCatching {
                json.decodeFromString(MapSerializer(String.serializer(), String.serializer()), currentJson)
            }.getOrDefault(emptyMap()).toMutableMap()

            currentMap.remove(deviceId)
            val newJson = json.encodeToString(MapSerializer(String.serializer(), String.serializer()), currentMap)
            prefs[KEY_BOUND_DEVICES] = newJson
        }
    }

    // ── HA 엔티티 선언 fingerprint ────────────────────────────────────────────
    // 저장된 fingerprint와 현재 config의 실제 선언 내용이 다르면 해당 device를 cleanup 대상으로 반환.
    suspend fun getChangedDeviceIds(config: GatewayConfig): Set<String> {
        val prefs = context.dataStore.data.first()
        val storedJson = prefs[KEY_ENTITY_FINGERPRINTS] ?: "{}"
        val storedMap = runCatching {
            json.decodeFromString(MapSerializer(String.serializer(), String.serializer()), storedJson)
        }.getOrDefault(emptyMap())

        // validate는 한 번만 실행 (O(n) → 루프 내에서 재호출 방지)
        val issues = ConfigValidator.validate(config)
        return config.devices
            .filter { d ->
                val current = ConfigValidator.computeEffectiveFingerprint(d, issues)
                storedMap[d.id] != current
            }
            .map { it.id }
            .toSet()
    }

    suspend fun saveEntityFingerprints(config: GatewayConfig) {
        // validate는 한 번만 실행
        val issues = ConfigValidator.validate(config)
        context.dataStore.edit { prefs ->
            val newMap = config.devices.associate { d ->
                d.id to ConfigValidator.computeEffectiveFingerprint(d, issues)
            }
            prefs[KEY_ENTITY_FINGERPRINTS] = json.encodeToString(
                MapSerializer(String.serializer(), String.serializer()), newMap
            )
        }
    }

    suspend fun clearEntityFingerprints() {
        context.dataStore.edit { prefs -> prefs.remove(KEY_ENTITY_FINGERPRINTS) }
    }
}

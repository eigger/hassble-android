package dev.eigger.hassble.config

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
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
        private val KEY_GIT_URL = stringPreferencesKey("git_url")
        private val KEY_GIT_TOKEN = stringPreferencesKey("git_token")
        private val KEY_BOUND_DEVICES = stringPreferencesKey("bound_devices")
        private val KEY_START_ON_BOOT = booleanPreferencesKey("start_on_boot")
        private val KEY_ENABLED_SENSORS = stringPreferencesKey("enabled_sensors")
        private val KEY_ENABLED_SENSORS_INITIALIZED = booleanPreferencesKey("enabled_sensors_initialized")
        private val KEY_ALL_KNOWN_SENSORS = stringPreferencesKey("all_known_sensors")
        private val KEY_ONBOARDING_COMPLETE = booleanPreferencesKey("onboarding_complete")
    }

    val haUrl: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_HA_URL] ?: "https://homeassistant.local:8123"
    }

    val startOnBoot: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_START_ON_BOOT] ?: true
    }

    val haToken: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_HA_TOKEN] ?: ""
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
        val allKeys = config.allSensorKeys()
        if (allKeys.isEmpty()) return
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
                        allKeys.sorted(),
                    )
                    prefs[KEY_ENABLED_SENSORS_INITIALIZED] = true
                }
                else -> {
                    val newKeys = allKeys - allKnown
                    val pruned = current.intersect(allKeys) + newKeys
                    prefs[KEY_ENABLED_SENSORS] = json.encodeToString(
                        ListSerializer(String.serializer()),
                        pruned.sorted(),
                    )
                    prefs[KEY_ALL_KNOWN_SENSORS] = json.encodeToString(
                        ListSerializer(String.serializer()),
                        allKeys.sorted(),
                    )
                }
            }
        }
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
}

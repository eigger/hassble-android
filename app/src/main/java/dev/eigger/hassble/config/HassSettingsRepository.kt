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
        prefs[KEY_GIT_URL] ?: ""
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

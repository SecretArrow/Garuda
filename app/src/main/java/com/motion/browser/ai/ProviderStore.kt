package com.motion.browser.ai

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.motion.browser.ai.core.ProviderConfig
import com.motion.browser.ai.core.RoleType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** One DataStore file for AI provider settings (process-wide singleton by name). */
private val Context.motionProviderDataStore: DataStore<Preferences> by preferencesDataStore(name = "motion_providers")

/** Persisted snapshot — contains NO API keys, only SecretStore key references (spec §10). */
@Serializable
private data class ProviderSnapshot(
    val configs: List<ProviderConfig> = emptyList(),
    val roles: Map<String, String> = emptyMap() // RoleType.name -> configId
)

/**
 * DataStore<Preferences> persistence for the AI provider layer (contract §3.3).
 *
 * Persists one JSON blob: the list of [ProviderConfig]s and the role→configId map.
 * API keys are NEVER persisted here (nor in Room, logs, or configs) — they live
 * only in com.motion.browser.security.SecretStore under "provider_key_<id>".
 *
 * In-memory MutableStateFlows are the source of truth; DataStore is written on
 * every mutation (suspend, Mutex-serialized) and read by [load] at startup.
 */
class ProviderStore(context: Context) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val dataStore = context.applicationContext.motionProviderDataStore
    private val mutex = Mutex()

    private val dataKey = stringPreferencesKey("providers_json_v1")

    private val _configs = MutableStateFlow<List<ProviderConfig>>(emptyList())

    /** Current provider configurations (never contains API keys). */
    val configs: StateFlow<List<ProviderConfig>> = _configs.asStateFlow()

    private val _roles = MutableStateFlow<Map<RoleType, String>>(emptyMap())

    /** Current role → configId mapping. */
    val roles: StateFlow<Map<RoleType, String>> = _roles.asStateFlow()

    private var loaded = false

    /** Reads persisted state into memory (idempotent; safe to call repeatedly). */
    suspend fun load() = mutex.withLock {
        if (!loaded) {
            loadLocked()
            loaded = true
        }
    }

    private suspend fun loadLocked() {
        val raw = withContext(Dispatchers.IO) { dataStore.data.first()[dataKey] } ?: return
        val snapshot = runCatching { json.decodeFromString(ProviderSnapshot.serializer(), raw) }.getOrNull() ?: return
        _configs.value = snapshot.configs
        _roles.value = snapshot.roles.mapNotNull { (name, configId) ->
            runCatching { RoleType.valueOf(name) }.getOrNull()?.let { it to configId }
        }.toMap()
    }

    /** Insert or replace one configuration, then persist. */
    suspend fun upsert(cfg: ProviderConfig) {
        load()
        mutex.withLock {
            _configs.value = _configs.value.filterNot { it.id == cfg.id } + cfg
            persistLocked()
        }
    }

    /** Remove a configuration and any role mappings pointing at it, then persist. */
    suspend fun remove(id: String) {
        load()
        mutex.withLock {
            _configs.value = _configs.value.filterNot { it.id == id }
            _roles.value = _roles.value.filterValues { it != id }
            persistLocked()
        }
    }

    /** Map [role] to [configId]; null clears the mapping. Then persist. */
    suspend fun setRole(role: RoleType, configId: String?) {
        load()
        mutex.withLock {
            val next = _roles.value.toMutableMap()
            if (configId == null) next.remove(role) else next[role] = configId
            _roles.value = next
            persistLocked()
        }
    }

    /** Current configId mapped to [role], or null. */
    fun role(role: RoleType): String? = _roles.value[role]

    /** Caller must hold [mutex]. */
    private suspend fun persistLocked() {
        val snapshot = ProviderSnapshot(
            configs = _configs.value,
            roles = _roles.value.mapKeys { (role, _) -> role.name }
        )
        val raw = json.encodeToString(ProviderSnapshot.serializer(), snapshot)
        withContext(Dispatchers.IO) {
            dataStore.edit { prefs -> prefs[dataKey] = raw }
        }
    }
}

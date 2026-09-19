package com.garuda.browser.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.agentDataStore by preferencesDataStore(name = "garuda_agent")

/**
 * Agent-wide user preferences (plan Prompt 6C, Prompt 4): risky-action
 * confirmation, per-domain rate limiting, budgets, background execution.
 */
data class AgentSettings(
    val requireConfirmRisky: Boolean = true,
    val minActionIntervalMs: Int = 700,
    val maxStepsPerTask: Int = 50,
    val tokenBudgetPerTask: Long = 150_000,
    val maxParallelTabs: Int = 3,
    val backgroundEnabled: Boolean = true,
)

class AgentSettingsRepository(private val context: Context) {

    private object Keys {
        val CONFIRM_RISKY = booleanPreferencesKey("require_confirm_risky")
        val MIN_INTERVAL = intPreferencesKey("min_action_interval_ms")
        val MAX_STEPS = intPreferencesKey("max_steps_per_task")
        val TOKEN_BUDGET = longPreferencesKey("token_budget_per_task")
        val MAX_PARALLEL = intPreferencesKey("max_parallel_tabs")
        val BACKGROUND = booleanPreferencesKey("background_enabled")
    }

    val settings: Flow<AgentSettings> = context.agentDataStore.data.map { p ->
        AgentSettings(
            requireConfirmRisky = p[Keys.CONFIRM_RISKY] ?: true,
            minActionIntervalMs = p[Keys.MIN_INTERVAL] ?: 700,
            maxStepsPerTask = p[Keys.MAX_STEPS] ?: 50,
            tokenBudgetPerTask = p[Keys.TOKEN_BUDGET] ?: 150_000,
            maxParallelTabs = p[Keys.MAX_PARALLEL] ?: 3,
            backgroundEnabled = p[Keys.BACKGROUND] ?: true,
        )
    }

    suspend fun setRequireConfirmRisky(v: Boolean) = edit { it[Keys.CONFIRM_RISKY] = v }
    suspend fun setMinActionIntervalMs(v: Int) = edit { it[Keys.MIN_INTERVAL] = v }
    suspend fun setMaxStepsPerTask(v: Int) = edit { it[Keys.MAX_STEPS] = v.coerceIn(5, 200) }
    suspend fun setTokenBudgetPerTask(v: Long) = edit { it[Keys.TOKEN_BUDGET] = v }
    suspend fun setMaxParallelTabs(v: Int) = edit { it[Keys.MAX_PARALLEL] = v.coerceIn(1, 5) }
    suspend fun setBackgroundEnabled(v: Boolean) = edit { it[Keys.BACKGROUND] = v }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.agentDataStore.edit(block)
    }
}

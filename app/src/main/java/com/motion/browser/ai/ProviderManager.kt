package com.motion.browser.ai

import com.motion.browser.ai.core.AIRequestException
import com.motion.browser.ai.core.AiProvider
import com.motion.browser.ai.core.Http
import com.motion.browser.ai.core.LlmMessage
import com.motion.browser.ai.core.LlmOptions
import com.motion.browser.ai.core.LlmRequest
import com.motion.browser.ai.core.LlmResponse
import com.motion.browser.ai.core.ProviderConfig
import com.motion.browser.ai.core.ProviderType
import com.motion.browser.ai.core.RoleType
import com.motion.browser.ai.providers.AnthropicProvider
import com.motion.browser.ai.providers.GeminiProvider
import com.motion.browser.ai.providers.OpenAiCompatibleProvider
import com.motion.browser.security.SecretStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Facade over the AI provider layer (contract §3.3) — agent 2-c calls
 * [chat]/[chatWith]; agent 2-f's Providers screen drives [configs],
 * [saveConfig], [deleteConfig], [setRoleModel], [roleModel].
 *
 * SECURITY: API keys are only ever written to [secretStore] under
 * "provider_key_<id>" and are fetched per request by the adapters. They never
 * appear in ProviderConfig, Room, logs or error messages; every error is
 * redacted through [Http.redact] as defense in depth.
 *
 * Dispatch table: OpenAiCompatibleProvider covers OPENAI_COMPATIBLE,
 * OPENROUTER, OLLAMA and CUSTOM; Anthropic and Gemini have dedicated adapters.
 * Every call is suspend; adapters move network work to Dispatchers.IO.
 */
class ProviderManager(
    private val secretStore: SecretStore,
    private val store: ProviderStore
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val openAiCompatible = OpenAiCompatibleProvider(secretStore)
    private val anthropic = AnthropicProvider(secretStore)
    private val gemini = GeminiProvider(secretStore)

    private val providerList: List<AiProvider> = listOf(openAiCompatible, anthropic, gemini)

    private val dispatch: Map<ProviderType, AiProvider> = mapOf(
        ProviderType.OPENAI_COMPATIBLE to openAiCompatible,
        ProviderType.OPENROUTER to openAiCompatible,
        ProviderType.OLLAMA to openAiCompatible,
        ProviderType.CUSTOM to openAiCompatible,
        ProviderType.ANTHROPIC to anthropic,
        ProviderType.GEMINI to gemini
    )

    init {
        // Hydrate persisted configs + role map; mutations also ensure load, so
        // any early caller never sees stale empty state for long.
        scope.launch { store.load() }
    }

    /** The three concrete adapters (OpenAI-compatible one covers 4 provider types). */
    fun providers(): List<AiProvider> = providerList

    /** Live provider configurations (no secrets). Source of truth: [ProviderStore]. */
    val configs: StateFlow<List<ProviderConfig>> get() = store.configs

    /** Live role → configId mapping (extra, reactive companion to [roleModel]). */
    val roles: StateFlow<Map<RoleType, String>> get() = store.roles

    /**
     * Insert/replace a configuration. When [apiKey] is non-blank it is stored
     * ONLY in SecretStore under "provider_key_<cfg.id>"; a null/blank apiKey
     * leaves any previously stored key untouched. cfg.keyId is normalized to
     * the canonical reference and blank base URLs fall back to the type default.
     */
    suspend fun saveConfig(cfg: ProviderConfig, apiKey: String?) = withContext(Dispatchers.IO) {
        val id = cfg.id.ifBlank { "prov_" + UUID.randomUUID().toString().replace("-", "").take(12) }
        val normalized = cfg.copy(
            id = id,
            baseUrl = cfg.baseUrl.trim(),
            keyId = KEY_PREFIX + id // canonical reference only — never the key itself
        )
        if (!apiKey.isNullOrBlank()) {
            secretStore.put(normalized.keyId, apiKey)
        }
        store.upsert(normalized)
    }

    /** Removes the configuration, its stored key, and any role mappings to it. */
    suspend fun deleteConfig(id: String) = withContext(Dispatchers.IO) {
        runCatching { secretStore.delete(KEY_PREFIX + id) }
        store.remove(id)
    }

    /** Bind [role] to a configId (null = clear; chat() then falls back to any enabled config). */
    suspend fun setRoleModel(role: RoleType, configId: String?) = store.setRole(role, configId)

    /** configId currently mapped to [role], or null. */
    fun roleModel(role: RoleType): String? = store.role(role)

    /**
     * One chat completion for a logical [role]: resolve the role's config →
     * if missing/disabled fall back to any enabled config → if none, return a
     * friendly error. Never throws (except cancellation) and never crashes the caller.
     */
    suspend fun chat(
        role: RoleType,
        messages: List<LlmMessage>,
        opts: LlmOptions = LlmOptions()
    ): LlmResponse {
        store.load()
        val cfg = resolveForRole(role)
            ?: return LlmResponse(
                ok = false,
                error = "No AI provider configured. Add one in Motion AI → Providers."
            )
        return run(cfg, messages, opts)
    }

    /**
     * One chat completion against an explicit configuration (used by "test
     * connection" in the Providers screen, so disabled configs are allowed here).
     */
    suspend fun chatWith(
        configId: String,
        messages: List<LlmMessage>,
        opts: LlmOptions = LlmOptions()
    ): LlmResponse {
        store.load()
        val cfg = store.configs.value.firstOrNull { it.id == configId }
            ?: return LlmResponse(ok = false, error = "Unknown provider configuration.")
        return run(cfg, messages, opts)
    }

    private fun resolveForRole(role: RoleType): ProviderConfig? {
        val all = store.configs.value
        val mapped = all.firstOrNull { it.id == store.role(role) }
        return if (mapped != null && mapped.enabled) mapped else all.firstOrNull { it.enabled }
    }

    private suspend fun run(
        cfg: ProviderConfig,
        messages: List<LlmMessage>,
        opts: LlmOptions
    ): LlmResponse {
        if (cfg.model.isBlank()) {
            return LlmResponse(ok = false, error = "Provider \"${cfg.name}\" has no model set.")
        }
        val provider = dispatch[cfg.type]
            ?: return LlmResponse(ok = false, error = "Provider type ${cfg.type.name} is not supported.")
        return try {
            provider.complete(cfg, LlmRequest(messages = messages, model = cfg.model, options = opts))
        } catch (e: CancellationException) {
            throw e
        } catch (e: AIRequestException) {
            LlmResponse(ok = false, error = Http.redact(e.message ?: "AI request failed.", keyFor(cfg)))
        } catch (e: Exception) {
            LlmResponse(ok = false, error = "AI request failed: ${e.javaClass.simpleName}")
        }
    }

    /** Key fetched only to redact it out of error strings — never persisted/logged. */
    private fun keyFor(cfg: ProviderConfig): String? =
        runCatching { if (cfg.keyId.isBlank()) null else secretStore.get(cfg.keyId) }.getOrNull()

    companion object {
        const val KEY_PREFIX = "provider_key_"
    }
}

package com.talos.harness.store

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Where the model that answers a conversation actually runs.
 *
 * This is an explicit choice rather than a guess: the base URL used to be the
 * only signal, which left no way to say "run the weights on this phone" without
 * inventing a fake endpoint. [CLOUD] and [DESKTOP] still honour the URL, so an
 * install that predates this enum — or a user who typed the gateway URL by hand —
 * keeps working exactly as it did. [ON_DEVICE] never consults the URL at all: a
 * local model has no endpoint, and mistaking one for the other is how a cloud key
 * ends up posted somewhere it does not belong.
 */
enum class ModelMode { CLOUD, DESKTOP, ON_DEVICE }

/**
 * Everything the harness needs to reach a model.
 *
 * [baseUrl] is what makes the same client work against DeepSeek's public API and
 * against a local Hermes Agent gateway (an OpenAI-compatible server on the
 * desktop) — only the URL and key change.
 */
data class TalosSettings(
    /** Which kind of model serves the conversation. See [ModelMode]. */
    val mode: ModelMode = ModelMode.CLOUD,
    val apiKey: String = "",
    val baseUrl: String = DEFAULT_BASE_URL,
    val model: String = DEFAULT_MODEL,
    val temperature: Double = DEFAULT_TEMPERATURE,
    /** Tool names the user chose never to be asked about again. */
    val autoApprovedTools: Set<String> = emptySet(),
    /**
     * Credential for the desktop gateway (`API_SERVER_KEY`).
     *
     * A separate field because the two endpoints authenticate with different
     * secrets: sending the DeepSeek key to the gateway fails with
     * "Invalid gateway API key", which is exactly the trap this avoids.
     */
    val gatewayApiKey: String = "",
) {
    /**
     * True when calls are routed through the Talos gateway on the desktop.
     *
     * The explicit [mode] wins; the URL heuristic stays as the CLOUD fallback so
     * the settings screen's "use local gateway" shortcut and any install that
     * predates [ModelMode] select the bridge exactly as before. [ModelMode.ON_DEVICE]
     * is checked first so a stale gateway URL can never route a local turn to HTTP.
     */
    val usingDesktopBridge: Boolean
        get() = when (mode) {
            ModelMode.DESKTOP -> true
            ModelMode.ON_DEVICE -> false
            ModelMode.CLOUD -> baseUrl.contains(GATEWAY_MARKER)
        }

    /**
     * The credential that matches the selected endpoint. Never mix the two.
     *
     * A local model authenticates against nothing, so ON_DEVICE reports an empty
     * key rather than handing the backend a secret it would only ignore.
     */
    val effectiveApiKey: String
        get() = when {
            mode == ModelMode.ON_DEVICE -> ""
            usingDesktopBridge -> gatewayApiKey
            else -> apiKey
        }

    /** The model id that matches the selected endpoint. */
    val effectiveModel: String get() = if (usingDesktopBridge) GATEWAY_MODEL else model

    /**
     * Whether the app has everything it needs to answer.
     *
     * On-device needs no key — the weights are the credential — and reporting
     * "not configured" would show a setup banner about an API key that does not
     * exist for this mode.
     */
    val isConfigured: Boolean
        get() = mode == ModelMode.ON_DEVICE || effectiveApiKey.isNotBlank()

    companion object {
        const val DEFAULT_BASE_URL = "https://api.deepseek.com/v1"

        /**
         * `deepseek-flash` is DeepSeek-V4.1-Flash: 1M context and, unlike
         * `deepseek-v4-pro`, it accepts image input — which is what makes the
         * camera tool possible. `deepseek-chat` is gone as of the V4 line.
         */
        const val DEFAULT_MODEL = "deepseek-flash"
        const val DEFAULT_TEMPERATURE = 0.3

        /** The desktop bridge, exposed here so the settings screen can offer it. */
        const val LOCAL_GATEWAY_BASE_URL = "http://127.0.0.1:8642/v1"

        /** Substring that identifies the desktop gateway's base URL. */
        const val GATEWAY_MARKER = "127.0.0.1:8642"

        /** The only model id the desktop gateway serves. */
        const val GATEWAY_MODEL = "hermes-agent"

        /**
         * Mode for an install that has never stored one.
         *
         * Before [ModelMode] existed the gateway URL *was* the switch, so a
         * pre-existing desktop setup must keep reporting DESKTOP instead of
         * silently falling back to the cloud on first launch after an update.
         */
        fun inferMode(baseUrl: String): ModelMode =
            if (baseUrl.contains(GATEWAY_MARKER)) ModelMode.DESKTOP else ModelMode.CLOUD

        /** Parses a persisted name, tolerating anything a newer build may write. */
        fun fromStored(value: String?): ModelMode? =
            ModelMode.entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
    }
}


private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "talos_settings")

/**
 * Persists settings with DataStore.
 *
 * The API key lives in app-private storage. That keeps it away from other apps
 * and from backups (the manifest disables backup), but it is not hardware-backed
 * encryption — worth moving behind the Keystore before this ships widely.
 */
class SettingsRepository(private val context: Context) {

    private object Keys {
        val mode = stringPreferencesKey("model_mode")
        val apiKey = stringPreferencesKey("api_key")
        val baseUrl = stringPreferencesKey("base_url")
        val model = stringPreferencesKey("model")
        val temperature = floatPreferencesKey("temperature")
        val autoApprovedTools = stringSetPreferencesKey("auto_approved_tools")
        val gatewayApiKey = stringPreferencesKey("gateway_api_key")
    }

    val flow: Flow<TalosSettings> = context.settingsDataStore.data.map { prefs ->
        val baseUrl = prefs[Keys.baseUrl] ?: TalosSettings.DEFAULT_BASE_URL
        TalosSettings(
            // Not stored yet means this install predates ModelMode; derive it from
            // the URL that used to be the only switch.
            mode = TalosSettings.fromStored(prefs[Keys.mode])
                ?: TalosSettings.inferMode(baseUrl),
            apiKey = prefs[Keys.apiKey].orEmpty(),
            baseUrl = baseUrl,
            model = prefs[Keys.model] ?: TalosSettings.DEFAULT_MODEL,
            temperature = (prefs[Keys.temperature] ?: TalosSettings.DEFAULT_TEMPERATURE.toFloat()).toDouble(),
            autoApprovedTools = prefs[Keys.autoApprovedTools] ?: emptySet(),
            gatewayApiKey = prefs[Keys.gatewayApiKey].orEmpty(),
        )
    }

    /** Persists the explicit mode that [TalosSettings.usingDesktopBridge] derives from. */
    suspend fun setMode(value: ModelMode) = context.settingsDataStore.edit {
        it[Keys.mode] = value.name
    }

    suspend fun setApiKey(value: String) = context.settingsDataStore.edit { it[Keys.apiKey] = value.trim() }

    suspend fun setGatewayApiKey(value: String) = context.settingsDataStore.edit {
        it[Keys.gatewayApiKey] = value.trim()
    }

    suspend fun setBaseUrl(value: String) = context.settingsDataStore.edit {
        it[Keys.baseUrl] = value.trim().ifBlank { TalosSettings.DEFAULT_BASE_URL }
    }

    suspend fun setModel(value: String) = context.settingsDataStore.edit {
        it[Keys.model] = value.trim().ifBlank { TalosSettings.DEFAULT_MODEL }
    }

    suspend fun setTemperature(value: Double) = context.settingsDataStore.edit {
        it[Keys.temperature] = value.coerceIn(0.0, 2.0).toFloat()
    }

    suspend fun approveAlways(toolName: String) = context.settingsDataStore.edit { prefs ->
        prefs[Keys.autoApprovedTools] = (prefs[Keys.autoApprovedTools] ?: emptySet()) + toolName
    }

    suspend fun revokeApproval(toolName: String) = context.settingsDataStore.edit { prefs ->
        prefs[Keys.autoApprovedTools] = (prefs[Keys.autoApprovedTools] ?: emptySet()) - toolName
    }

    suspend fun clearApprovals() = context.settingsDataStore.edit { it.remove(Keys.autoApprovedTools) }
}

package com.hermes.harness.store

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
 * Everything the harness needs to reach a model.
 *
 * [baseUrl] is what makes the same client work against DeepSeek's public API and
 * against a local Hermes Agent gateway (an OpenAI-compatible server on the
 * desktop) — only the URL and key change.
 */
data class HermesSettings(
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
    /** True when calls are routed through the Hermes gateway on the desktop. */
    val usingDesktopBridge: Boolean get() = baseUrl.contains(GATEWAY_MARKER)

    /** The credential that matches the selected endpoint. Never mix the two. */
    val effectiveApiKey: String get() = if (usingDesktopBridge) gatewayApiKey else apiKey

    /** The model id that matches the selected endpoint. */
    val effectiveModel: String get() = if (usingDesktopBridge) GATEWAY_MODEL else model

    val isConfigured: Boolean get() = effectiveApiKey.isNotBlank()

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
    }
}


private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "hermes_settings")

/**
 * Persists settings with DataStore.
 *
 * The API key lives in app-private storage. That keeps it away from other apps
 * and from backups (the manifest disables backup), but it is not hardware-backed
 * encryption — worth moving behind the Keystore before this ships widely.
 */
class SettingsRepository(private val context: Context) {

    private object Keys {
        val apiKey = stringPreferencesKey("api_key")
        val baseUrl = stringPreferencesKey("base_url")
        val model = stringPreferencesKey("model")
        val temperature = floatPreferencesKey("temperature")
        val autoApprovedTools = stringSetPreferencesKey("auto_approved_tools")
        val gatewayApiKey = stringPreferencesKey("gateway_api_key")
    }

    val flow: Flow<HermesSettings> = context.settingsDataStore.data.map { prefs ->
        HermesSettings(
            apiKey = prefs[Keys.apiKey].orEmpty(),
            baseUrl = prefs[Keys.baseUrl] ?: HermesSettings.DEFAULT_BASE_URL,
            model = prefs[Keys.model] ?: HermesSettings.DEFAULT_MODEL,
            temperature = (prefs[Keys.temperature] ?: HermesSettings.DEFAULT_TEMPERATURE.toFloat()).toDouble(),
            autoApprovedTools = prefs[Keys.autoApprovedTools] ?: emptySet(),
            gatewayApiKey = prefs[Keys.gatewayApiKey].orEmpty(),
        )
    }

    suspend fun setApiKey(value: String) = context.settingsDataStore.edit { it[Keys.apiKey] = value.trim() }

    suspend fun setGatewayApiKey(value: String) = context.settingsDataStore.edit {
        it[Keys.gatewayApiKey] = value.trim()
    }

    suspend fun setBaseUrl(value: String) = context.settingsDataStore.edit {
        it[Keys.baseUrl] = value.trim().ifBlank { HermesSettings.DEFAULT_BASE_URL }
    }

    suspend fun setModel(value: String) = context.settingsDataStore.edit {
        it[Keys.model] = value.trim().ifBlank { HermesSettings.DEFAULT_MODEL }
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

package com.talos.harness.store

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

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
 * [providerId] selects endpoint defaults and authentication; [baseUrl] remains
 * editable so the same client can reach custom OpenAI-compatible servers such as
 * the local Hermes Agent gateway.
 */
data class TalosSettings(
    /** Which kind of model serves the conversation. See [ModelMode]. */
    val mode: ModelMode = ModelMode.CLOUD,
    val providerId: String = ProviderCatalog.DEEPSEEK.id,
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
    val apiKeyScope: String = credentialScope(providerId, baseUrl),
    val gatewayKeyScope: String = credentialScope(providerId, baseUrl, desktop = true),
    val credentials: Map<String, String> = emptyMap(),
    val profiles: Map<String, ConnectionProfile> = emptyMap(),
    val localModel: String = "",
    val memoryEnabled: Boolean = true,
    val memoryInRemote: Boolean = true,
) {
    /** Provider behavior is derived from the persisted id, with safe legacy fallback. */
    val provider: Provider get() = ProviderCatalog.byId(providerId) ?: ProviderCatalog.CUSTOM

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
            ModelMode.CLOUD -> isDefaultGateway(baseUrl)
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
            usingDesktopBridge -> gatewayApiKey.takeIf { gatewayKeyScope == credentialScope(providerId, baseUrl, true) }.orEmpty()
            else -> apiKey.takeIf { apiKeyScope == credentialScope(providerId, baseUrl) }.orEmpty()
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
        get() = when {
            mode == ModelMode.ON_DEVICE -> true
            usingDesktopBridge -> effectiveApiKey.isNotBlank()
            provider.id == ProviderCatalog.CUSTOM.id && isLoopbackOrPrivateEndpoint(baseUrl) -> true
            else -> effectiveApiKey.isNotBlank()
        }

    val includesMemory: Boolean get() = memoryEnabled && (mode == ModelMode.ON_DEVICE || memoryInRemote)

    fun connectionDraft() = ConnectionDraft(providerId, baseUrl, effectiveModel, effectiveApiKey, usingDesktopBridge)

    override fun toString(): String = "TalosSettings(mode=$mode, provider=$providerId, credentials=redacted)"

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
            if (isDefaultGateway(baseUrl)) ModelMode.DESKTOP else ModelMode.CLOUD

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
class SettingsRepository(private val dataStore: DataStore<Preferences>) {
    constructor(context: Context) : this(context.settingsDataStore)

    private object Keys {
        val mode = stringPreferencesKey("model_mode")
        val providerId = stringPreferencesKey("provider_id")
        val apiKey = stringPreferencesKey("api_key")
        val baseUrl = stringPreferencesKey("base_url")
        val model = stringPreferencesKey("model")
        val temperature = floatPreferencesKey("temperature")
        val autoApprovedTools = stringSetPreferencesKey("auto_approved_tools")
        val gatewayApiKey = stringPreferencesKey("gateway_api_key")
        val scoped = booleanPreferencesKey("scoped_credentials_v1")
        val memoryEnabled = booleanPreferencesKey("memory_enabled")
        val memoryInRemote = booleanPreferencesKey("memory_in_remote")
        val localModel = stringPreferencesKey("local_model")
        val cloudProvider = stringPreferencesKey("last_cloud_provider")
    }

    private fun profileUrl(id: String) = stringPreferencesKey("profile_url_$id")
    private fun profileModel(id: String) = stringPreferencesKey("profile_model_$id")
    private fun secret(scope: String) = stringPreferencesKey("credential_$scope")

    private fun current(prefs: Preferences): TalosSettings {
        val baseUrl = prefs[Keys.baseUrl] ?: TalosSettings.DEFAULT_BASE_URL
        val providerId = prefs[Keys.providerId]?.let { ProviderCatalog.byId(it)?.id }
            ?: ProviderCatalog.forBaseUrl(baseUrl).id
        val mode = TalosSettings.fromStored(prefs[Keys.mode]) ?: TalosSettings.inferMode(baseUrl)
        val desktop = mode == ModelMode.DESKTOP || (mode == ModelMode.CLOUD && isDefaultGateway(baseUrl))
        val keys = prefs.asMap().entries.mapNotNull { (key, value) ->
            if (key.name.startsWith("credential_") && value is String) key.name.removePrefix("credential_") to value else null
        }.toMap().toMutableMap()
        // Bind legacy secrets once to their known destination BEFORE any retargeting.
        // An inactive cloud key while in desktop mode has no verifiable destination.
        if (prefs[Keys.scoped] != true) {
            if (!desktop && !isDefaultGateway(baseUrl)) {
                credentialScope(providerId, baseUrl).takeIf { it.isNotEmpty() }?.let { keys[it] = prefs[Keys.apiKey].orEmpty() }
            }
            if (desktop || isDefaultGateway(baseUrl)) {
                keys[credentialScope(ProviderCatalog.CUSTOM.id, baseUrl, true)] = prefs[Keys.gatewayApiKey].orEmpty()
            }
        }
        val profiles = (ProviderCatalog.all.map { it.id } + "gateway").mapNotNull { id ->
            prefs[profileUrl(id)]?.let { url -> id to ConnectionProfile(
                if (id == "gateway") ProviderCatalog.CUSTOM.id else id, url,
                prefs[profileModel(id)].orEmpty(),
            ) }
        }.toMap()
        return TalosSettings(
            mode = mode, providerId = providerId, baseUrl = baseUrl,
            apiKey = keys[credentialScope(providerId, baseUrl)].orEmpty(),
            gatewayApiKey = keys[credentialScope(providerId, baseUrl, true)].orEmpty(),
            model = prefs[Keys.model] ?: TalosSettings.DEFAULT_MODEL,
            temperature = (prefs[Keys.temperature] ?: TalosSettings.DEFAULT_TEMPERATURE.toFloat()).toDouble(),
            autoApprovedTools = prefs[Keys.autoApprovedTools] ?: emptySet(), credentials = keys, profiles = profiles,
            localModel = prefs[Keys.localModel] ?: if (mode == ModelMode.ON_DEVICE) prefs[Keys.model].orEmpty() else "",
            memoryEnabled = prefs[Keys.memoryEnabled] ?: true, memoryInRemote = prefs[Keys.memoryInRemote] ?: true,
        )
    }

    val flow: Flow<TalosSettings> = dataStore.data.map(::current)

    private fun migrate(prefs: MutablePreferences) {
        if (prefs[Keys.scoped] == true) return
        val settings = current(prefs)
        settings.credentials.filterKeys { it.isNotEmpty() }.forEach { (scope, key) -> prefs[secret(scope)] = key }
        if (settings.mode == ModelMode.ON_DEVICE) {
            prefs[Keys.localModel] = settings.model
            val remoteMode = if (isDefaultGateway(settings.baseUrl)) ModelMode.DESKTOP else ModelMode.CLOUD
            rememberProfile(prefs, settings.copy(mode = remoteMode, model = settings.provider.defaultModel.orEmpty()))
        } else rememberProfile(prefs, settings)
        if ((settings.usingDesktopBridge || isDefaultGateway(settings.baseUrl)) && prefs[Keys.apiKey].orEmpty().isNotBlank()) {
            // Preserve the inactive legacy secret without assigning it to an unverified endpoint.
            prefs[stringPreferencesKey("legacy_unbound_api_key")] = prefs[Keys.apiKey].orEmpty()
        }
        if (!settings.usingDesktopBridge && !isDefaultGateway(settings.baseUrl) && prefs[Keys.gatewayApiKey].orEmpty().isNotBlank()) {
            prefs[stringPreferencesKey("legacy_unbound_gateway_key")] = prefs[Keys.gatewayApiKey].orEmpty()
        }
        prefs.remove(Keys.apiKey)
        prefs.remove(Keys.gatewayApiKey)
        prefs[Keys.scoped] = true
    }

    private fun rememberProfile(prefs: MutablePreferences, settings: TalosSettings) {
        val id = if (settings.usingDesktopBridge) "gateway" else settings.providerId
        prefs[profileUrl(id)] = settings.baseUrl
        prefs[profileModel(id)] = settings.effectiveModel
        if (!settings.usingDesktopBridge) prefs[Keys.cloudProvider] = settings.providerId
    }

    suspend fun saveConnection(draft: ConnectionDraft) = dataStore.edit { prefs ->
        require(draft.validation() == null) { draft.validation().orEmpty() }
        migrate(prefs)
        val url = draft.baseUrl.trim().trimEnd('/')
        prefs[secret(draft.scope)] = draft.boundKey.trim()
        // Saving a new profile also retains the configuration being left behind.
        val previous = current(prefs)
        if (previous.mode != ModelMode.ON_DEVICE) rememberProfile(prefs, previous)
        prefs[Keys.mode] = (if (draft.desktop) ModelMode.DESKTOP else ModelMode.CLOUD).name
        prefs[Keys.providerId] = draft.providerId
        prefs[Keys.baseUrl] = url
        prefs[Keys.model] = if (draft.desktop) TalosSettings.GATEWAY_MODEL else draft.model.trim()
        rememberProfile(prefs, current(prefs))
    }

    /** A mode change is one transaction; readers never observe a mixed endpoint/key/model. */
    suspend fun setMode(value: ModelMode, localModel: String? = null) = dataStore.edit { prefs ->
        migrate(prefs)
        val previous = current(prefs)
        if (previous.mode != ModelMode.ON_DEVICE) rememberProfile(prefs, previous)
        if (value == ModelMode.ON_DEVICE) {
            val selected = localModel ?: previous.localModel
            if (selected.isNotBlank()) { prefs[Keys.model] = selected; prefs[Keys.localModel] = selected }
        } else {
            val id = if (value == ModelMode.DESKTOP) "gateway" else prefs[Keys.cloudProvider] ?: ProviderCatalog.DEEPSEEK.id
            val provider = if (id == "gateway") ProviderCatalog.CUSTOM else ProviderCatalog.byId(id) ?: ProviderCatalog.DEEPSEEK
            prefs[Keys.providerId] = provider.id
            prefs[Keys.baseUrl] = prefs[profileUrl(id)] ?: if (value == ModelMode.DESKTOP) TalosSettings.LOCAL_GATEWAY_BASE_URL else provider.baseUrl ?: TalosSettings.DEFAULT_BASE_URL
            prefs[Keys.model] = prefs[profileModel(id)] ?: if (value == ModelMode.DESKTOP) TalosSettings.GATEWAY_MODEL else provider.defaultModel.orEmpty()
        }
        prefs[Keys.mode] = value.name
    }

    suspend fun setMemoryPolicy(enabled: Boolean, remote: Boolean) = dataStore.edit {
        it[Keys.memoryEnabled] = enabled
        it[Keys.memoryInRemote] = remote
    }

    suspend fun setTemperature(value: Double) = dataStore.edit {
        it[Keys.temperature] = value.coerceIn(0.0, 2.0).toFloat()
    }

    suspend fun approveAlways(toolName: String) = dataStore.edit { prefs ->
        prefs[Keys.autoApprovedTools] = (prefs[Keys.autoApprovedTools] ?: emptySet()) + toolName
    }

    suspend fun revokeApproval(toolName: String) = dataStore.edit { prefs ->
        prefs[Keys.autoApprovedTools] = (prefs[Keys.autoApprovedTools] ?: emptySet()) - toolName
    }

    suspend fun clearApprovals() = dataStore.edit { it.remove(Keys.autoApprovedTools) }
}

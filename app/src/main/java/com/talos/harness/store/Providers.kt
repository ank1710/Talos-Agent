package com.talos.harness.store

/** Authentication understood by the OpenAI-compatible endpoints used by Talos. */
enum class ProviderAuthStyle { BEARER, X_API_KEY }

/** A model returned by a provider's OpenAI-shaped `/models` endpoint. */
data class ProviderModel(
    val id: String,
    val name: String = id,
)

/** Connection details for one OpenAI-compatible provider. */
data class Provider(
    val id: String,
    val displayName: String,
    /** Null for CUSTOM, which keeps the editable URL already stored in settings. */
    val baseUrl: String?,
    val authStyle: ProviderAuthStyle,
    val extraHeaders: Map<String, String> = emptyMap(),
    val requiresApiKeyForModels: Boolean,
    val defaultModel: String? = null,
)

/** Known provider endpoints; anything else belongs to CUSTOM. */
object ProviderCatalog {
    val DEEPSEEK = Provider(
        id = "deepseek",
        displayName = "DeepSeek",
        baseUrl = "https://api.deepseek.com/v1",
        authStyle = ProviderAuthStyle.BEARER,
        requiresApiKeyForModels = true,
        defaultModel = "deepseek-flash",
    )
    val OPENAI = Provider(
        id = "openai",
        displayName = "OpenAI",
        baseUrl = "https://api.openai.com/v1",
        authStyle = ProviderAuthStyle.BEARER,
        requiresApiKeyForModels = true,
    )
    val OPENROUTER = Provider(
        id = "openrouter",
        displayName = "OpenRouter",
        baseUrl = "https://openrouter.ai/api/v1",
        authStyle = ProviderAuthStyle.BEARER,
        requiresApiKeyForModels = false,
    )
    val ANTHROPIC = Provider(
        id = "anthropic",
        displayName = "Anthropic",
        baseUrl = "https://api.anthropic.com/v1",
        // The compatibility endpoint may accept Bearer auth or Anthropic's native
        // x-api-key. Anthropic's API docs imply x-api-key plus anthropic-version;
        // this provider-scoped choice can be changed in one field if needed.
        authStyle = ProviderAuthStyle.X_API_KEY,
        extraHeaders = mapOf("anthropic-version" to "2023-06-01"),
        requiresApiKeyForModels = true,
    )
    val CUSTOM = Provider(
        id = "custom",
        displayName = "Personalizado",
        baseUrl = null,
        authStyle = ProviderAuthStyle.BEARER,
        // Custom endpoints include local servers such as Ollama that commonly list
        // models without a key; protected custom endpoints can report their own 401.
        requiresApiKeyForModels = false,
    )

    val all: List<Provider> = listOf(DEEPSEEK, OPENAI, OPENROUTER, ANTHROPIC, CUSTOM)

    fun byId(id: String?): Provider? = all.firstOrNull { it.id.equals(id, ignoreCase = true) }

    /** Keep legacy custom URLs, including the desktop gateway, on CUSTOM. */
    fun forBaseUrl(baseUrl: String): Provider = all
        .firstOrNull { it.baseUrl != null && normalizeUrl(it.baseUrl) == normalizeUrl(baseUrl) }
        ?: CUSTOM

    private fun normalizeUrl(value: String): String = value.trim().trimEnd('/')
}

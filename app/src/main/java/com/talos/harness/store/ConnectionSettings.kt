package com.talos.harness.store

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.security.MessageDigest

/** Bind secrets to the complete API endpoint, not merely a provider label or hostname. */
fun credentialScope(providerId: String, baseUrl: String, desktop: Boolean = false): String {
    val url = baseUrl.trim().toHttpUrlOrNull() ?: return ""
    if (url.username.isNotEmpty() || url.password.isNotEmpty() || url.query != null || url.fragment != null) return ""
    val identity = "${if (desktop) "desktop" else "cloud"}|$providerId|${url.toString().trimEnd('/')}"
    return MessageDigest.getInstance("SHA-256").digest(identity.toByteArray()).joinToString("") { "%02x".format(it) }
}

fun isDefaultGateway(baseUrl: String): Boolean = baseUrl.toHttpUrlOrNull()?.let {
    it.host == "127.0.0.1" && it.port == 8642
} == true

data class ConnectionProfile(val providerId: String, val baseUrl: String, val model: String)

/** Kept in memory while editing. Never save a draft containing credentials in Bundle state. */
data class ConnectionDraft(
    val providerId: String,
    val baseUrl: String,
    val model: String,
    val apiKey: String,
    val desktop: Boolean,
    val keyScope: String = credentialScope(providerId, baseUrl, desktop),
) {
    val provider: Provider get() = ProviderCatalog.byId(providerId) ?: ProviderCatalog.CUSTOM
    val scope: String get() = credentialScope(providerId, baseUrl, desktop)
    val boundKey: String get() = apiKey.takeIf { scope.isNotEmpty() && keyScope == scope }.orEmpty()

    fun retarget(providerId: String, baseUrl: String, credentials: Map<String, String>): ConnectionDraft {
        val next = credentialScope(providerId, baseUrl, desktop)
        return copy(providerId = providerId, baseUrl = baseUrl,
            apiKey = if (next == scope) boundKey else credentials[next].orEmpty(), keyScope = next)
    }

    fun validation(requireModel: Boolean = true): String? = when {
        !desktop && isDefaultGateway(baseUrl) -> "Selecione o modo PC para configurar o gateway local."
        scope.isEmpty() -> "Informe uma URL HTTP ou HTTPS válida, sem usuário, senha, parâmetros ou fragmento."
        apiKey.isNotBlank() && keyScope != scope -> "A chave não pertence a este destino. Informe a chave novamente."
        requireModel && model.isBlank() -> "Informe ou escolha um modelo."
        (desktop || providerId != ProviderCatalog.CUSTOM.id || !isLoopbackOrPrivateEndpoint(baseUrl)) && boundKey.isBlank() ->
            if (desktop) "Informe a chave do gateway." else "Informe a chave deste provedor e endereço."
        else -> null
    }

    override fun toString(): String = "ConnectionDraft(provider=$providerId, desktop=$desktop, credentials=redacted)"
}

/**
 * A missing key is acceptable only for a custom endpoint whose host is an
 * explicitly local/private IP. Public custom hosts still require credentials.
 */
internal fun isLoopbackOrPrivateEndpoint(baseUrl: String): Boolean {
    val host = baseUrl.toHttpUrlOrNull()?.host?.lowercase() ?: return false
    if (host == "localhost" || host.endsWith(".localhost")) return true

    val octets = host.split('.')
    if (octets.size == 4 && octets.all { it.isNotEmpty() && it.all { digit -> digit in '0'..'9' } }) {
        val bytes = octets.map { it.toIntOrNull()?.takeIf { value -> value in 0..255 } ?: return false }
        val (first, second) = bytes
        return first == 127 || first == 10 ||
            (first == 172 && second in 16..31) ||
            (first == 192 && second == 168)
    }

    // A colon ensures this is an IPv6 literal, so parsing cannot perform DNS.
    if (':' in host) {
        val literal = host.removePrefix("[").removeSuffix("]")
        val address = runCatching { java.net.InetAddress.getByName(literal) }.getOrNull() ?: return false
        if (address.isLoopbackAddress) return true
        val bytes = address.address
        return bytes.size == 16 && (bytes[0].toInt() and 0xfe) == 0xfc // fc00::/7 ULA
    }
    return false
}



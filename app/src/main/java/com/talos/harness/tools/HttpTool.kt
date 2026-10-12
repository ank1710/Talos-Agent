package com.talos.harness.tools

import com.talos.harness.agent.Risk
import com.talos.harness.llm.DeepSeekClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * Makes an HTTP request on the agent's behalf.
 *
 * Deliberately [Risk.CAUTION], not SAFE: a GET can smuggle data out in a query
 * string and a POST can send anything anywhere. Marking it CAUTION means
 * unattended scheduled runs cannot use it unless the user explicitly opts that
 * task in — which is the whole point of the risk tiers.
 *
 * Uses its own short-timeout client rather than the one behind [DeepSeekClient]:
 * the model client disables read timeouts because completions stream for minutes,
 * and inheriting that here would let a hung server stall an agent turn forever.
 */
class FetchUrlTool : Tool {

    override val name = "fetch_url"

    override val description = """
        Faz uma requisição HTTP e devolve status, cabeçalhos relevantes e o corpo
        em texto. Use para consultar APIs, buscar uma página ou baixar conteúdo.
        O corpo é truncado; se a resposta for binária, apenas o tipo é informado.
    """.trimIndent()

    override val parameters = Schema.obj(
        "url" to Schema.string("URL completa, começando com http:// ou https://"),
        "method" to Schema.string("Método HTTP. Padrão GET.", enumValues = listOf("GET", "POST", "PUT", "DELETE", "HEAD")),
        "body" to Schema.string("Corpo da requisição, para POST e PUT."),
        "content_type" to Schema.string("Content-Type do corpo. Padrão application/json."),
        "max_chars" to Schema.integer("Máximo de caracteres do corpo devolvido. Padrão $DEFAULT_MAX_CHARS."),
        required = listOf("url"),
    )

    override val risk = Risk.CAUTION

    override fun preview(args: JsonObject): String {
        val method = (Args.string(args, "method") ?: "GET").uppercase()
        val url = Args.string(args, "url").orEmpty()
        val body = Args.string(args, "body")
        return buildString {
            append("$method $url")
            if (!body.isNullOrBlank()) append("\n\ncorpo (${body.length} caracteres): ${body.take(300)}")
        }
    }

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult = withContext(Dispatchers.IO) {
        val url = Args.string(args, "url")?.trim().orEmpty()
        if (url.isEmpty()) return@withContext ToolResult.error("Parâmetro 'url' é obrigatório.")
        val initialUrl = url.toHttpUrlOrNull()
            ?: return@withContext ToolResult.error("URL inválida. Use um endereço HTTP ou HTTPS válido.")
        validateDestination(initialUrl)?.let { return@withContext ToolResult.error(it) }

        val method = (Args.string(args, "method") ?: "GET").uppercase()
        val bodyText = Args.string(args, "body")
        val contentType = Args.string(args, "content_type") ?: "application/json"
        val maxChars = (Args.int(args, "max_chars") ?: DEFAULT_MAX_CHARS).coerceIn(200, MAX_MAX_CHARS)

        val requestBody = if (method in setOf("POST", "PUT") && bodyText != null) {
            bodyText.toRequestBody(contentType.toMediaTypeOrNull())
        } else {
            null
        }

        val request = Request.Builder()
            .url(initialUrl)
            .method(method, requestBody)
            .header("Accept", "*/*")
            .header("User-Agent", "Talos/0.1 (Android)")
            .build()

        try {
            var activeRequest = request
            var redirects = 0
            while (true) {
                val redirectError = validateDestination(activeRequest.url)
                if (redirectError != null) return@withContext ToolResult.error(redirectError)

                val response = client.newCall(activeRequest).execute()
                val location = response.header("Location")
                if (response.code in REDIRECT_CODES && location != null) {
                    if (redirects >= MAX_REDIRECTS) {
                        response.close()
                        return@withContext ToolResult.error("A URL excedeu o limite de redirecionamentos.")
                    }
                    val nextUrl = response.request.url.resolve(location)
                    if (nextUrl == null) {
                        response.close()
                        return@withContext ToolResult.error("O servidor enviou um redirecionamento inválido.")
                    }
                    val nextError = validateDestination(nextUrl)
                    if (nextError != null) {
                        response.close()
                        return@withContext ToolResult.error("Redirecionamento bloqueado: $nextError")
                    }
                    if (activeRequest.url.isHttps && !nextUrl.isHttps) {
                        response.close()
                        return@withContext ToolResult.error("Redirecionamento de HTTPS para HTTP bloqueado.")
                    }
                    if (activeRequest.body != null && !sameOrigin(activeRequest.url, nextUrl)) {
                        response.close()
                        return@withContext ToolResult.error(
                            "Redirecionamento para outra origem bloqueado para proteger o corpo enviado.",
                        )
                    }
                    activeRequest = redirectRequest(activeRequest, response.code, nextUrl)
                    response.close()
                    redirects++
                    continue
                }

                return@withContext response.use {
                    val mediaType = response.header("Content-Type").orEmpty()
                    val textual = mediaType.isEmpty() ||
                        mediaType.startsWith("text/") ||
                        mediaType.contains("json") ||
                        mediaType.contains("xml") ||
                        mediaType.contains("javascript") ||
                        mediaType.contains("x-www-form-urlencoded")

                    val headerSummary = listOf("Content-Type", "Location", "Retry-After")
                        .mapNotNull { name -> response.header(name)?.let { "$name: $it" } }
                        .joinToString("\n")

                    val content = if (!textual) {
                        "(corpo binário de ${response.body.contentLength()} bytes; não exibido)"
                    } else {
                        val raw = runCatching { response.body.string() }.getOrDefault("")
                        if (raw.length > maxChars) raw.take(maxChars) + "\n… truncado em $maxChars caracteres." else raw
                    }

                    ToolResult(
                        content = buildString {
                            appendLine("HTTP ${response.code} ${response.message}")
                            if (headerSummary.isNotEmpty()) appendLine(headerSummary)
                            appendLine()
                            append(content)
                        },
                        isError = !response.isSuccessful,
                    )
                }
            }
            ToolResult.error("A requisição não produziu uma resposta.")
        } catch (e: IOException) {
            ToolResult.error("Falha de rede ao acessar $url: ${e.message}")
        } catch (e: IllegalArgumentException) {
            ToolResult.error("URL inválida: ${e.message}")
        }
    }

    private companion object {
        const val DEFAULT_MAX_CHARS = 8_000
        const val MAX_MAX_CHARS = 40_000
        const val MAX_REDIRECTS = 5
        val REDIRECT_CODES = setOf(300, 301, 302, 303, 307, 308)

        val client = okhttp3.OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            // Redirects are followed manually so each destination is checked first.
            .followRedirects(false)
            .followSslRedirects(false)
            // DNS is validated again at connection time to close the preflight/DNS-rebind gap.
            .dns(Dns { hostname ->
                val addresses = Dns.SYSTEM.lookup(hostname)
                if (addresses.isEmpty() || addresses.any(::isBlockedAddress)) {
                    throw UnknownHostException("Endereço local ou privado bloqueado")
                }
                addresses
            })
            // Proxies can resolve destinations themselves, outside the validated DNS policy.
            .proxy(Proxy.NO_PROXY)
            .build()
    }
}

private fun redirectRequest(current: Request, code: Int, nextUrl: HttpUrl): Request {
    val switchToGet = (code == 303 && current.method != "HEAD") ||
        (code == 301 || code == 302) && current.method == "POST"
    if (!switchToGet) return current.newBuilder().url(nextUrl).build()
    return current.newBuilder()
        .url(nextUrl)
        .method("GET", null)
        .removeHeader("Content-Length")
        .removeHeader("Content-Type")
        .removeHeader("Transfer-Encoding")
        .build()
}

private fun sameOrigin(first: HttpUrl, second: HttpUrl): Boolean =
    first.scheme == second.scheme && first.host == second.host && first.port == second.port

/** Resolve before connecting and reject every local, private, link-local, or reserved address. */
private fun validateDestination(url: HttpUrl): String? {
    if (url.scheme != "http" && url.scheme != "https") return "A URL precisa usar HTTP ou HTTPS."
    val addresses = try {
        Dns.SYSTEM.lookup(url.host)
    } catch (_: UnknownHostException) {
        return "Não foi possível resolver o servidor da URL."
    }
    return if (addresses.isEmpty() || addresses.any(::isBlockedAddress)) {
        "A URL não pode acessar endereços locais, privados ou reservados."
    } else {
        null
    }
}

private fun isBlockedAddress(address: InetAddress): Boolean {
    if (
        address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
        address.isSiteLocalAddress || address.isMulticastAddress
    ) return true

    val bytes = address.address
    if (bytes.size == 4) {
        val a = bytes[0].toInt() and 0xff
        val b = bytes[1].toInt() and 0xff
        val c = bytes[2].toInt() and 0xff
        return a == 0 || a == 10 || a == 127 || a >= 224 ||
            (a == 100 && b in 64..127) ||
            (a == 169 && b == 254) ||
            (a == 172 && b in 16..31) ||
            (a == 192 && b == 0 && c in setOf(0, 2)) ||
            (a == 192 && b == 88 && c == 99) ||
            (a == 192 && b == 168) ||
            (a == 198 && b in 18..19) ||
            (a == 198 && b == 51 && c == 100) ||
            (a == 203 && b == 0 && c == 113)
    }
    if (bytes.size != 16) return true

    val isIpv4Mapped = bytes.take(10).all { it.toInt() == 0 } &&
        (bytes[10].toInt() and 0xff) == 0xff && (bytes[11].toInt() and 0xff) == 0xff
    if (isIpv4Mapped) {
        return isBlockedAddress(InetAddress.getByAddress(bytes.copyOfRange(12, 16)))
    }

    val first = bytes[0].toInt() and 0xff
    val second = bytes[1].toInt() and 0xff
    val third = bytes[2].toInt() and 0xff
    val fourth = bytes[3].toInt() and 0xff
    val isUnspecifiedOrCompatible = bytes.take(12).all { it.toInt() == 0 }
    val isDocumentation = first == 0x20 && second == 0x01 && third == 0x0d && fourth == 0xb8
    val isTeredo = first == 0x20 && second == 0x01 && third == 0x00 && fourth == 0x00
    val nat64Prefix = first == 0x00 && second == 0x64 && third == 0xff && fourth == 0x9b
    val isNat64 = nat64Prefix && (bytes.slice(4..11).all { it.toInt() == 0 } ||
        ((bytes[4].toInt() and 0xff) == 0 && (bytes[5].toInt() and 0xff) == 1))
    val isSixToFour = first == 0x20 && second == 0x02 &&
        isBlockedAddress(InetAddress.getByAddress(bytes.copyOfRange(2, 6)))
    return isUnspecifiedOrCompatible ||
        (first and 0xfe) == 0xfc || // fc00::/7 unique-local addresses
        isDocumentation || isTeredo || isNat64 || isSixToFour
}

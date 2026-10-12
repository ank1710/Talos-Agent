package com.hermes.harness.tools

import com.hermes.harness.agent.Risk
import com.hermes.harness.llm.DeepSeekClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
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
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return@withContext ToolResult.error("A URL precisa começar com http:// ou https://")
        }

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
            .url(url)
            .method(method, requestBody)
            .header("Accept", "*/*")
            .header("User-Agent", "Hermes/0.1 (Android)")
            .build()

        try {
            client.newCall(request).execute().use { response ->
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
        } catch (e: IOException) {
            ToolResult.error("Falha de rede ao acessar $url: ${e.message}")
        } catch (e: IllegalArgumentException) {
            ToolResult.error("URL inválida: ${e.message}")
        }
    }

    private companion object {
        const val DEFAULT_MAX_CHARS = 8_000
        const val MAX_MAX_CHARS = 40_000

        val client = okhttp3.OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }
}

package com.talos.harness.tools

import com.talos.harness.agent.Risk
import com.talos.harness.store.WebSearchProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.Proxy
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/** Web search sends the query to a third party and wraps returned pages as untrusted data. */
class WebSearchTool(
    private val provider: WebSearchProvider,
    private val apiKey: String,
) : Tool {
    override val name = "web_search"
    override val description = "Pesquisa na web e retorna títulos, URLs e trechos como conteúdo não confiável."
    override val parameters = Schema.obj(
        "query" to Schema.string("Consulta para pesquisar na internet."),
        "max_results" to Schema.integer("Quantidade de resultados, de 1 a 5; padrão 5."),
        required = listOf("query"),
    )
    override val risk = Risk.CAUTION
    override fun preview(args: JsonObject): String = "Pesquisar na web: ${Args.string(args, "query").orEmpty()}"

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult = withContext(Dispatchers.IO) {
        val query = Args.string(args, "query")?.trim().orEmpty()
        if (query.isBlank()) return@withContext ToolResult.error("O parâmetro 'query' é obrigatório.")
        if (apiKey.isBlank()) {
            val label = if (provider == WebSearchProvider.TAVILY) "Tavily" else "Brave"
            return@withContext ToolResult.error("Busca web selecionada: $label. Cole a chave de API em Ajustes → Busca na web.")
        }
        val limit = (Args.int(args, "max_results") ?: 5).coerceIn(1, 5)
        val endpoint = if (provider == WebSearchProvider.TAVILY) "https://api.tavily.com/search" else "https://api.search.brave.com/res/v1/web/search"
        val base = endpoint.toHttpUrlOrNull()!!
        validateDestination(base)?.let { return@withContext ToolResult.error(it) }
        val request = if (provider == WebSearchProvider.TAVILY) {
            Request.Builder().url(base).post(buildJsonObject {
                put("api_key", apiKey); put("query", query); put("max_results", limit)
            }.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).header("Accept", "application/json").build()
        } else {
            Request.Builder().url(base.newBuilder().addQueryParameter("q", query).addQueryParameter("count", limit.toString()).build())
                .header("Accept", "application/json").header("X-Subscription-Token", apiKey).build()
        }
        try {
            var active = request
            repeat(MAX_REDIRECTS + 1) { hop ->
                validateDestination(active.url)?.let { return@withContext ToolResult.error(it) }
                client.newCall(active).execute().use { response ->
                    val location = response.header("Location")
                    if (response.code in REDIRECT_CODES && location != null) {
                        if (hop == MAX_REDIRECTS) return@withContext ToolResult.error("A busca excedeu o limite de redirecionamentos.")
                        val next = response.request.url.resolve(location)
                            ?: return@withContext ToolResult.error("O provedor enviou um redirecionamento inválido.")
                        validateDestination(next)?.let { return@withContext ToolResult.error("Redirecionamento bloqueado: $it") }
                        if (!sameOrigin(active.url, next) || (active.url.isHttps && !next.isHttps))
                            return@withContext ToolResult.error("Redirecionamento para outra origem bloqueado para proteger a chave da API.")
                        active = active.newBuilder().url(next).build()
                        return@use
                    }
                    if (!response.isSuccessful) return@withContext ToolResult.error("Busca web falhou (HTTP ${response.code}).")
                    val parsed = runCatching { Json.parseToJsonElement(response.body.string()) as? JsonObject }.getOrNull()
                        ?: return@withContext ToolResult.error("O provedor de busca devolveu uma resposta inválida.")
                    val results = extractResults(parsed, limit)
                    return@withContext storedData(JsonArray(results))
                }
            }
            ToolResult.error("A busca não produziu uma resposta.")
        } catch (e: IOException) {
            ToolResult.error("Falha de rede na busca web: ${e.message}")
        }
    }

    private fun extractResults(root: JsonObject, limit: Int): JsonArray {
        val entries = when (provider) {
            WebSearchProvider.TAVILY -> root["results"] as? JsonArray
            WebSearchProvider.BRAVE -> ((root["web"] as? JsonObject)?.get("results") as? JsonArray)
        }.orEmpty()
        return JsonArray(entries.take(limit).mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val title = obj.text("title")
            val url = obj.text("url") ?: obj.text("link")
            val snippet = if (provider == WebSearchProvider.TAVILY) obj.text("content") else obj.text("description")
            if (title == null || url == null || snippet == null) null else buildJsonObject {
                put("title", title.take(300)); put("url", url.take(2000)); put("snippet", snippet.take(1200))
            }
        })
    }

    private fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private companion object {
        const val MAX_REDIRECTS = 5
        val REDIRECT_CODES = setOf(300, 301, 302, 303, 307, 308)
        val client = okhttp3.OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS)
            .followRedirects(false).followSslRedirects(false).proxy(Proxy.NO_PROXY)
            .dns(Dns { host ->
                val addresses = Dns.SYSTEM.lookup(host)
                if (addresses.isEmpty() || addresses.any(::isBlockedAddress)) throw UnknownHostException("Endereço local ou privado bloqueado")
                addresses
            }).build()
        fun sameOrigin(a: HttpUrl, b: HttpUrl) = a.scheme == b.scheme && a.host == b.host && a.port == b.port
    }
}

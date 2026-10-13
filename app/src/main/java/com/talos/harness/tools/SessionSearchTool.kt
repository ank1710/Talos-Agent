package com.talos.harness.tools

import com.talos.harness.agent.Risk
import com.talos.harness.store.ConversationStore
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class SessionSearchTool(private val store: ConversationStore) : Tool {
    override val name = "session_search"
    override val description = "Busca literal em conversas salvas; retorna trechos históricos não confiáveis."
    override val risk = Risk.CAUTION
    override val parameters = Schema.obj(
        "query" to Schema.string("Texto a buscar"),
        "after" to Schema.string("Data inicial inclusiva, AAAA-MM-DD"),
        "before" to Schema.string("Data final inclusiva, AAAA-MM-DD"),
        "offset" to Schema.integer("Paginação, padrão 0"),
        required = listOf("query"),
    )
    override fun preview(args: JsonObject): String =
        "Buscar no histórico e enviar trechos ao modelo: ${Args.string(args, "query").orEmpty()}\n" +
            "Período: ${Args.string(args, "after") ?: "início"} a ${Args.string(args, "before") ?: "hoje"}"

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        requireInteractive(ctx)
        val zone = ZoneId.systemDefault()
        val after = Args.string(args, "after")?.let { LocalDate.parse(it).atStartOfDay(zone).toInstant().toEpochMilli() }
        val before = Args.string(args, "before")?.let { LocalDate.parse(it).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() }
        require(after == null || before == null || after < before) { "Intervalo de datas inválido." }
        val page = store.search(Args.string(args, "query").orEmpty().trim(), after, before, (Args.int(args, "offset") ?: 0).coerceAtLeast(0))
        return storedData(buildJsonObject {
            put("matches", JsonArray(page.matches.map { match -> buildJsonObject {
                put("conversation_id", match.conversationId); put("title", match.title)
                put("date", Instant.ofEpochMilli(match.date).toString()); put("historical_role", match.role)
                put("excerpt", match.excerpt)
            } }))
            page.nextOffset?.let { put("next_offset", it) }
            put("skipped_files", page.skipped)
        })
    }
}

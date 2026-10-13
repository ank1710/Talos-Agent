package com.talos.harness.tools

import com.talos.harness.agent.Risk
import com.talos.harness.store.MemoryStore
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class MemoryTool(private val store: MemoryStore, private val origin: String) : Tool {
    override val name = "memory"
    override val description = "Guarda fatos duráveis, esquece por id ou lista memórias."
    override val risk = Risk.CAUTION
    override val parameters = Schema.obj(
        "action" to Schema.string("Ação", listOf("save", "forget", "list")),
        "text" to Schema.string("Fato, até 1000 caracteres"),
        "id" to Schema.string("Id para esquecer"),
        "offset" to Schema.integer("Paginação, padrão 0"),
        required = listOf("action"),
    )
    override fun preview(args: JsonObject): String = when (Args.string(args, "action")) {
        "save" -> "Guardar na memória:\n${Args.string(args, "text").orEmpty()}"
        "forget" -> "Apagar memória: ${Args.string(args, "id").orEmpty()}"
        else -> "Ler memórias pessoais e enviá-las ao modelo desta conversa."
    }
    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        requireInteractive(ctx)
        return when (Args.string(args, "action")) {
            "save" -> {
                val entry = store.save(Args.string(args, "text").orEmpty(), origin)
                ToolResult.ok("Memória gravada: ${entry.id}")
            }
            "forget" -> {
                store.forget(Args.string(args, "id").orEmpty())
                ToolResult.ok("Memória apagada.")
            }
            "list" -> {
                val entries = store.list()
                val offset = (Args.int(args, "offset") ?: 0).coerceAtLeast(0)
                val page = entries.drop(offset).take(5)
                storedData(buildJsonObject {
                    put("entries", JsonArray(page.map { entry -> buildJsonObject {
                        put("id", entry.id); put("text", entry.text)
                        put("created_at", entry.createdAt); put("updated_at", entry.updatedAt); put("origin", entry.origin)
                    } }))
                    if (offset < entries.size - page.size) put("next_offset", offset + page.size)
                })
            }
            else -> ToolResult.error("Ação inválida: use save, forget ou list.")
        }
    }
}

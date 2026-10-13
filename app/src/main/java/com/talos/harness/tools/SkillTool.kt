package com.talos.harness.tools

import com.talos.harness.agent.Risk
import com.talos.harness.store.SkillStore
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class SkillTool(private val store: SkillStore, private val origin: String) : Tool {
    override val name = "skill"
    override val description = "Gerencia procedimentos. execute lê passos para realizar com as ferramentas normais."
    override val risk = Risk.CAUTION
    override val parameters = Schema.obj(
        "action" to Schema.string("Ação", listOf("create", "list", "execute", "edit", "delete")),
        "name" to Schema.string("Nome: a-z, 0-9, _ ou -, até 64 caracteres"),
        "title" to Schema.string("Nome de exibição opcional, até 80 caracteres"),
        "description" to Schema.string("Resumo, até 160 caracteres"),
        "steps" to Schema.string("Passos, até 6000 caracteres"),
        "offset" to Schema.integer("Paginação, padrão 0"),
        required = listOf("action"),
    )
    override fun preview(args: JsonObject): String = buildString {
        append("Skill: ${Args.string(args, "action").orEmpty()} · ${Args.string(args, "name").orEmpty()}")
        if (Args.string(args, "action") in listOf("create", "edit")) {
            append("\n${Args.string(args, "description").orEmpty()}\n${Args.string(args, "steps").orEmpty()}")
        } else {
            append("\nLer passos não aprova as ações seguintes.")
        }
    }
    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        requireInteractive(ctx)
        val name = Args.string(args, "name").orEmpty()
        return when (val action = Args.string(args, "action")) {
            "create", "edit" -> {
                store.save(name, Args.string(args, "description").orEmpty(), Args.string(args, "steps").orEmpty(), origin, action == "create", Args.string(args, "title"))
                ToolResult.ok("Skill gravada.")
            }
            "delete" -> { store.delete(name); ToolResult.ok("Skill apagada.") }
            "execute" -> {
                val entry = store.list().firstOrNull { it.name == name }
                    ?: return ToolResult.error("Skill não encontrada.")
                // Deliberately no executor, shell, recursive loop or policy callback here.
                // The next model step can propose calls only through AgentLoop's real gate.
                storedData(buildJsonObject {
                    put("name", entry.name); put("title", entry.displayName); put("description", entry.description); put("steps", entry.steps)
                    put("updated_at", entry.updatedAt); put("origin", entry.origin)
                })
            }
            "list" -> {
                val entries = store.list()
                val offset = (Args.int(args, "offset") ?: 0).coerceAtLeast(0)
                val page = entries.drop(offset).take(10)
                storedData(buildJsonObject {
                    put("skills", JsonArray(page.map { entry -> buildJsonObject {
                        put("name", entry.name); put("title", entry.displayName); put("description", entry.description)
                    } }))
                    if (offset < entries.size - page.size) put("next_offset", offset + page.size)
                })
            }
            else -> ToolResult.error("Ação inválida: use create, list, execute, edit ou delete.")
        }
    }
}

package com.hermes.harness.tools

import com.hermes.harness.llm.WireFunctionDef
import com.hermes.harness.llm.WireTool

/**
 * The set of tools offered to the model on a given turn.
 *
 * Tool selection is deliberately global for now; per-conversation toolset
 * filtering can narrow [all] later without touching call sites.
 */
class ToolRegistry(tools: List<Tool>) {

    private val byName: Map<String, Tool> = tools.associateBy { it.name }

    val all: List<Tool> get() = byName.values.toList()

    operator fun get(name: String): Tool? = byName[name]

    fun specs(): List<WireTool> = all.map { tool ->
        WireTool(
            function = WireFunctionDef(
                name = tool.name,
                description = tool.description,
                parameters = tool.parameters,
            ),
        )
    }
}

package com.talos.harness.tools

import android.content.Context
import com.talos.harness.agent.Risk
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.File

/** Outcome of one tool invocation, already flattened to text for the model. */
data class ToolResult(
    val content: String,
    val isError: Boolean = false,
) {
    companion object {
        fun ok(message: String) = ToolResult(message)
        fun error(message: String) = ToolResult(message, isError = true)
    }
}

/**
 * A capability the model can invoke.
 *
 * [risk] drives the approval flow: anything above [Risk.SAFE] pauses the loop
 * until the user confirms. [preview] is what the confirmation sheet shows, so
 * it should read like a sentence a human can judge at a glance.
 */
interface Tool {
    val name: String
    val description: String
    val parameters: JsonObject
    val risk: Risk get() = Risk.SAFE

    fun preview(args: JsonObject): String = name

    suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult
}

/** Everything a tool may touch. Kept small so tools stay easy to test. */
class ToolContext(
    val app: Context,
    /** Sandbox root: the agent's own directory inside the app. */
    val workspace: File,
)

/** Tiny JSON Schema builder — enough for tool declarations, nothing more. */
object Schema {
    fun string(description: String, enumValues: List<String>? = null): JsonObject = buildJsonObject {
        put("type", "string")
        put("description", description)
        if (enumValues != null) {
            put(
                "enum",
                buildJsonArray { enumValues.forEach { add(JsonPrimitive(it)) } },
            )
        }
    }

    fun integer(description: String): JsonObject = buildJsonObject {
        put("type", "integer")
        put("description", description)
    }

    fun boolean(description: String): JsonObject = buildJsonObject {
        put("type", "boolean")
        put("description", description)
    }

    fun obj(
        vararg props: Pair<String, JsonObject>,
        required: List<String> = emptyList(),
    ): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") { props.forEach { (key, schema) -> put(key, schema) } }
        if (required.isNotEmpty()) {
            put("required", buildJsonArray { required.forEach { add(JsonPrimitive(it)) } })
        }
        put("additionalProperties", false)
    }
}

/** Defensive argument readers: models occasionally emit the wrong JSON type. */
object Args {
    fun string(args: JsonObject, key: String): String? =
        (args[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    fun int(args: JsonObject, key: String): Int? =
        (args[key] as? JsonPrimitive)?.content?.trim()?.toIntOrNull()

    fun bool(args: JsonObject, key: String): Boolean? =
        (args[key] as? JsonPrimitive)?.content?.trim()?.toBooleanStrictOrNull()

    @Suppress("unused")
    fun array(args: JsonObject, key: String): JsonArray? = args[key] as? JsonArray
}

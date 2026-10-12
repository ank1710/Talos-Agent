package com.hermes.harness.llm

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * One turn on the wire.
 *
 * [content] is a [JsonElement] rather than a `String` because the OpenAI-compatible
 * API accepts two shapes: a bare string for text-only turns, and an array of typed
 * parts (`{"type":"text"}`, `{"type":"image_url"}`) when a turn carries images.
 * Use [textContent] or [multimodalContent] to build it instead of hand-rolling JSON.
 */
@Serializable
data class WireMessage(
    val role: String,
    val content: JsonElement? = null,
    val name: String? = null,
    @SerialName("tool_calls") val toolCalls: List<WireToolCall>? = null,
    @SerialName("tool_call_id") val toolCallId: String? = null,
)

/** Plain string content, the common case. */
fun textContent(text: String): JsonElement = JsonPrimitive(text)

/**
 * Builds the array-of-parts form.
 *
 * Text comes first so the model reads the instruction before the pixels, and
 * images follow in order. Verified against `deepseek-flash`, whose
 * `input_modalities` include `image`.
 */
fun multimodalContent(text: String, imageDataUrls: List<String>): JsonElement = buildJsonArray {
    if (text.isNotBlank()) {
        add(
            buildJsonObject {
                put("type", "text")
                put("text", text)
            },
        )
    }
    for (url in imageDataUrls) {
        add(
            buildJsonObject {
                put("type", "image_url")
                putJsonObject("image_url") { put("url", url) }
            },
        )
    }
}

/** Convenience for reading a text-only content element back out. */
fun JsonElement?.asPlainText(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

@Suppress("unused")
fun JsonElement?.asParts(): JsonArray? = this as? JsonArray

@Serializable
data class WireToolCall(
    val id: String? = null,
    val type: String = "function",
    val function: WireFunctionCall? = null,
)

@Serializable
data class WireFunctionCall(
    val name: String? = null,
    val arguments: String = "",
)

@Serializable
data class WireTool(
    val type: String = "function",
    val function: WireFunctionDef,
)

@Serializable
data class WireFunctionDef(
    val name: String,
    val description: String,
    val parameters: JsonObject,
)

@Serializable
data class ChatRequest(
    val model: String,
    val messages: List<WireMessage>,
    val stream: Boolean = true,
    val tools: List<WireTool>? = null,
    @SerialName("tool_choice") val toolChoice: String? = null,
    val temperature: Double? = null,
    @SerialName("max_tokens") val maxTokens: Int? = null,
    @SerialName("stream_options") val streamOptions: StreamOptions? = StreamOptions(),
)

@Serializable
data class StreamOptions(@SerialName("include_usage") val includeUsage: Boolean = true)

@Serializable
data class StreamChunk(
    val id: String? = null,
    val model: String? = null,
    val choices: List<StreamChoice> = emptyList(),
    val usage: WireUsage? = null,
)

@Serializable
data class StreamChoice(
    val index: Int = 0,
    val delta: Delta = Delta(),
    @SerialName("finish_reason") val finishReason: String? = null,
)

@Serializable
data class Delta(
    val role: String? = null,
    val content: String? = null,
    @SerialName("reasoning_content") val reasoningContent: String? = null,
    @SerialName("tool_calls") val toolCalls: List<ToolCallDelta>? = null,
)

@Serializable
data class ToolCallDelta(
    val index: Int = 0,
    val id: String? = null,
    val type: String? = null,
    val function: FunctionDelta? = null,
)

@Serializable
data class FunctionDelta(
    val name: String? = null,
    val arguments: String? = null,
)

@Serializable
data class WireUsage(
    @SerialName("prompt_tokens") val promptTokens: Int = 0,
    @SerialName("completion_tokens") val completionTokens: Int = 0,
    @SerialName("total_tokens") val totalTokens: Int = 0,
)

@Serializable
data class ModelsResponse(val data: List<ModelEntry> = emptyList())

@Serializable
data class ModelEntry(val id: String)

@Serializable
data class ApiErrorEnvelope(val error: ApiError? = null)

@Serializable
data class ApiError(val message: String? = null, val type: String? = null, val code: String? = null)

/** Incremental events decoded from the SSE stream. */
sealed interface StreamEvent {
    data class Content(val text: String) : StreamEvent
    data class Reasoning(val text: String) : StreamEvent
    data class ToolCallDelta(
        val index: Int,
        val id: String?,
        val name: String?,
        val argsFragment: String?,
    ) : StreamEvent

    data class Finished(val reason: String?) : StreamEvent
    data class Usage(val usage: WireUsage) : StreamEvent
    data class Failed(val message: String, val retryable: Boolean = false) : StreamEvent
}

package com.talos.harness.agent

import com.talos.harness.llm.WireFunctionCall
import com.talos.harness.llm.WireMessage
import com.talos.harness.llm.WireToolCall
import com.talos.harness.llm.multimodalContent
import com.talos.harness.llm.textContent
import kotlinx.serialization.Serializable
import java.util.UUID

fun newId(): String = UUID.randomUUID().toString()

@Serializable
enum class Role { SYSTEM, USER, ASSISTANT, TOOL }

@Serializable
enum class MessageStatus {
    STREAMING,
    COMPLETE,
    FAILED,
    AWAITING_APPROVAL,
    REJECTED,
}

/** How much damage a tool call could do if the model got it wrong. */
@Serializable
enum class Risk { SAFE, CAUTION, DANGEROUS }

@Serializable
data class ToolCall(
    val id: String,
    val name: String,
    /** Raw JSON arguments exactly as the model emitted them. */
    val arguments: String = "",
)

/**
 * A piece of media attached to a message.
 *
 * Only the URI is persisted; the bytes are resolved into a base64 data URL at
 * send time by `MediaResolver`, which also downscales them. Storing base64 in the
 * conversation files would bloat them by ~33% per image for no benefit.
 */
@Serializable
data class Attachment(
    val uri: String,
    val mimeType: String = "image/jpeg",
    val label: String? = null,
)

@Serializable
data class ChatMessage(
    val id: String = newId(),
    val role: Role,
    val content: String = "",
    /** Chain-of-thought exposed by deepseek-reasoner. */
    val reasoning: String = "",
    val toolCalls: List<ToolCall> = emptyList(),
    val toolCallId: String? = null,
    val toolName: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val status: MessageStatus = MessageStatus.COMPLETE,
    val error: String? = null,
    val risk: Risk = Risk.SAFE,
    val durationMs: Long? = null,
    val attachments: List<Attachment> = emptyList(),
)

@Serializable
data class Conversation(
    val id: String = newId(),
    val title: String = "Nova conversa",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val model: String? = null,
    val messages: List<ChatMessage> = emptyList(),
)

/**
 * Flattens stored messages into the OpenAI-style wire shape. Tool calls become
 * their own assistant turn so the model can see what it asked for, and tool
 * results follow as `role: "tool"` messages keyed by `tool_call_id`.
 *
 * In-flight (`STREAMING`) and failed assistant turns are dropped: replaying a
 * half-written turn would teach the model to truncate.
 *
 * [resolveImages] turns user attachments into base64 data URLs. It is injected
 * rather than called directly so this stays a pure transformation — and because
 * the loop rebuilds the transcript on every step, the implementation is expected
 * to cache.
 */
suspend fun List<ChatMessage>.toWireMessages(
    systemPrompt: String,
    resolveImages: suspend (List<Attachment>) -> List<String> = { emptyList() },
): List<WireMessage> {
    val out = mutableListOf(WireMessage(role = "system", content = textContent(systemPrompt)))

    for (message in this) {
        if (message.status == MessageStatus.STREAMING || message.status == MessageStatus.FAILED) continue

        when (message.role) {
            Role.SYSTEM -> out += WireMessage(
                role = "system",
                content = textContent(message.content),
            )

            Role.USER -> {
                val dataUrls = if (message.attachments.isEmpty()) {
                    emptyList()
                } else {
                    resolveImages(message.attachments)
                }
                out += WireMessage(
                    role = "user",
                    content = if (dataUrls.isEmpty()) {
                        textContent(message.content)
                    } else {
                        multimodalContent(message.content, dataUrls)
                    },
                )
            }

            Role.ASSISTANT -> out += WireMessage(
                role = "assistant",
                content = message.content.takeIf { it.isNotBlank() }?.let(::textContent),
                toolCalls = message.toolCalls
                    .map { call ->
                        WireToolCall(
                            id = call.id,
                            function = WireFunctionCall(
                                name = call.name,
                                arguments = call.arguments.ifBlank { "{}" },
                            ),
                        )
                    }
                    .ifEmpty { null },
            )

            Role.TOOL -> out += WireMessage(
                role = "tool",
                content = textContent(message.content),
                toolCallId = message.toolCallId,
            )
        }
    }
    return out
}

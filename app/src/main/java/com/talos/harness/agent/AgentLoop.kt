package com.talos.harness.agent

import com.talos.harness.core.TalosJson
import com.talos.harness.llm.ChatRequest
import com.talos.harness.llm.DeepSeekClient
import com.talos.harness.llm.StreamEvent
import com.talos.harness.tools.ToolContext
import com.talos.harness.tools.ToolRegistry
import com.talos.harness.tools.ToolResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.coroutines.cancellation.CancellationException

/**
 * The agent turn loop: stream a completion, run whatever tools it asked for,
 * feed the results back, and repeat until the model answers without tools.
 *
 * The loop owns a private transcript copy and never mutates the caller's
 * history; every change is published as an [AgentEvent] so the caller stays the
 * single source of truth for persisted state.
 */
class AgentLoop(
    private val client: DeepSeekClient,
    private val registry: ToolRegistry,
    private val approvals: ApprovalGate,
    private val toolContext: ToolContext,
    private val maxSteps: Int = DEFAULT_MAX_STEPS,
    /**
     * Turns a user message's attachments into base64 data URLs. Injected so the
     * loop stays free of Android I/O; the implementation is expected to cache,
     * because the transcript is rebuilt on every step.
     */
    private val resolveImages: suspend (List<Attachment>) -> List<String> = { emptyList() },
) {

    fun run(
        apiKey: String,
        baseUrl: String,
        model: String,
        systemPrompt: String,
        history: List<ChatMessage>,
        temperature: Double? = null,
    ): Flow<AgentEvent> = flow {
        val transcript = history.toMutableList()
        var step = 0

        while (true) {
            if (step++ >= maxSteps) {
                emit(AgentEvent.Failed("Parei depois de $maxSteps passos para não entrar em loop. Me diga como seguir."))
                return@flow
            }

            val messageId = newId()
            emit(AgentEvent.TurnStarted(messageId))

            val content = StringBuilder()
            val reasoning = StringBuilder()
            val partials = sortedMapOf<Int, PartialCall>()
            var failure: String? = null

            val request = ChatRequest(
                model = model,
                messages = transcript.toWireMessages(systemPrompt, resolveImages),
                tools = registry.specs().ifEmpty { null },
                temperature = temperature,
            )

            client.streamChat(apiKey, baseUrl, request).collect { event ->
                when (event) {
                    is StreamEvent.Content -> {
                        content.append(event.text)
                        emit(AgentEvent.TextDelta(messageId, event.text))
                    }

                    is StreamEvent.Reasoning -> {
                        reasoning.append(event.text)
                        emit(AgentEvent.ReasoningDelta(messageId, event.text))
                    }

                    is StreamEvent.ToolCallDelta -> {
                        val slot = partials.getOrPut(event.index) { PartialCall() }
                        event.id?.let { slot.id = it }
                        event.name?.let { slot.name = it }
                        event.argsFragment?.let { slot.arguments.append(it) }
                    }

                    is StreamEvent.Usage -> emit(AgentEvent.UsageReported(event.usage))
                    is StreamEvent.Finished -> Unit
                    is StreamEvent.Failed -> failure = event.message
                    is StreamEvent.GatewayApprovalRequested -> Unit
                    is StreamEvent.GatewayApprovalResolved -> Unit
                }
            }

            failure?.let {
                emit(AgentEvent.Failed(it))
                return@flow
            }

            val toolCalls = partials.mapNotNull { (index, slot) ->
                val toolName = slot.name ?: return@mapNotNull null
                ToolCall(
                    id = slot.id ?: "call_$index",
                    name = toolName,
                    arguments = slot.arguments.toString().ifBlank { "{}" },
                )
            }

            val assistant = ChatMessage(
                id = messageId,
                role = Role.ASSISTANT,
                content = content.toString(),
                reasoning = reasoning.toString(),
                toolCalls = toolCalls,
            )
            transcript += assistant
            emit(AgentEvent.TurnFinished(assistant))

            if (toolCalls.isEmpty()) {
                emit(AgentEvent.Completed)
                return@flow
            }

            for (call in toolCalls) {
                val tool = registry[call.name]
                if (tool == null) {
                    val missing = toolMessage(call, "ERRO: não existe ferramenta chamada '${call.name}'.")
                    transcript += missing
                    emit(AgentEvent.ToolFinished(missing))
                    continue
                }

                val args = runCatchingCancellable {
                    TalosJson.parseToJsonElement(call.arguments).jsonObject
                }.getOrElse { JsonObject(emptyMap()) }

                emit(AgentEvent.ToolStarted(messageId, call))

                if (tool.risk != Risk.SAFE) {
                    val approval = ApprovalRequest(
                        id = newId(),
                        toolName = tool.name,
                        arguments = call.arguments,
                        preview = runCatchingCancellable { tool.preview(args) }.getOrDefault(tool.name),
                        risk = tool.risk,
                    )
                    emit(AgentEvent.ApprovalRequired(approval))
                    val decision = approvals.request(approval)
                    emit(AgentEvent.ApprovalResolved(approval.id, decision))

                    if (decision == ApprovalDecision.REJECT) {
                        val refused = toolMessage(
                            call,
                            "O usuário recusou esta ação. Não repita a mesma chamada: " +
                                "proponha outro caminho ou pergunte o que ele prefere.",
                        )
                        transcript += refused
                        emit(AgentEvent.ToolFinished(refused))
                        continue
                    }
                }

                val startedAt = System.currentTimeMillis()
                val result = runCatchingCancellable { tool.run(args, toolContext) }
                    .getOrElse { ToolResult.error(it.message ?: "falha inesperada ao executar a ferramenta") }

                val outcome = toolMessage(call, result.content, System.currentTimeMillis() - startedAt)
                transcript += outcome
                emit(AgentEvent.ToolFinished(outcome))
            }
        }
    }

    private fun toolMessage(call: ToolCall, content: String, durationMs: Long? = null) = ChatMessage(
        role = Role.TOOL,
        content = content,
        toolCallId = call.id,
        toolName = call.name,
        durationMs = durationMs,
    )

    private class PartialCall {
        var id: String? = null
        var name: String? = null
        val arguments = StringBuilder()
    }

    private companion object {
        const val DEFAULT_MAX_STEPS = 24
    }
}

/**
 * `runCatching` swallows [CancellationException], which would turn a cancelled
 * turn into a successful tool result. This variant lets cancellation through.
 */
internal inline fun <T> runCatchingCancellable(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Throwable) {
    Result.failure(e)
}

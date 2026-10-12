package com.hermes.harness.agent

import com.hermes.harness.llm.WireUsage

/**
 * Everything the agent loop reports while it works.
 *
 * Streaming deltas are keyed by the id announced in [TurnStarted] so the UI can
 * upsert one message per assistant turn instead of rebuilding the whole list.
 */
sealed interface AgentEvent {
    data class TurnStarted(val messageId: String) : AgentEvent
    data class TextDelta(val messageId: String, val text: String) : AgentEvent
    data class ReasoningDelta(val messageId: String, val text: String) : AgentEvent
    data class TurnFinished(val message: ChatMessage) : AgentEvent

    data class ToolStarted(val parentId: String, val call: ToolCall) : AgentEvent
    data class ToolFinished(val message: ChatMessage) : AgentEvent

    data class ApprovalRequired(val request: ApprovalRequest) : AgentEvent
    data class ApprovalResolved(val requestId: String, val decision: ApprovalDecision) : AgentEvent

    data class UsageReported(val usage: WireUsage) : AgentEvent
    data class Failed(val message: String) : AgentEvent
    data object Completed : AgentEvent
}

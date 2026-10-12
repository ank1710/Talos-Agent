package com.hermes.harness.agent

/** A tool call the model wants to make, presented to the user for a decision. */
data class ApprovalRequest(
    val id: String,
    val toolName: String,
    val arguments: String,
    /** Human-readable sentence describing the concrete action. */
    val preview: String,
    val risk: Risk,
)

enum class ApprovalDecision {
    APPROVE_ONCE,
    APPROVE_ALWAYS,
    REJECT,
}

/**
 * Suspends the agent loop until a human answers.
 *
 * Implementations decide how: the in-app sheet, a notification action, or a
 * remote approval from the desktop. The loop stays unaware of the channel.
 */
interface ApprovalGate {
    suspend fun request(request: ApprovalRequest): ApprovalDecision
}

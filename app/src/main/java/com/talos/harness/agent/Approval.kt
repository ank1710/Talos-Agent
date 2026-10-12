package com.talos.harness.agent

/** A tool call the model wants to make, presented to the user for a decision. */
data class ApprovalRequest(
    val id: String,
    val toolName: String,
    val arguments: String,
    /** Human-readable sentence describing the concrete action. */
    val preview: String,
    val risk: Risk,
    /**
     * Decisions the requester will accept, when it restricts them.
     *
     * Null means "no restriction" — the local tool path — and the dialog offers
     * its full set. The desktop gateway, by contrast, advertises exactly what a
     * given gate allows: some prompts offer only `once` and `deny`, and offering
     * "Sempre" there would send a choice the server rejects.
     */
    val choices: List<ApprovalDecision>? = null,
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

package com.hermes.harness.gateway

import com.hermes.harness.agent.ApprovalDecision
import com.hermes.harness.agent.ApprovalGate
import com.hermes.harness.agent.ApprovalRequest
import kotlinx.coroutines.CompletableDeferred

/**
 * An [ApprovalGate] whose answer comes from somewhere else — the desktop
 * gateway, a notification action, the in-app sheet.
 *
 * It deliberately owns no UI: [request] parks on a [CompletableDeferred] and
 * reports the prompt through [onPending], exactly like the gate in
 * `ChatViewModel`, so the approval dialog renders both identically and an
 * answer arriving from any channel is just a [submit].
 *
 * One prompt at a time: the gateway can raise several on one run, but the brief
 * leaves that unverified and this app only ever shows one dialog, so a second
 * [request] supersedes the first rather than queueing.
 *
 * Unlike the local gate this is *not* consulted against the on-device
 * allowlist. The allowlist is about tools this phone runs; a prompt from the
 * desktop is governed by the desktop's own policy, and silently auto-answering
 * it would bypass that.
 */
class GatewayApprovalGate(
    /** Publishes a parked prompt to the host (the dialog state). */
    private val onPending: (ApprovalRequest) -> Unit = {},
    /** Clears it again once the prompt is settled, however it ended. */
    private val onSettled: (ApprovalRequest) -> Unit = {},
) : ApprovalGate {

    @Volatile
    private var deferred: CompletableDeferred<ApprovalDecision>? = null

    /** Run the parked prompt belongs to; null when no desktop prompt is known. */
    @Volatile
    var runId: String? = null
        private set

    /** Which pending prompt on that run, when the gateway told us. */
    @Volatile
    var requestId: String? = null
        private set

    /** Choices the gateway advertised for the parked prompt, verbatim. */
    @Volatile
    var choices: List<String> = emptyList()
        private set

    /**
     * Records the ids the frame carried, once they are known.
     *
     * [request] may be reached before the frame is parsed (the loop can park
     * first), so the association is a separate, repeatable call rather than a
     * constructor argument.
     */
    fun attach(runId: String?, requestId: String?, choices: List<String> = emptyList()) {
        this.runId = runId
        this.requestId = requestId
        this.choices = choices
    }

    /** Forgets the ids once the prompt is over, so a stale run is never answered. */
    fun clear() {
        runId = null
        requestId = null
        choices = emptyList()
    }

    override suspend fun request(request: ApprovalRequest): ApprovalDecision {
        val pending = CompletableDeferred<ApprovalDecision>()
        deferred = pending
        onPending(request)
        return try {
            pending.await()
        } finally {
            // Only drop our own slot: a prompt that arrived in the meantime must
            // not be detached by the previous one unwinding.
            if (deferred === pending) deferred = null
            onSettled(request)
        }
    }

    /** Answers the parked prompt. A no-op when nothing is waiting. */
    fun submit(decision: ApprovalDecision) {
        deferred?.complete(decision)
    }

    /**
     * Takes a parked prompt down without a real answer — the run was stopped, or
     * the desktop resolved it. Completes fail-closed ([ApprovalDecision.REJECT])
     * so a caller that reacts to the returned value can never mistake a
     * disappeared prompt for consent.
     */
    fun cancel() {
        deferred?.complete(ApprovalDecision.REJECT)
    }
}

/**
 * The dialog button a gateway choice authorises, or null for a choice this
 * build does not offer.
 *
 * `session` and `always` are the gateway's two spellings for "stop asking";
 * both map to [ApprovalDecision.APPROVE_ALWAYS]. `approve`/`approved`/`allow`
 * are the documented aliases of `once` (`docs/remote-approvals.md`).
 */
fun decisionForGatewayChoice(choice: String): ApprovalDecision? = when (choice.lowercase()) {
    "once", "approve", "approved", "allow" -> ApprovalDecision.APPROVE_ONCE
    "session", "always" -> ApprovalDecision.APPROVE_ALWAYS
    "deny" -> ApprovalDecision.REJECT
    else -> null
}

/**
 * The wire spelling to POST for [decision], preferring whatever the event
 * advertised (a gate that offers `session` but not `always` rejects `always`).
 *
 * Falls back to the canonical spelling so an answer is never dropped; the
 * dialog only offers advertised decisions, so the fallback is unreachable for
 * the remote path and exists for the local one.
 */
fun gatewayChoiceFor(decision: ApprovalDecision, advertised: List<String>): String {
    val aliases = when (decision) {
        ApprovalDecision.APPROVE_ONCE -> listOf("once", "approve", "approved", "allow")
        ApprovalDecision.APPROVE_ALWAYS -> listOf("always", "session")
        ApprovalDecision.REJECT -> listOf("deny")
    }
    val normalized = advertised.map { it.lowercase() }
    return aliases.firstOrNull { it in normalized } ?: aliases.first()
}

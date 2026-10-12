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
 * Several prompts can be in flight at once. The gateway keys every
 * `approval.request` by its `request_id` and accepts a matching `request_id` on
 * the decision POST, so this gate keeps *all* parked prompts instead of one
 * slot. A single slot would strand the earlier caller forever, because a second
 * [request] would overwrite the deferred that [submit] and [cancel] reach.
 *
 * The app still shows one dialog at a time, and prompts are published in the
 * order they arrived, so [submit] answers the oldest — FIFO is the honest
 * behaviour for a one-dialog UI. [submitFor] targets one specific
 * `request_id` for a caller that already knows which prompt it is deciding.
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

    /**
     * One parked prompt together with the ids read off the frame that raised it.
     *
     * The ids are kept per entry, not only in the [runId]/[requestId]/[choices]
     * scalars below: with several prompts in flight those scalars describe the
     * frame that arrived *last*, while the decision POST must carry the ids of
     * the prompt the user was actually shown.
     */
    data class Parked(
        val request: ApprovalRequest,
        val runId: String?,
        val requestId: String?,
        val choices: List<String>,
    )

    private class Slot(
        val key: String,
        val parked: Parked,
        val deferred: CompletableDeferred<ApprovalDecision> = CompletableDeferred(),
    )

    /** Guards [parked] and [displayed]; callbacks are always fired outside it. */
    private val lock = Any()

    /**
     * Every prompt still waiting, oldest first.
     *
     * A [LinkedHashMap] iterates in insertion order, so "oldest" is just the
     * first live entry — no second queue that could drift out of step.
     */
    private val parked = LinkedHashMap<String, Slot>()

    /** Key of the entry currently published through [onPending], if any. */
    private var displayed: String? = null

    /**
     * Run id of the most recently [attach]ed frame; null when no desktop prompt
     * is known.
     *
     * Kept for callers that only care about the latest frame. Prefer [oldest]
     * when answering a prompt, because this scalar is overwritten by every
     * later frame even while an earlier prompt is still on screen.
     */
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
     * constructor argument. [request] snapshots these values into its own
     * entry, so a later frame does not rewrite an already-parked prompt's ids.
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

    /** The oldest prompt still waiting, with the ids needed to answer it. */
    val oldest: Parked?
        get() = synchronized(lock) {
            parked.values.firstOrNull { !it.deferred.isCompleted }?.parked
        }

    /** How many prompts are still waiting for an answer. */
    val pendingCount: Int
        get() = synchronized(lock) { parked.values.count { !it.deferred.isCompleted } }

    override suspend fun request(request: ApprovalRequest): ApprovalDecision {
        // Snapshot the ids now: a frame that arrives while this prompt is
        // parked would otherwise overwrite the scalars and mis-address the
        // answer for this one.
        val snapshot = Parked(request, runId, requestId, choices)
        val slot = synchronized(lock) {
            // request_id is the gateway's own key. Without one, the run id is
            // the best grouping; a prompt with neither still gets a unique key
            // from its own id so two of them cannot clobber each other.
            val base = snapshot.requestId ?: snapshot.runId ?: request.id
            var key = base
            var suffix = 2
            while (parked.containsKey(key)) key = "$base#${suffix++}"
            Slot(key, snapshot).also { parked[key] = it }
        }
        // Only the head of the queue reaches the dialog; the rest wait their turn.
        publishHead()
        return try {
            slot.deferred.await()
        } finally {
            synchronized(lock) {
                parked.remove(slot.key)
                // Only the entry that was on screen frees the dialog, so a
                // prompt settled out of order cannot blank someone else's.
                if (displayed == slot.key) displayed = null
            }
            onSettled(request)
            // The dialog is free now: reveal the next prompt, if one is waiting.
            publishHead()
        }
    }

    /**
     * Answers the oldest parked prompt (FIFO). A no-op when nothing is waiting.
     *
     * The gate publishes prompts in arrival order, so the one on screen *is*
     * the oldest; answering in any other order would decide a prompt the user
     * never saw.
     */
    fun submit(decision: ApprovalDecision) {
        val oldestSlot = synchronized(lock) {
            parked.values.firstOrNull { !it.deferred.isCompleted }
        } ?: return
        oldestSlot.deferred.complete(decision)
    }

    /**
     * Answers one specific parked prompt, named the way the gateway names it
     * (`request_id`).
     *
     * Falls back to matching the entry's key so a prompt parked without a
     * `request_id` (keyed by run id, or by its own id) can still be targeted.
     * Returns false when no live prompt matches, i.e. the caller's answer has
     * nothing to settle.
     */
    fun submitFor(requestId: String, decision: ApprovalDecision): Boolean {
        val match = synchronized(lock) {
            parked.values.firstOrNull {
                (it.parked.requestId == requestId || it.key == requestId) && !it.deferred.isCompleted
            }
        } ?: return false
        return match.deferred.complete(decision)
    }

    /**
     * Takes every parked prompt down without a real answer — the run was
     * stopped, or the desktop resolved it. Completes fail-closed
     * ([ApprovalDecision.REJECT]) so a caller that reacts to the returned value
     * can never mistake a disappeared prompt for consent.
     *
     * *All* of them, not just the newest: leaving one pending is a caller
     * suspended forever when the turn is cancelled.
     */
    fun cancel() {
        val all = synchronized(lock) { parked.values.toList() }
        all.forEach { it.deferred.complete(ApprovalDecision.REJECT) }
    }

    /**
     * Publishes the head of the queue unless a prompt is already on screen.
     *
     * Called after a park and after a settle, which is what makes the display
     * FIFO: the second prompt is not pushed at a dialog that is still busy, it
     * is shown when the first one leaves.
     */
    private fun publishHead() {
        val next = synchronized(lock) {
            // A stale key can only linger if its entry vanished without the
            // finally block; treat that as "nothing displayed".
            if (displayed != null && parked.containsKey(displayed)) return@synchronized null
            displayed = null
            val head = parked.values.firstOrNull { !it.deferred.isCompleted }
                ?: return@synchronized null
            displayed = head.key
            head.parked
        }
        if (next != null) onPending(next.request)
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

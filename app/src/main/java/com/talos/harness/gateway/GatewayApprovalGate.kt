package com.talos.harness.gateway

import com.talos.harness.agent.ApprovalDecision
import com.talos.harness.agent.ApprovalGate
import com.talos.harness.agent.ApprovalRequest
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
 * The ids a decision must be POSTed with are read off the frame that raised the
 * prompt and travel *inside* that prompt's entry: [forFrame] captures them while
 * the frame is being handled, and [request] carries them into the coroutine that
 * parks the prompt. They are never read back from fields that a later frame
 * could overwrite, so a prompt can never be answered with another request's ids.
 *
 * The app still shows one dialog at a time, and prompts are published in the
 * order they arrived, so [submit] answers the oldest — FIFO is the honest
 * behaviour for a one-dialog UI. [submitFor] targets one specific `request_id`
 * for a caller that already knows which prompt it is deciding.
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
     * The endpoint and key captured when one desktop run started.
     * Its custom string form avoids leaking the credential through accidental logs.
     */
    class Target(
        val baseUrl: String,
        val origin: String,
        val apiKey: String,
    ) {
        override fun toString(): String = "GatewayApprovalTarget(origin=$origin)"
    }

    /**
     * One parked prompt together with the ids read off the frame that raised it.
     *
     * The ids are kept per entry, not in gate-wide fields: with several prompts
     * in flight a shared field would describe the frame that arrived *last*,
     * while the decision POST must carry the ids of the prompt the user was
     * actually shown.
     */
    data class Parked(
        val request: ApprovalRequest,
        val runId: String?,
        val requestId: String?,
        val choices: List<String>,
        val target: Target?,
    )

    /**
     * The ids one `approval.request` frame carried, bound to that frame.
     *
     * Built by [forFrame] on the thread that reads the frame and handed back to
     * [request] from the coroutine that parks the prompt. Because the value is
     * carried, a second frame read before that coroutine runs cannot change the
     * ids this prompt will be answered with.
     *
     * [cancelEpoch] is the state of the gate-wide cancel when the frame was
     * read. A prompt whose frame predates a [cancel] must not park on a
     * response that already happened — see [request].
     */
    class Frame internal constructor(
        internal val runId: String?,
        internal val requestId: String?,
        internal val choices: List<String>,
        internal val target: Target?,
        internal val cancelEpoch: Int,
    )

    private class Slot(
        val key: String,
        val parked: Parked,
        val deferred: CompletableDeferred<ApprovalDecision> = CompletableDeferred(),
    )

    /** Guards [parked], [displayed], [settledEarly] and [cancelEpoch]. */
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
     * `request_id`s the gateway settled *before* their prompt registered.
     *
     * An `approval.responded` frame is handled on the callback thread while the
     * prompt's registration runs in a coroutine, so the response can be read
     * first. Without this memory the prompt would park afterwards and wait
     * forever for an answer that already happened. Bounded because only the
     * handful of frames that can race a registration matter.
     */
    private val settledEarly = LinkedHashSet<String>()

    /**
     * Bumped by [cancel], which takes every parked prompt down at once.
     *
     * Read through [forFrame] while a frame is handled, so a registration
     * scheduled before a cancel can tell that it lost the race.
     */
    @Volatile
    private var cancelEpoch: Int = 0

    /**
     * Captures one `approval.request` frame's ids, together with the gate-wide
     * cancel state at this moment.
     *
     * Call it on the frame callback, not inside the coroutine that parks the
     * prompt: the point is to bind the ids to the prompt as the frame is read.
     */
    fun forFrame(
        runId: String?,
        requestId: String?,
        choices: List<String> = emptyList(),
        target: Target? = null,
    ): Frame = Frame(runId, requestId, choices, target, cancelEpoch)

    /** The oldest prompt still waiting, with the ids needed to answer it. */
    val oldest: Parked?
        get() = synchronized(lock) {
            parked.values.firstOrNull { !it.deferred.isCompleted }?.parked
        }

    /** How many prompts are still waiting for an answer. */
    val pendingCount: Int
        get() = synchronized(lock) { parked.values.count { !it.deferred.isCompleted } }

    /**
     * A prompt raised by the loop itself (a local tool in bridge mode).
     *
     * It has no gateway frame behind it, so it carries no run id and is answered
     * locally: the decision resumes the loop's own coroutine instead of being
     * POSTed anywhere.
     */
    override suspend fun request(request: ApprovalRequest): ApprovalDecision =
        request(request, forFrame(runId = null, requestId = null))

    /**
     * Parks [request] together with the ids of the frame that raised it.
     *
     * [frame] must come from [forFrame] on the callback thread. The ids are
     * copied into this prompt's own entry here and are never read from shared
     * state again, so two prompts in flight are each answered with their own
     * `run_id`/`request_id`/`choices`.
     *
     * If the response is already known — an `approval.responded` for this
     * `request_id`, or a [cancel] that happened after the frame was read — the
     * prompt is settled fail-closed ([ApprovalDecision.REJECT]) and never
     * published, instead of parking and stranding the caller.
     */
    suspend fun request(request: ApprovalRequest, frame: Frame): ApprovalDecision {
        val snapshot = Parked(request, frame.runId, frame.requestId, frame.choices, frame.target)
        val slot = synchronized(lock) {
            val alreadySettled = frame.cancelEpoch < cancelEpoch ||
                (frame.requestId != null && frame.requestId in settledEarly)
            if (alreadySettled) {
                null
            } else {
                // request_id is the gateway's own key. Without one, the run id is
                // the best grouping; a prompt with neither still gets a unique key
                // from its own id so two of them cannot clobber each other.
                val base = frame.requestId ?: frame.runId ?: request.id
                var key = base
                var suffix = 2
                while (parked.containsKey(key)) key = "$base#${suffix++}"
                Slot(key, snapshot).also { parked[key] = it }
            }
        }

        if (slot == null) return ApprovalDecision.REJECT

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
     * Answers the oldest parked prompt (FIFO) and returns it, or null when
     * nothing is waiting.
     *
     * The gate publishes prompts in arrival order, so the one on screen *is*
     * the oldest; answering in any other order would decide a prompt the user
     * never saw. The entry is returned so the caller POSTs the ids of the prompt
     * it actually settled — a frame answered in between must not let the
     * decision carry the *next* prompt's ids.
     */
    fun submit(decision: ApprovalDecision): Parked? = synchronized(lock) {
        val oldestSlot = parked.values.firstOrNull { !it.deferred.isCompleted }
            ?: return@synchronized null
        oldestSlot.deferred.complete(decision)
        oldestSlot.parked
    }

    /**
     * Answers one specific parked prompt, named the way the gateway names it
     * (`request_id`).
     *
     * Falls back to matching the entry's key so a prompt parked without a
     * `request_id` (keyed by run id, or by its own id) can still be targeted.
     * Returns false when no live prompt matches; the id is then remembered so a
     * prompt that registers afterwards fails closed instead of stranding.
     */
    fun submitFor(requestId: String, decision: ApprovalDecision): Boolean = synchronized(lock) {
        val match = parked.values.firstOrNull {
            (it.parked.requestId == requestId || it.key == requestId) && !it.deferred.isCompleted
        }
        if (match != null) {
            match.deferred.complete(decision)
            true
        } else {
            settledEarly += requestId
            while (settledEarly.size > REMEMBERED_SETTLED_IDS) {
                settledEarly.remove(settledEarly.first())
            }
            false
        }
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
        val all = synchronized(lock) {
            // Registrations scheduled before this point must not park afterwards:
            // their response is already known (see [Frame.cancelEpoch]).
            cancelEpoch++
            parked.values.toList()
        }
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

    private companion object {
        /** See [settledEarly]; a registration race is one dispatch wide. */
        const val REMEMBERED_SETTLED_IDS = 32
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

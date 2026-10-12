package com.hermes.harness.schedule

import com.hermes.harness.agent.newId
import kotlinx.serialization.Serializable

/**
 * One stored prompt the agent runs on its own, without the user watching.
 *
 * The last outcome is kept on the task itself rather than in a separate run log:
 * the schedule screen only ever shows "what happened last time", and one record
 * per task cannot grow without bound the way a history would.
 *
 * [intervalMinutes] is the cadence the user asked for, not the cadence the
 * system can deliver — see [isDue].
 */
@Serializable
data class ScheduledTask(
    val id: String = newId(),
    val title: String,
    val prompt: String,
    /** How often the task should run. WorkManager's floor is 15 minutes. */
    val intervalMinutes: Int = 60,
    val enabled: Boolean = true,
    /** When false, unattended runs may only use tools whose risk is SAFE. */
    val allowSensitiveTools: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val lastRunAt: Long? = null,
    val lastStatus: String? = null,
    val lastResult: String? = null,
) {

    /**
     * Whether the task should run in this pass.
     *
     * Never-run tasks are due immediately, so creating one does not mean waiting
     * a full interval for the first result. The effective interval is floored at
     * [MIN_INTERVAL_MINUTES]: it is the same floor WorkManager enforces on the
     * periodic worker, so asking for less would silently never be honoured —
     * better to run at the real floor than to store a cadence that is a lie.
     *
     * The elapsed-time form avoids `now - lastRunAt`, which can overflow on a
     * clock change.
     */
    fun isDue(now: Long = System.currentTimeMillis()): Boolean {
        if (!enabled) return false
        val last = lastRunAt ?: return true
        val minutes = intervalMinutes.coerceAtLeast(MIN_INTERVAL_MINUTES)
        val intervalMs = minutes.toLong() * 60_000L
        return now - last >= intervalMs
    }

    companion object {
        /**
         * WorkManager's own minimum periodic interval. Kept next to [isDue]
         * because both the schedule and the due check must agree on it.
         */
        const val MIN_INTERVAL_MINUTES = 15
    }
}

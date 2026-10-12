package com.talos.harness.schedule

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.talos.harness.agent.AgentEvent
import com.talos.harness.agent.AgentLoop
import com.talos.harness.agent.ApprovalDecision
import com.talos.harness.agent.ApprovalGate
import com.talos.harness.agent.ApprovalRequest
import com.talos.harness.agent.ChatMessage
import com.talos.harness.agent.Risk
import com.talos.harness.agent.Role
import com.talos.harness.agent.SystemPrompt
import com.talos.harness.llm.DeepSeekClient
import com.talos.harness.notifications.TalosNotifications
import com.talos.harness.store.TalosSettings
import com.talos.harness.store.SettingsRepository
import com.talos.harness.tools.ClipboardReadTool
import com.talos.harness.tools.ClipboardWriteTool
import com.talos.harness.tools.DeviceInfoTool
import com.talos.harness.tools.ListAppsTool
import com.talos.harness.tools.ListDirTool
import com.talos.harness.tools.NotifyTool
import com.talos.harness.tools.OpenUrlTool
import com.talos.harness.tools.ReadFileTool
import com.talos.harness.tools.ShellTool
import com.talos.harness.tools.ToolContext
import com.talos.harness.tools.CalendarReadTool
import com.talos.harness.tools.ContactsSearchTool
import com.talos.harness.tools.FetchUrlTool
import com.talos.harness.tools.ToolRegistry
import com.talos.harness.tools.WriteFileTool
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * The periodic entry point for unattended runs, plus [ScheduleScheduler]'s
 * one-shot variant.
 *
 * Each execution wakes up cold: there is no process state to inherit from the UI,
 * so the settings, the task list and the tool registry are all rebuilt here.
 */
class ScheduledAgentWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    // One HTTP client per run rather than per task: OkHttp's connection pool is
    // the only thing shared between tasks, and it is thread-safe by contract.
    private val client = DeepSeekClient()

    override suspend fun doWork(): Result = try {
        withContext(Dispatchers.IO) {
            val oneShotId = inputData.getString(ScheduleScheduler.EXTRA_TASK_ID)
            val store = ScheduledTaskStore(applicationContext.filesDir)

            val settings = SettingsRepository(applicationContext).flow.first()
            if (!settings.isConfigured) {
                // There is no key to call the API with, so this is not a failure of
                // the run — it is an unconfigured app. Retrying would burn the
                // backoff budget forever on a condition only the user can fix.
                recordWithoutRunning(store, oneShotId, "erro: chave da API não configurada")
                return@withContext Result.success()
            }

            if (oneShotId != null) {
                runOne(store, settings, oneShotId)
            } else {
                runDueTasks(store, settings)
            }

            // Retry the *whole* worker only after a transient network failure, and
            // only a bounded number of times. WorkManager's own backoff would
            // otherwise keep a dead network retrying for hours.
            if (transientFailure.get() && runAttemptCount < MAX_RETRY_ATTEMPTS) {
                Result.retry()
            } else {
                Result.success()
            }
        }
    } catch (e: CancellationException) {
        // The one exception that must escape: swallowing a stop request would turn
        // it into a normal completion and keep a cancelled worker alive.
        throw e
    } catch (e: Throwable) {
        // Belt and braces. The per-task catch in `attempt` already contains task
        // failures, so reaching here means something structural (the store,
        // DataStore) broke and there is no reliable place left to record it.
        android.util.Log.e("TalosScheduledWorker", "falha inesperada no worker agendado", e)
        Result.success()
    }

    /** Set when a task failed for a reason that a later attempt could clear. */
    private val transientFailure = AtomicBoolean(false)

    private suspend fun runDueTasks(store: ScheduledTaskStore, settings: TalosSettings) {
        val due = store.list().filter { it.enabled && it.isDue() }
        // Sequential on purpose: each task drives a streaming model call, and
        // running them in parallel would multiply concurrent HTTP calls for no
        // wall-clock gain the user is waiting on.
        for (task in due) {
            attempt(store, settings, task)
        }
    }

    private suspend fun runOne(store: ScheduledTaskStore, settings: TalosSettings, taskId: String) {
        val task = store.list().firstOrNull { it.id == taskId } ?: return
        attempt(store, settings, task)
    }

    /**
     * Runs one task and contains every failure to that task.
     *
     * The whole point of an unattended schedule is that the next task still runs
     * when one of them breaks; without a per-task catch, a single malformed
     * prompt or one unhandled error would silently disable the entire schedule
     * for as long as it stayed broken.
     */
    private suspend fun attempt(store: ScheduledTaskStore, settings: TalosSettings, task: ScheduledTask) {
        var status: String
        var result: String
        try {
            val outcome = runTask(task, settings)
            status = outcome.status
            result = outcome.result
            if (outcome.transient) transientFailure.set(true)
        } catch (e: CancellationException) {
            // WorkManager is stopping us. The task is left without a recorded run
            // so it stays due and will be picked up next pass.
            throw e
        } catch (e: Throwable) {
            status = "erro: ${headline(e.message ?: e::class.java.simpleName)}"
            result = status
        }

        runCatching { store.recordRun(task.id, status, result) }
        postOutcome(task, status, result)
    }

    /**
     * Drives one task through [AgentLoop] and reduces the event stream to an
     * outcome. Nothing is persisted here: the caller owns that.
     */
    private suspend fun runTask(task: ScheduledTask, settings: TalosSettings): Outcome {
        val workspace = File(applicationContext.filesDir, "workspace").apply { mkdirs() }

        val loop = AgentLoop(
            client = client,
            registry = toolRegistry(),
            approvals = UnattendedApprovalGate(allowSensitiveTools = task.allowSensitiveTools),
            toolContext = ToolContext(applicationContext, workspace),
            maxSteps = UNATTENDED_MAX_STEPS,
            resolveImages = { emptyList() },
        )

        val turnMessages = mutableListOf<ChatMessage>()
        var failure: String? = null

        loop.run(
            apiKey = settings.effectiveApiKey,
            baseUrl = settings.baseUrl,
            model = settings.effectiveModel,
            systemPrompt = SystemPrompt.build(workspace.absolutePath),
            history = listOf(ChatMessage(role = Role.USER, content = task.prompt)),
            temperature = settings.temperature,
        ).collect { event ->
            when (event) {
                // The turn-level deltas are redundant here: TurnFinished carries
                // the same text already assembled.
                is AgentEvent.TextDelta -> Unit
                is AgentEvent.TurnFinished -> turnMessages += event.message
                is AgentEvent.Failed -> failure = event.message
                else -> Unit
            }
        }

        val answer = finalAnswer(turnMessages)
        return when {
            failure != null -> Outcome(
                status = "erro: ${headline(failure!!)}",
                result = failure!!,
                transient = looksTransient(failure!!),
            )

            answer == null -> Outcome(
                status = "erro: sem resposta final",
                result = "O agente não produziu uma resposta final. Últimos passos: " +
                    turnMessages.takeLast(MAX_STEPS_IN_RESULT).joinToString(" | ") { it.role.name },
                transient = false,
            )

            turnMessages.any { it.role == Role.TOOL }.not() -> Outcome("ok", answer, transient = false)

            else -> Outcome("ok", answer, transient = false)
        }
    }

    /**
     * The result is the last assistant turn that asked for no tools — i.e. the
     * one where the model stopped working and reported back. Intermediate turns
     * are narration between tool calls, and a refused tool call produces no
     * assistant turn at all, so the loop can end on a tool result.
     */
    private fun finalAnswer(messages: List<ChatMessage>): String? = messages
        .lastOrNull { it.role == Role.ASSISTANT && it.toolCalls.isEmpty() && it.content.isNotBlank() }
        ?.content

    /**
     * Mirrors `ChatViewModel`'s registry, in the same order, so a prompt that
     * works interactively behaves identically on a schedule.
     */
    private fun toolRegistry() = ToolRegistry(
        listOf(
            // Filesystem and shell.
            ShellTool(),
            ReadFileTool(),
            WriteFileTool(),
            ListDirTool(),
            FetchUrlTool(),
            CalendarReadTool(),
            ContactsSearchTool(),
            // Device integration.
            DeviceInfoTool(),
            NotifyTool(),
            ClipboardReadTool(),
            ClipboardWriteTool(),
            OpenUrlTool(),
            ListAppsTool(),
        ),
    )

    private suspend fun recordWithoutRunning(store: ScheduledTaskStore, taskId: String?, status: String) {
        runCatching {
            val ids = if (taskId != null) listOf(taskId) else store.list().map { it.id }
            ids.forEach { store.recordRun(it, status, status) }
        }
    }

    /**
     * Successive runs of one task replace each other instead of stacking, so a
     * task that fires hourly does not leave a day of stale notifications.
     */
    private fun postOutcome(task: ScheduledTask, status: String, result: String) {
        runCatching {
            val body = result.ifBlank { status }
            TalosNotifications.post(
                context = applicationContext,
                id = notificationId(task.id),
                title = task.title.ifBlank { "Tarefa agendada" },
                body = "[$status] ${body.take(NOTIFICATION_BODY_CHARS)}",
            )
        }
    }

    private data class Outcome(val status: String, val result: String, val transient: Boolean)

    private companion object {
        /**
         * Lower than the interactive default (24): every extra step is an
         * unattended call the user is not watching, and a scheduled task that
         * needs two dozen tool calls is more likely stuck than productive.
         */
        const val UNATTENDED_MAX_STEPS = 16

        const val MAX_RETRY_ATTEMPTS = 3
        const val NOTIFICATION_BODY_CHARS = 180
        const val MAX_STEPS_IN_RESULT = 8
        const val ERROR_CHARS = 120

        /** Short, single-line label for the stored status field. */
        fun headline(message: String): String =
            message.replace(Regex("\\s+"), " ").trim().take(ERROR_CHARS)
    }
}

/**
 * Decides tool calls for a run nobody is watching.
 *
 * The interactive gate suspends on a `CompletableDeferred` until a human taps a
 * button. Reusing it here would park the worker until WorkManager's timeout and
 * then let a humanless run execute nothing — or worse, everything. So the policy
 * is baked in instead: do what is provably harmless, do the mildly risky things
 * only when the user opted this task into them, and refuse the rest outright.
 *
 * Refusal is not an error path: [AgentLoop] feeds the rejection back to the model
 * as a tool result, so the model can propose a safer route in the same run.
 */
private class UnattendedApprovalGate(
    private val allowSensitiveTools: Boolean,
) : ApprovalGate {

    override suspend fun request(request: ApprovalRequest): ApprovalDecision = when (request.risk) {
        // Read-only or otherwise reversible: safe to do with nobody looking.
        Risk.SAFE -> ApprovalDecision.APPROVE_ONCE

        // Shell commands, file writes, opening URLs, reading the clipboard.
        // These are the ones the user explicitly opted into per task.
        Risk.CAUTION -> if (allowSensitiveTools) {
            ApprovalDecision.APPROVE_ONCE
        } else {
            ApprovalDecision.REJECT
        }

        // No unattended path, not even with `allowSensitiveTools`. The flag is
        // there to let a task be useful, not to remove every boundary from
        // something the user cannot interrupt.
        Risk.DANGEROUS -> ApprovalDecision.REJECT
    }

    // Overridden only so a code search for the approval interface finds no
    // toString that could print arguments into a log.
    override fun toString(): String = "UnattendedApprovalGate(allowSensitiveTools=$allowSensitiveTools)"
}

/**
 * Puts the schedule into WorkManager, or takes it out.
 *
 * WorkManager — not `AlarmManager` — because these runs need "roughly every
 * hour, when there is a network, even across doze and reboots", which is exactly
 * the contract WorkManager already implements as a battery-optimised scheduler.
 */
object ScheduleScheduler {

    const val PERIODIC_WORK_NAME = "talos_scheduled_tasks"
    const val RUN_NOW_WORK_NAME = "talos_run_now"
    const val EXTRA_TASK_ID = "task_id"

    /** Enqueue the periodic worker when at least one task is enabled, cancel it otherwise. */
    fun sync(context: Context, tasks: List<ScheduledTask>) {
        val manager = WorkManager.getInstance(context)

        if (tasks.none { it.enabled }) {
            manager.cancelUniqueWork(PERIODIC_WORK_NAME)
            return
        }

        // 15 minutes is WorkManager's hard floor for periodic work: it will not
        // accept a shorter interval, and the OS may delay execution further for
        // doze and battery. This is the tick *granularity*, not the schedule —
        // each task's own intervalMinutes is enforced by ScheduledTask.isDue, so
        // a 6-hour task simply skips most ticks.
        val request = PeriodicWorkRequestBuilder<ScheduledAgentWorker>(15, TimeUnit.MINUTES)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .build()

        // KEEP, not UPDATE. The request's shape is identical on every call, and
        // `sync` runs on every app start and every edit. UPDATE would re-register
        // the work and push the next run a full interval away, so launching the
        // app twice would starve the schedule; KEEP lets an unchanged (or already
        // changed) task list ride the already-pending window.
        manager.enqueueUniquePeriodicWork(
            PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    /** Enqueue a one-shot run for a single task, replacing any pending one-shot. */
    fun runNow(context: Context, taskId: String) {
        val request = OneTimeWorkRequestBuilder<ScheduledAgentWorker>()
            .setInputData(
                Data.Builder()
                    .putString(EXTRA_TASK_ID, taskId)
                    .build(),
            )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .build()

        // REPLACE: the user pressing "run now" again wants this run, not a queue
        // of identical ones. The unique name is shared, so this also drops a
        // previous one-shot that has not started yet.
        WorkManager.getInstance(context).enqueueUniqueWork(
            RUN_NOW_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }
}

/**
 * Derives a stable per-task notification id: the same task always replaces its
 * own notification, and the mask keeps the value inside the positive int range
 * `notify` expects (`hashCode` can be negative).
 */
internal fun notificationId(taskId: String): Int = (taskId.hashCode() and 0x7FFF_FFFF) + 1

/**
 * True when the message reads like a network hiccup rather than a bad key or a
 * bad request. [AgentLoop] collapses `StreamEvent.Failed` into a plain string, so
 * the `retryable` flag `DeepSeekClient` already computed is not visible here —
 * the phrases below are what is left to work with. A real fix would carry the
 * flag through `AgentEvent.Failed`; inference is the fallback.
 */
private fun looksTransient(message: String): Boolean {
    val text = message.lowercase()
    return listOf(
        "timeout",
        "timed out",
        "de rede",
        "socket",
        "unreachable",
        "connection",
        "conexão",
        "reset",
        "unable to resolve",
        "host",
        "503",
        "502",
        "504",
        "429",
    ).any { text.contains(it) }
}

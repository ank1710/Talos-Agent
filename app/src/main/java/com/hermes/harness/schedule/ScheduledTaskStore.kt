package com.hermes.harness.schedule

import com.hermes.harness.core.HermesJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import java.io.File

/**
 * The schedule on disk: one `tasks.json` array under [root].
 *
 * A single file, unlike [com.hermes.harness.store.ConversationStore]'s
 * file-per-record layout. The two stores optimise for different things: a
 * conversation is large, written constantly, and only ever read one at a time,
 * so isolating it limits the blast radius of a corrupt write. The schedule is
 * small, read as a whole every time, and the worker and the UI both need a
 * consistent view of *all* of it — an array in one file gives that atomically.
 *
 * No public method throws: a scheduler that crashes on unreadable state would
 * stop running the user's tasks, which is worse than skipping one pass.
 */
class ScheduledTaskStore(root: File) {

    private val dir: File = File(root, "schedule").apply { mkdirs() }
    private val file: File = File(dir, "tasks.json")
    private val mutex = Mutex()

    suspend fun list(): List<ScheduledTask> = withContext(Dispatchers.IO) {
        mutex.withLock { readTasks() }
    }

    /**
     * Inserts [task] or replaces the stored task with the same id.
     *
     * The read-modify-write runs inside one locked section because the worker
     * records outcomes while the UI may be saving edits; doing it in two lock
     * acquisitions would let one overwrite the other.
     */
    suspend fun upsert(task: ScheduledTask) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val current = readTasks()
            val next = if (current.any { it.id == task.id }) {
                current.map { if (it.id == task.id) task else it }
            } else {
                current + task
            }
            writeTasks(next)
        }
        Unit
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            writeTasks(readTasks().filterNot { it.id == id })
        }
        Unit
    }

    /**
     * Notes the outcome of one run.
     *
     * This is the only writer that touches `lastRunAt`; [upsert] replaces the
     * whole task, so a UI save that races a run could resurrect a stale status —
     * the editor is expected to re-read before saving.
     */
    suspend fun recordRun(id: String, status: String, result: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val now = System.currentTimeMillis()
            val next = readTasks().map { task ->
                if (task.id != id) {
                    task
                } else {
                    task.copy(
                        lastRunAt = now,
                        lastStatus = status,
                        // Capped so a chatty or looping run cannot grow the
                        // single shared JSON file without bound.
                        lastResult = result.take(MAX_RESULT_CHARS),
                    )
                }
            }
            writeTasks(next)
        }
        Unit
    }

    /** A missing file is the normal first-run state, not an error. */
    private fun readTasks(): List<ScheduledTask> {
        if (!file.exists()) return emptyList()
        return runCatching {
            HermesJson.decodeFromString(ListSerializer(ScheduledTask.serializer()), file.readText())
        }.getOrDefault(emptyList())
    }

    private fun writeTasks(tasks: List<ScheduledTask>) {
        runCatching {
            dir.mkdirs()
            file.writeText(HermesJson.encodeToString(ListSerializer(ScheduledTask.serializer()), tasks))
        }
        Unit
    }

    private companion object {
        /**
         * Also bounded by [recordRun]'s caller; enforced here so a future caller
         * cannot forget and blow up the file.
         */
        const val MAX_RESULT_CHARS = 2_000
    }
}

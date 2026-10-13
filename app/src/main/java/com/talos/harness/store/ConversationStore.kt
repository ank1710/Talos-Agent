package com.talos.harness.store

import com.talos.harness.agent.Conversation
import com.talos.harness.core.TalosJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import kotlinx.serialization.Serializable
import java.io.File

@Serializable
data class ConversationSummary(
    val id: String,
    val title: String,
    val updatedAt: Long,
    val messageCount: Int,
)

data class SessionMatch(val conversationId: String, val title: String, val date: Long, val role: String, val excerpt: String)
data class SessionSearchPage(val matches: List<SessionMatch>, val nextOffset: Int?, val skipped: Int)

/**
 * Conversations on disk: one JSON file each, plus a small index for the list.
 *
 * Deliberately not a database. History is append-mostly, per-conversation files
 * keep writes cheap and make a corrupt conversation a local problem instead of a
 * global one, and the index means the drawer never parses every transcript.
 */
class ConversationStore(root: File) {

    private val dir: File = File(root, "conversations").apply { mkdirs() }
    private val indexFile: File = File(dir, "_index.json")
    private val mutex = Mutex()

    suspend fun list(): List<ConversationSummary> = withContext(Dispatchers.IO) {
        mutex.withLock { readIndex().sortedByDescending { it.updatedAt } }
    }

    suspend fun load(id: String): Conversation? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val file = File(dir, "$id.json")
            if (!file.exists()) return@withLock null
            runCatching {
                TalosJson.decodeFromString(Conversation.serializer(), file.readText())
            }.getOrNull()
        }
    }

    suspend fun save(conversation: Conversation) = withContext(Dispatchers.IO) {
        mutex.withLock {
            runCatching {
                File(dir, "${conversation.id}.json")
                    .writeText(TalosJson.encodeToString(Conversation.serializer(), conversation))

                val summary = ConversationSummary(
                    id = conversation.id,
                    title = conversation.title,
                    updatedAt = conversation.updatedAt,
                    messageCount = conversation.messages.size,
                )
                writeIndex(readIndex().filterNot { it.id == conversation.id } + summary)
            }
        }
        Unit
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            runCatching {
                File(dir, "$id.json").takeIf { it.exists() }?.delete()
                writeIndex(readIndex().filterNot { it.id == id })
            }
        }
        Unit
    }

    /** Read-only literal search: historical roles are labels, never replayed as messages. */
    suspend fun search(query: String, after: Long?, before: Long?, offset: Int): SessionSearchPage =
        withContext(Dispatchers.IO) {
            require(query.isNotBlank() && query.length <= 200) { "Busca deve ter de 1 a 200 caracteres." }
            mutex.withLock {
                val files = readIndex().sortedByDescending { it.updatedAt }.drop(offset).take(100)
                val matches = mutableListOf<SessionMatch>()
                var scanned = 0
                var skipped = 0
                for (summary in files) {
                    coroutineContext.ensureActive()
                    scanned++
                    val file = File(dir, "${summary.id}.json")
                    // A hand-edited index cannot turn history search into an arbitrary file reader.
                    if (!summary.id.matches(Regex("[a-zA-Z0-9_-]{1,80}")) ||
                        file.canonicalFile.parentFile != dir.canonicalFile || file.length() > 4 * 1024 * 1024
                    ) { skipped++; continue }
                    val conversation = runCatching {
                        TalosJson.decodeFromString(Conversation.serializer(), file.readText())
                    }.getOrNull()
                    if (conversation == null) { skipped++; continue }
                    // At most one match per conversation keeps pagination stable and diverse.
                    val message = conversation.messages.firstOrNull {
                        it.role != com.talos.harness.agent.Role.SYSTEM && it.content.isNotBlank() &&
                            (after == null || it.createdAt >= after) && (before == null || it.createdAt < before) &&
                            (it.content.contains(query, ignoreCase = true) || conversation.title.contains(query, ignoreCase = true))
                    } ?: continue
                    val position = message.content.indexOf(query, ignoreCase = true).coerceAtLeast(0)
                    val start = (position - 120).coerceAtLeast(0)
                    val end = (start + 480).coerceAtMost(message.content.length)
                    val excerpt = (if (start > 0) "…" else "") + message.content.substring(start, end) +
                        (if (end < message.content.length) "…" else "")
                    matches += SessionMatch(summary.id, conversation.title.take(120), message.createdAt, message.role.name, excerpt)
                    if (matches.size == 5) break
                }
                val next = offset + scanned
                SessionSearchPage(matches, next.takeIf { it < readIndex().size }, skipped)
            }
        }

    private fun readIndex(): List<ConversationSummary> {
        if (!indexFile.exists()) return emptyList()
        return runCatching {
            TalosJson.decodeFromString(
                kotlinx.serialization.builtins.ListSerializer(ConversationSummary.serializer()),
                indexFile.readText(),
            )
        }.getOrDefault(emptyList())
    }

    private fun writeIndex(entries: List<ConversationSummary>) {
        runCatching {
            indexFile.writeText(
                TalosJson.encodeToString(
                    kotlinx.serialization.builtins.ListSerializer(ConversationSummary.serializer()),
                    entries,
                ),
            )
        }
    }
}

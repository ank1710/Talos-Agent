package com.talos.harness.store

import com.talos.harness.agent.Conversation
import com.talos.harness.core.TalosJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File

@Serializable
data class ConversationSummary(
    val id: String,
    val title: String,
    val updatedAt: Long,
    val messageCount: Int,
)

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

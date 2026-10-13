package com.talos.harness.store

import java.io.File
import java.util.UUID

enum class MemoryKind(val value: String, val label: String) {
    USER_DECLARED("user_declared", "Declarado pelo usuário"),
    AGENT_INFERRED("agent_inferred", "Inferido pelo agente");

    companion object {
        // Missing metadata predates provenance tracking; it must never become a user assertion.
        fun parse(value: String?): MemoryKind = if (value == null) AGENT_INFERRED else
            entries.firstOrNull { it.value == value } ?: throw IllegalArgumentException("Classificação de memória inválida.")
    }
}

data class MemoryEntry(
    val id: String, val text: String, val createdAt: String, val updatedAt: String, val origin: String,
    val kind: MemoryKind = MemoryKind.AGENT_INFERRED,
)

/** Kept outside the file tools' workspace; all agent writes use the approval-gated tool. */
class MemoryStore(root: File) {
    private val records = PlainTextStore(root, "memory", MAX_TEXT)
    val directory: String get() = records.path
    suspend fun list(): List<MemoryEntry> = records.list().map(::entry)
    suspend fun save(text: String, origin: String, kind: MemoryKind = MemoryKind.AGENT_INFERRED): MemoryEntry =
        entry(records.save(UUID.randomUUID().toString(), "", text, origin, create = true, memoryKind = kind))
    suspend fun edit(id: String, text: String, kind: MemoryKind): MemoryEntry =
        entry(records.save(id, "", text, "usuário: ajustes", create = false, memoryKind = kind))
    suspend fun forget(id: String) = records.delete(id)
    private fun entry(it: TextRecord) = MemoryEntry(it.id, it.body, it.created, it.updated, it.origin, it.memoryKind ?: MemoryKind.AGENT_INFERRED)
    companion object { const val MAX_TEXT = 1000 }
}

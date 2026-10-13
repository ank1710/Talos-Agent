package com.talos.harness.store

import java.io.File
import java.util.UUID

data class MemoryEntry(val id: String, val text: String, val createdAt: String, val updatedAt: String, val origin: String)

/** Kept outside the file tools' workspace; all agent writes use the approval-gated tool. */
class MemoryStore(root: File) {
    private val records = PlainTextStore(root, "memory", MAX_TEXT)
    val directory: String get() = records.path
    suspend fun list(): List<MemoryEntry> = records.list().map(::entry)
    suspend fun save(text: String, origin: String): MemoryEntry =
        entry(records.save(UUID.randomUUID().toString(), "", text, origin, create = true))
    suspend fun edit(id: String, text: String): MemoryEntry =
        entry(records.save(id, "", text, "usuário: ajustes", create = false))
    suspend fun forget(id: String) = records.delete(id)
    private fun entry(it: TextRecord) = MemoryEntry(it.id, it.body, it.created, it.updated, it.origin)
    companion object { const val MAX_TEXT = 1000 }
}

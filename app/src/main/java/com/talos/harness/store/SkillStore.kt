package com.talos.harness.store

import java.io.File

data class SkillEntry(
    val name: String, val description: String, val steps: String,
    val createdAt: String, val updatedAt: String, val origin: String,
    val displayName: String = name,
)

/** A skill is a document, never a script, registry, policy or pre-approved tool call. */
class SkillStore(root: File) {
    private val records = PlainTextStore(root, "skills", MAX_STEPS)
    val directory: String get() = records.path
    suspend fun list(): List<SkillEntry> = records.list().map(::entry)
    suspend fun save(name: String, description: String, steps: String, origin: String, create: Boolean, displayName: String? = null): SkillEntry {
        require(description.isNotBlank()) { "Informe uma descrição." }
        return entry(records.save(name, description, steps, origin, create, displayName))
    }
    suspend fun delete(name: String) = records.delete(name)
    private fun entry(it: TextRecord) = SkillEntry(it.id, it.description, it.body, it.created, it.updated, it.origin, it.title)
    companion object { const val MAX_STEPS = 6000 }
}

/** Keep the human name independent of the stable filename used by tool calls. */
fun skillIdentifier(title: String): String = java.text.Normalizer.normalize(title.lowercase(java.util.Locale.ROOT), java.text.Normalizer.Form.NFD)
    .replace(Regex("\\p{M}+"), "").replace(Regex("[^a-z0-9]+"), "-").trim('-').take(64).trimEnd('-')

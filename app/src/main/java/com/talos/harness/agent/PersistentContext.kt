package com.talos.harness.agent

import com.talos.harness.store.MemoryEntry
import com.talos.harness.store.SkillEntry
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.text.Normalizer

/** No tokenizer is exposed by the backend. Charge one unit per UTF-8 byte, not chars/4.
 * This deliberately pessimistic budget includes JSON escaping and labels. It is a hard
 * byte ceiling, not a claim that all providers share a tokenizer or fit a whole turn.
 */
object PersistentContext {
    const val SMALL_SECTION_BUDGET = 96
    const val FULL_SECTION_BUDGET = 1024

    // Keep prompt size and tool availability on the same decision. Unknown windows retain the full catalogue.
    fun useCompact(onDevice: Boolean, model: String): Boolean {
        val window = Regex("ekv(\\d+)", RegexOption.IGNORE_CASE).find(model)
            ?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it > 0 }
        return onDevice && window != null && window < 4096
    }

    fun build(memories: List<MemoryEntry>, skills: List<SkillEntry>, query: String, compact: Boolean): String {
        val budget = if (compact) SMALL_SECTION_BUDGET else FULL_SECTION_BUDGET
        val terms = terms(query)
        val memory = memories.sortedWith(compareByDescending<MemoryEntry> { score(it.text, terms) }.thenByDescending { it.updatedAt })
        val skill = skills.sortedWith(compareByDescending<SkillEntry> { score(it.name + " " + it.displayName + " " + it.description, terms) }.thenByDescending { it.updatedAt })
        // Only facts and the skill catalogue are eligible; a skill body never reaches the system role.
        val facts = section("memory", memory.map { listOf(it.kind.value, flat(it.text)) }, budget)
        val catalogue = section("skills", skill.map { listOf(it.name, flat(it.description)) }, budget)
        return facts + catalogue
    }

    private fun section(key: String, candidates: List<List<String>>, budget: Int): String {
        val chosen = mutableListOf<JsonArray>()
        fun render(items: List<JsonArray>): String = "\n" + JsonObject(mapOf(key to JsonArray(items))).toString()
        for (candidate in candidates) {
            val fields = candidate.toMutableList()
            // Summarise by excerpt; preserve skill names exactly so lookup still works.
            val text = fields.last()
            fields[fields.lastIndex] = text.substring(0, text.offsetByCodePoints(0, minOf(160, text.codePointCount(0, text.length))))
            var item = JsonArray(fields.map(::JsonPrimitive))
            while (render(chosen + listOf(item)).toByteArray(Charsets.UTF_8).size > budget && fields.last().length > 8) {
                val text = fields.last().removeSuffix("…")
                fields[fields.lastIndex] = text.substring(0, text.offsetByCodePoints(text.length, -1)) + "…"
                item = JsonArray(fields.map(::JsonPrimitive))
            }
            if (render(chosen + listOf(item)).toByteArray(Charsets.UTF_8).size <= budget) chosen.add(item)
        }
        return if (chosen.isEmpty()) "" else render(chosen)
    }

    private fun flat(text: String) = text.replace(Regex("\\s+"), " ").trim()
    private fun terms(text: String): Set<String> = Normalizer.normalize(text.lowercase(java.util.Locale.ROOT), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").split(Regex("[^\\p{L}\\p{N}]+"))
        .filter { it.length >= 3 }.take(64).toSet()
    private fun score(text: String, query: Set<String>): Int = terms(text).count { it in query }
}

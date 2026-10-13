package com.talos.harness.store

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant

/** Fixed metadata followed by plain text: hand edits cannot introduce executable fields. */
internal data class TextRecord(
    val id: String,
    val created: String,
    val updated: String,
    val origin: String,
    val description: String,
    val body: String,
    val title: String = id,
    val memoryKind: MemoryKind? = null,
)

internal class PlainTextStore(private val root: File, directory: String, private val bodyLimit: Int) {
    private val dir = File(root, directory)
    // Shared across instances (UI and tools), so a read/modify/write cannot lose an edit.
    private companion object {
        val lock = Mutex()
        val ID = Regex("[a-z0-9][a-z0-9_-]{0,63}")
        const val MAX_RECORDS = 200
    }

    val path: String get() = dir.absolutePath

    suspend fun list(): List<TextRecord> = withContext(Dispatchers.IO) {
        lock.withLock { readAll() }
    }

    suspend fun save(id: String, description: String, body: String, origin: String, create: Boolean, title: String? = null,
                     memoryKind: MemoryKind? = null): TextRecord =
        withContext(Dispatchers.IO) {
            lock.withLock {
                require(body.isNotBlank() && body.length <= bodyLimit) { "Texto deve ter de 1 a $bodyLimit caracteres." }
                require(description.length <= 160 && '\n' !in description && '\r' !in description) {
                    "Descrição deve ter até 160 caracteres em uma linha."
                }
                require(origin.length <= 160 && '\n' !in origin && '\r' !in origin) { "Origem inválida." }
                dir.mkdirs()
                val file = file(id)
                val previous = if (file.exists()) read(file) else null
                require(if (create) previous == null else previous != null) {
                    if (create) "Nome já existe." else "Entrada não encontrada."
                }
                require(previous != null || readAll().size < MAX_RECORDS) { "Limite de $MAX_RECORDS entradas atingido." }
                val displayName = title ?: previous?.title ?: id
                require(displayName.isNotBlank() && displayName.length <= 80 && '\n' !in displayName && '\r' !in displayName) { "Nome deve ter até 80 caracteres em uma linha." }
                val now = Instant.now().toString()
                val record = TextRecord(id, previous?.created ?: now, now, origin, description.trim(), body.trim(), displayName.trim(), memoryKind)
                // Atomic replacement prevents process death from leaving half a remembered fact.
                val temporary = File(dir, "$id.tmp")
                require(temporary.canonicalFile == File(dir.canonicalFile, "$id.tmp")) { "Caminho inválido." }
                try {
                    temporary.outputStream().use { out ->
                        out.write(encode(record).toByteArray(Charsets.UTF_8))
                        out.fd.sync()
                    }
                    check(temporary.renameTo(file)) { "Não foi possível gravar a entrada." }
                } finally {
                    temporary.delete()
                }
                record
            }
        }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        lock.withLock {
            val file = file(id)
            require(file.exists()) { "Entrada não encontrada." }
            check(file.delete()) { "Não foi possível apagar a entrada." }
        }
    }

    private fun file(id: String): File {
        require(ID.matches(id)) { "Identificador inválido." }
        require(dir.canonicalFile.parentFile == root.canonicalFile) { "Diretório fora do armazenamento." }
        val file = File(dir, "$id.md")
        // Records live outside workspace; neither ids nor symlinks may escape their store.
        require(file.canonicalFile == File(dir.canonicalFile, "$id.md")) { "Links não são permitidos no armazenamento." }
        return file
    }

    private fun readAll(): List<TextRecord> {
        val files = dir.listFiles()?.filter { it.extension == "md" }.orEmpty()
        require(files.size <= MAX_RECORDS) { "Mais de $MAX_RECORDS arquivos em $path." }
        return files.map { read(file(it.nameWithoutExtension)) }.sortedByDescending { it.updated }
    }

    private fun read(file: File): TextRecord {
        require(file.length() <= bodyLimit * 4L + 2048) { "Arquivo muito grande: ${file.name}" }
        val parts = file.readText().replace("\r\n", "\n").split("\n\n", limit = 2)
        require(parts.size == 2) { "Cabeçalho inválido: ${file.name}" }
        val metadata = parts[0].lines().associate {
            val pair = it.split(": ", limit = 2)
            require(pair.size == 2) { "Cabeçalho inválido: ${file.name}" }
            pair[0] to pair[1]
        }
        val created = Instant.parse(metadata.getValue("created_at")).toString()
        val updated = Instant.parse(metadata.getValue("updated_at")).toString()
        val origin = metadata.getValue("origin")
        val description = metadata.getValue("description").let { if (it == "-") "" else it }
        val title = metadata["title"] ?: file.nameWithoutExtension
        require(title.isNotBlank() && title.length <= 80) { "Nome inválido: ${file.name}" }
        val body = parts[1].trim()
        require(origin.length <= 160 && description.length <= 160 && body.isNotBlank() && body.length <= bodyLimit) {
            "Conteúdo inválido: ${file.name}"
        }
        val memoryKind = metadata["memory_kind"]?.let(MemoryKind::parse)
        return TextRecord(file.nameWithoutExtension, created, updated, origin, description, body, title, memoryKind)
    }

    private fun encode(record: TextRecord): String =
        "created_at: ${record.created}\nupdated_at: ${record.updated}\norigin: ${record.origin}\n" +
            "description: ${record.description.ifEmpty { "-" }}\ntitle: ${record.title}\n" +
            (record.memoryKind?.let { "memory_kind: ${it.value}\n" } ?: "") + "\n${record.body}\n"
}

package com.talos.harness.tools

import com.talos.harness.agent.Risk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.io.File

/**
 * Resolves a model-supplied path inside the one directory exposed to file tools.
 *
 * Shared storage (`/sdcard`) and other app-private files remain unreachable:
 * fetched/shared prompt injection must not turn file tools into a path to secrets.
 */
internal fun resolvePath(ctx: ToolContext, raw: String): File {
    val root = ctx.workspace.canonicalFile
    val supplied = File(raw)
    val candidate = (if (supplied.isAbsolute) supplied else File(root, raw)).canonicalFile
    if (!isWithinWorkspace(root, candidate)) {
        throw SecurityException("Caminho fora da área de trabalho do agente")
    }
    return candidate
}

/** Both arguments are canonical; separator-aware comparison avoids prefix tricks. */
private fun isWithinWorkspace(root: File, candidate: File): Boolean =
    candidate.path == root.path || candidate.path.startsWith(root.path + File.separator)

/** Reads a text file, capped so a huge file cannot blow up the context window. */
class ReadFileTool : Tool {

    override val name = "read_file"

    override val description = """
        Lê um arquivo de texto e devolve o conteúdo com números de linha.
        Caminhos relativos são resolvidos dentro da área de trabalho do agente.
        Arquivos grandes são truncados; use 'offset' e 'limit' para paginar.
    """.trimIndent()

    override val parameters = Schema.obj(
        "path" to Schema.string("Caminho do arquivo dentro da área de trabalho do agente."),
        "offset" to Schema.integer("Número da primeira linha a ler (1-based). Padrão 1."),
        "limit" to Schema.integer("Quantidade máxima de linhas. Padrão $DEFAULT_LIMIT, máximo $MAX_LIMIT."),
        required = listOf("path"),
    )

    override val risk = Risk.CAUTION

    override fun preview(args: JsonObject): String =
        "Ler o arquivo ${Args.string(args, "path").orEmpty()}"

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult = withContext(Dispatchers.IO) {
        val raw = Args.string(args, "path") ?: return@withContext ToolResult.error("Parâmetro 'path' é obrigatório.")
        val file = runCatching { resolvePath(ctx, raw) }.getOrElse {
            return@withContext ToolResult.error("Caminho inválido ou fora da área de trabalho do agente.")
        }
        if (!file.exists()) return@withContext ToolResult.error("Arquivo não encontrado: ${file.path}")
        if (file.isDirectory) return@withContext ToolResult.error("${file.path} é um diretório, não um arquivo.")

        val offset = (Args.int(args, "offset") ?: 1).coerceAtLeast(1)
        val limit = (Args.int(args, "limit") ?: DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)

        runCatching {
            val lines = file.bufferedReader().useLines { sequence ->
                sequence.drop(offset - 1).take(limit).toList()
            }
            val total = file.useLines { it.count() }
            val lastLine = offset - 1 + lines.size
            val body = lines.mapIndexed { index, line -> "${offset + index}\t$line" }.joinToString("\n")
            val more = if (lastLine < total) "\n… restam ${total - lastLine} linhas." else ""
            ToolResult.ok("${file.path} ($total linhas)\n$body$more")
        }.getOrElse { ToolResult.error("Falha ao ler ${file.path}: ${it.message}") }
    }

    private companion object {
        const val DEFAULT_LIMIT = 400
        const val MAX_LIMIT = 2000
    }
}

/** Writes or appends to a text file, creating parent directories as needed. */
class WriteFileTool : Tool {

    override val name = "write_file"

    override val description = """
        Escreve texto em um arquivo, criando os diretórios intermediários se preciso.
        Por padrão substitui o arquivo inteiro; use append=true para adicionar ao final.
        Devolve quantos bytes foram gravados.
    """.trimIndent()

    override val parameters = Schema.obj(
        "path" to Schema.string("Caminho do arquivo dentro da área de trabalho do agente."),
        "content" to Schema.string("Conteúdo de texto a gravar."),
        "append" to Schema.boolean("Se true, adiciona ao final em vez de substituir. Padrão false."),
        required = listOf("path", "content"),
    )

    override val risk = Risk.CAUTION

    override fun preview(args: JsonObject): String {
        val path = Args.string(args, "path").orEmpty()
        val bytes = Args.string(args, "content")?.length ?: 0
        val append = Args.bool(args, "append") == true
        return "${if (append) "Acrescentar" else "Escrever"} $bytes caracteres em $path"
    }

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult = withContext(Dispatchers.IO) {
        val raw = Args.string(args, "path") ?: return@withContext ToolResult.error("Parâmetro 'path' é obrigatório.")
        val content = Args.string(args, "content")
            ?: return@withContext ToolResult.error("Parâmetro 'content' é obrigatório.")
        val append = Args.bool(args, "append") == true
        val file = runCatching { resolvePath(ctx, raw) }.getOrElse {
            return@withContext ToolResult.error("Caminho inválido ou fora da área de trabalho do agente.")
        }

        runCatching {
            file.parentFile?.mkdirs()
            // Re-resolve after creating parents so a newly introduced symlink
            // cannot redirect the write beyond the checked workspace root.
            val checked = resolvePath(ctx, raw)
            if (append) checked.appendText(content) else checked.writeText(content)
            ToolResult.ok("${if (append) "Acrescentados" else "Gravados"} ${content.length} caracteres em ${checked.path}")
        }.getOrElse { ToolResult.error("Falha ao escrever ${file.path}: ${it.message}") }
    }
}

/** Lists a directory so the agent can orient itself before reading files. */
class ListDirTool : Tool {

    override val name = "list_dir"

    /**
     * SAFE deliberately, not by omission.
     *
     * [resolvePath] confines every path to the agent's own workspace, so this
     * reads the sandbox that exists for the agent rather than the user's files —
     * and it reports entries that link outside the workspace instead of
     * following them. Approval here would fire constantly and train the user to
     * dismiss the dialog. read_file and write_file stay CAUTION because they
     * touch content.
     */
    override val risk = Risk.SAFE

    override val description = """
        Lista o conteúdo de um diretório com tipo e tamanho de cada entrada.
        Sem parâmetro, lista a área de trabalho do agente.
    """.trimIndent()

    override val parameters = Schema.obj(
        "path" to Schema.string("Diretório dentro da área de trabalho. Padrão: a raiz da área de trabalho."),
    )

    override fun preview(args: JsonObject): String =
        "Listar o diretório ${Args.string(args, "path") ?: "de trabalho"}"

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult = withContext(Dispatchers.IO) {
        val file = runCatching { resolvePath(ctx, Args.string(args, "path") ?: ".") }.getOrElse {
            return@withContext ToolResult.error("Caminho inválido ou fora da área de trabalho do agente.")
        }
        if (!file.exists()) return@withContext ToolResult.error("Diretório não encontrado: ${file.path}")
        if (!file.isDirectory) return@withContext ToolResult.error("${file.path} não é um diretório.")

        val entries = file.listFiles()?.sortedBy { it.name.lowercase() } ?: emptyList()

        val shown = entries.take(MAX_ENTRIES)
        val body = shown.joinToString("\n") { entry ->
            val resolved = runCatching { entry.canonicalFile }.getOrNull()
            if (resolved == null || !isWithinWorkspace(ctx.workspace.canonicalFile, resolved)) {
                "link  -  ${entry.name} (destino fora da área de trabalho)"
            } else {
                val kind = if (resolved.isDirectory) "dir " else "file"
                val size = if (resolved.isDirectory) "-" else humanSize(resolved.length())
                "$kind  $size  ${entry.name}"
            }
        }
        val more = if (entries.size > shown.size) "\n… e mais ${entries.size - shown.size} entradas." else ""
        ToolResult.ok("${file.path} (${entries.size} entradas)\n$body$more")
    }

    private fun humanSize(bytes: Long): String = when {
        bytes < 1024 -> "${bytes}B"
        bytes < 1024 * 1024 -> "${bytes / 1024}K"
        else -> "${bytes / (1024 * 1024)}M"
    }

    private companion object {
        const val MAX_ENTRIES = 200
    }
}

package com.talos.harness.tools

import com.talos.harness.agent.Risk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Runs one command through Android's own `/system/bin/sh`.
 *
 * There is no interactive session: every call is a fresh process whose working
 * directory defaults to the agent workspace. Output is captured and capped so a
 * runaway command cannot flood the model's context.
 */
class ShellTool : Tool {

    override val name = "shell"

    override val description = """
        Executa um comando no shell do Android (/system/bin/sh) e devolve stdout e stderr juntos.
        Use para inspecionar o dispositivo, manipular arquivos, rodar scripts e checar o ambiente.
        Não existe shell interativo: cada chamada é um processo novo, então variáveis de ambiente
        e o diretório atual não persistem entre chamadas. Prefira comandos idempotentes.
    """.trimIndent()

    override val parameters = Schema.obj(
        "command" to Schema.string(
            "Comando shell completo. Pode usar pipes, redirecionamentos, && e aspas.",
        ),
        "timeout_seconds" to Schema.integer(
            "Tempo máximo de execução em segundos. Padrão $DEFAULT_TIMEOUT, máximo $MAX_TIMEOUT.",
        ),
        "workdir" to Schema.string(
            "Diretório de trabalho. Padrão: a área de trabalho do agente.",
        ),
        required = listOf("command"),
    )

    override val risk = Risk.CAUTION

    override fun preview(args: JsonObject): String {
        val command = Args.string(args, "command").orEmpty().trim()
        return if (command.isEmpty()) "Executar comando no shell" else "Rodar no shell do celular: $command"
    }

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        val command = Args.string(args, "command")?.trim()
            ?: return ToolResult.error("Parâmetro 'command' é obrigatório.")
        if (command.isEmpty()) return ToolResult.error("O comando está vazio.")

        val timeout = (Args.int(args, "timeout_seconds") ?: DEFAULT_TIMEOUT).coerceIn(1, MAX_TIMEOUT)
        val workdir = Args.string(args, "workdir")
            ?.let { File(it) }
            ?.takeIf { it.isDirectory }
            ?: ctx.workspace
        workdir.mkdirs()

        val process = try {
            ProcessBuilder("/system/bin/sh", "-c", command)
                .directory(workdir)
                .redirectErrorStream(true)
                .start()
        } catch (e: Exception) {
            return ToolResult.error("Não foi possível iniciar o processo: ${e.message}")
        }

        val output = StringBuilder()
        return withContext(Dispatchers.IO) {
            coroutineScope {
                val reader = launch {
                    runCatching {
                        process.inputStream.bufferedReader().forEachLine { line ->
                            if (output.length < MAX_OUTPUT_CHARS) {
                                output.append(line).append('\n')
                            }
                        }
                    }
                }

                val finished = process.waitFor(timeout.toLong(), TimeUnit.SECONDS)
                if (!finished) {
                    process.destroy()
                    if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly()
                }
                reader.join()

                val exit = if (finished) runCatching { process.exitValue() }.getOrDefault(-1) else -1
                val body = output.toString()
                val wasTruncated = body.length >= MAX_OUTPUT_CHARS

                val content = buildString {
                    if (!finished) appendLine("TEMPO ESGOTADO após ${timeout}s — o processo foi encerrado.")
                    appendLine("exit=$exit")
                    appendLine(if (body.isBlank()) "(sem saída)" else body.trimEnd())
                    if (wasTruncated) appendLine("… saída truncada em $MAX_OUTPUT_CHARS caracteres.")
                }

                ToolResult(content = content, isError = !finished || exit != 0)
            }
        }
    }

    private companion object {
        const val DEFAULT_TIMEOUT = 30
        const val MAX_TIMEOUT = 300
        const val MAX_OUTPUT_CHARS = 20_000
    }
}

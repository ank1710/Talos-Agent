package com.talos.harness.tools

import com.talos.harness.agent.AgentEvent
import com.talos.harness.agent.AgentLoop
import com.talos.harness.agent.ApprovalGate
import com.talos.harness.agent.ChatMessage
import com.talos.harness.agent.Risk
import com.talos.harness.agent.Role
import com.talos.harness.llm.ChatBackend
import com.talos.harness.llm.WireUsage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlin.coroutines.cancellation.CancellationException

/**
 * Builds the tool registry for one nesting depth. `depth == 0` is the agent the
 * user is talking to; every level below it is a subagent. The implementation is
 * recursive (depth N's registry contains a spawner that builds depth N+1), which
 * is why the two `SpawnSubagentTool` limits below exist — without them that
 * recursion would never terminate.
 */
typealias RegistryFactory = (Int) -> ToolRegistry

/**
 * Delegates one self-contained task to a child agent with its own context.
 *
 * The feature this implements is context isolation: the child starts from the
 * delegated task alone, burns its own (bounded) token budget on the messy part,
 * and hands back a short answer. The parent's transcript grows by one tool
 * result instead of by every search, file read and dead end the subtask took —
 * which is the only reason a parent should ever call this.
 */
class SpawnSubagentTool(
    /** How deep this instance sits: the root agent is 0, its subagents are 1. */
    private val depth: Int,
    /** Backend the *parent* is using: cloud, desktop gateway or on-device. */
    private val backend: ChatBackend,
    /** The parent's gate, passed straight through to the child. See the note in `run`. */
    private val approvals: ApprovalGate,
    private val toolContext: ToolContext,
    /** Builds the child's registry at `depth + 1`, i.e. one level further down. */
    private val registryFactory: RegistryFactory,
    private val apiKey: String,
    private val baseUrl: String,
    private val model: String,
    private val temperature: Double? = null,
    private val maxSteps: Int = CHILD_MAX_STEPS,
    private val timeoutMs: Long = CHILD_TIMEOUT_MS,
) : Tool {

    init {
        require(depth <= MAX_DEPTH) { "SpawnSubagentTool construída com profundidade $depth além do limite." }
    }

    override val name = "spawn_subagent"

    /**
     * The description is the whole adoption story, so it is explicit about when
     * *not* to call this: trivial work costs more as a subagent than as a direct
     * tool call, and anything that has to ask the user a question cannot be
     * delegated at all because the child has no user.
     */
    override val description = """
        Delega uma tarefa independente e autocontida a um agente filho que roda com
        contexto próprio e devolve apenas a resposta final. Use quando a subtarefa
        custaria muitas chamadas de ferramenta (varrer arquivos, ler vários
        documentos, pesquisar na web, comparar alternativas) e encheria o seu
        contexto de detalhes que você não precisa guardar depois.

        Não use para trabalho trivial — um comando único, uma leitura de arquivo ou
        uma pergunta simples saem mais baratos como chamada direta. Não use para
        nada que exija falar com o usuário: o filho não tem acesso à conversa, não
        pode perguntar nada e não vê os anexos que você recebeu. Você é responsável
        pelo resultado: confira o que ele devolveu antes de repassar ao usuário e
        nunca delegue a mesma tarefa duas vezes esperando respostas diferentes.
    """.trimIndent()

    override val parameters = Schema.obj(
        "task" to Schema.string(
            "O que o agente filho deve realizar, escrito no idioma do usuário. " +
                "Descreva o objetivo e o formato da resposta esperada, não os passos: " +
                "o filho decide como executar.",
        ),
        "context" to Schema.string(
            "Fatos que o filho precisa para começar (caminhos, nomes, decisões já tomadas). " +
                "Opcional: o filho não vê a conversa, então o que não estiver aqui nem na " +
                "tarefa não existe para ele.",
        ),
        required = listOf("task"),
    )

    // The spawner itself touches nothing: it makes a model call, and every
    // side-effecting thing the child then does goes through the child's own
    // approval-gated tools with this phone's real risk tiers.
    override val risk = Risk.SAFE

    /** The sheet shows the delegated task: that is what the user is paying for. */
    override fun preview(args: JsonObject): String {
        val task = Args.string(args, "task").orEmpty().trim()
        return if (task.isEmpty()) {
            "Delegar uma subtarefa a um agente filho."
        } else {
            buildString {
                appendLine("Delegar a um agente filho:")
                append(excerpt(task))
                if (depth > 0) append("\n(profundidade ${depth + 1} de $MAX_DEPTH)")
            }
        }
    }

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        // 1. No unbounded recursion. This is the load-bearing check of the whole
        //    feature: the child's registry is built below, and without this guard
        //    a model that can spawn a spawner will do exactly that, forever, at
        //    the user's expense. The refusal is phrased so the model gets an
        //    actionable instruction instead of an opaque failure.
        if (depth >= MAX_DEPTH) {
            return ToolResult.error(
                "ERRO: limite de subagentes aninhados atingido (profundidade máxima $MAX_DEPTH). " +
                    "Não delegue de novo: faça esta tarefa você mesmo, com as suas ferramentas.",
            )
        }

        val task = Args.string(args, "task")?.trim().orEmpty()
        if (task.isEmpty()) {
            return ToolResult.error("ERRO: o parâmetro 'task' é obrigatório e não pode ficar vazio.")
        }
        // Optional by design: it is how the parent unloads facts it does not want
        // to keep in its own transcript.
        val extraContext = Args.string(args, "context")?.trim().orEmpty()

        val startedAt = System.currentTimeMillis()
        val collected = ChildRun()
        var failure: String? = null

        // 2. Context isolation. The child gets one user message — the delegated
        //    task — plus a system prompt written for a worker, and nothing else.
        //    Handing it the parent's transcript would duplicate the very tokens
        //    delegation exists to save, and would let the child act on requests
        //    the user never made of it. Facts the child genuinely needs come
        //    through the explicit `context` argument, not through conversation.
        val childPrompt = childSystemPrompt(task, extraContext)
        val childHistory = listOf(
            ChatMessage(
                role = Role.USER,
                content = "Tarefa delegada: $task",
            ),
        )

        // 3. Approvals still apply. `approvals` is the *parent's* gate, so a
        //    child tool call of Risk.CAUTION or above parks on the same dialog the
        //    user already knows. This is the security-relevant property of the
        //    feature: a subagent must never become a way to run `shell` (or write
        //    a file, or fetch a URL) without the user confirming it, and it must
        //    not be able to widen its own permissions by nesting.
        val loop = AgentLoop(
            client = backend,
            // The child's registry is one level deeper, and therefore contains no
            // spawner at all once `depth + 1` reaches MAX_DEPTH.
            registry = registryFactory(depth + 1),
            approvals = approvals,
            toolContext = toolContext,
            // 4. Bounded cost. A delegated task runs while the user waits, and a
            //    child that inherits the parent's budget (24 steps) can block the
            //    parent's turn for the price of two turns. Fewer steps plus the
            //    timeout below keep one bad delegation from stalling the chat.
            maxSteps = maxSteps,
            // A subagent cannot see the parent's images, so there is nothing to
            // resolve; passing the parent's resolver would only mislead the model
            // into thinking attachments exist here.
            resolveImages = { emptyList() },
        )

        try {
            // The child runs inside this tool call, in the caller's coroutine:
            // cancelling the parent (stop button, view-model teardown, process
            // death) cancels the child with it. Launching it in a scope of its own
            // would leave an orphan turn burning tokens with nobody listening.
            withContext(Dispatchers.IO) {
                withTimeout(timeoutMs) {
                    loop.run(
                        apiKey = apiKey,
                        baseUrl = baseUrl,
                        model = model,
                        systemPrompt = childPrompt,
                        history = childHistory,
                        temperature = temperature,
                    ).collect { event ->
                        when (event) {
                            // Streamed deltas are the child's intermediate
                            // narration; TurnFinished carries the same text once
                            // the turn is settled, so only that is kept.
                            is AgentEvent.TextDelta -> Unit
                            is AgentEvent.ReasoningDelta -> Unit
                            is AgentEvent.TurnFinished -> {
                                collected.steps++
                                collected.turns += event.message
                            }
                            is AgentEvent.ToolFinished -> collected.toolResults++
                            // The child's spend is captured so the parent can judge
                            // whether the delegation paid for itself. Note that
                            // nothing here is re-emitted to the parent UI: a child
                            // streams no text, so a long delegation makes the parent
                            // turn look quiet rather than busy.
                            is AgentEvent.UsageReported -> collected.usage = event.usage
                            is AgentEvent.Failed -> failure = event.message
                            else -> Unit
                        }
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            // Only the timeout is turned into a result; a real cancellation must
            // keep propagating (see below) or the parent would read a cancelled
            // turn as a successful delegation.
            return ToolResult.error(
                "ERRO: o subagente passou de ${timeoutMs / 1000}s e foi interrompido. " +
                    "Divida a tarefa em partes menores ou faça você mesmo o que faltou.",
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            return ToolResult.error(
                "ERRO: o subagente falhou (${e.message ?: e::class.java.simpleName}). " +
                    "Tente de novo sem delegar ou reformule a tarefa.",
            )
        }

        val elapsedMs = System.currentTimeMillis() - startedAt
        val answer = finalAnswer(collected.turns)
        val cost = buildString {
            append("${collected.steps} passo(s)")
            if (collected.usage != null) append(", ${collected.usage!!.totalTokens} tokens")
            append(", ${elapsedMs / 1000}s")
        }

        // 5. A useful result, not a transcript dump. The child's internal turns
        //    are dropped on purpose: returning them would put back into the
        //    parent's context exactly the flood the delegation was meant to avoid.
        return when {
            failure != null -> ToolResult.error(
                "ERRO: o subagente não concluiu a tarefa: $failure\n" +
                    "(custo: $cost)\n" +
                    "Não repita a mesma delegação: prossiga você mesmo ou explique a falha ao usuário.",
            )

            // An empty tool result reads to the model as success, so silence has
            // to be reported as an explicit failure.
            answer == null -> ToolResult.error(
                "ERRO: o subagente terminou sem produzir resposta final " +
                    "(${collected.toolResults} resultado(s) de ferramenta, custo: $cost). " +
                    "Faça esta tarefa você mesmo.",
            )

            else -> ToolResult.ok(
                buildString {
                    appendLine("Resultado do subagente (custo: $cost):")
                    appendLine()
                    append(answer.take(MAX_RESULT_CHARS).trim())
                    if (answer.length > MAX_RESULT_CHARS) {
                        appendLine()
                        appendLine("… resposta truncada em $MAX_RESULT_CHARS caracteres.")
                    }
                },
            )
        }
    }

    /**
     * The last assistant turn that asked for no tools: that is the child
     * reporting back. Intermediate turns are narration between tool calls, and a
     * child that ends on a tool result has produced no answer at all — which the
     * caller must see as a failure rather than as an empty success.
     */
    private fun finalAnswer(turns: List<ChatMessage>): String? = turns
        .lastOrNull { it.role == Role.ASSISTANT && it.toolCalls.isEmpty() && it.content.isNotBlank() }
        ?.content
        ?.trim()

    /** What one child run accumulated. Mutable and local to a single `run`. */
    private class ChildRun {
        var steps: Int = 0
        var toolResults: Int = 0
        var usage: WireUsage? = null
        val turns: MutableList<ChatMessage> = mutableListOf()
    }

    companion object {
        /**
         * The root agent is depth 0 and may delegate once (depth 1); that
         * subagent may delegate once more (depth 2). At depth 2 the registry
         * contains no spawner at all, so the recursion is structurally finite —
         * this constant is the second, explicit belt to that braces.
         */
        const val MAX_DEPTH = 2

        /** Steps a child may take: half the interactive parent's default (24). */
        const val CHILD_MAX_STEPS = 12

        /** Hard ceiling on one delegation, measured across all of its steps. */
        const val CHILD_TIMEOUT_MS = 120_000L

        private const val MAX_RESULT_CHARS = 6_000
        private const val PREVIEW_CHARS = 220
        private const val CONTEXT_CHARS = 8_000

        /**
         * The child's own prompt, never the parent's.
         *
         * Two things are deliberate: it repeats the approval rule, because the
         * child also runs on this phone and a worker that does not know it is
         * sandboxed writes commands the user then has to refuse one by one; and
         * it forbids dumping raw tool output, because the only text of the child
         * that ever reaches the parent is its final answer — anything else is
         * silently lost work.
         */
        fun childSystemPrompt(task: String, extraContext: String): String = buildString {
            appendLine("Você é um subagente do Talos, um agente que roda dentro do celular Android do usuário.")
            appendLine("Você foi acionado pelo agente principal para resolver UMA tarefa delimitada.")
            appendLine()
            appendLine("# O que você recebeu")
            appendLine(task)
            if (extraContext.isNotBlank()) {
                appendLine()
                appendLine("# Contexto fornecido pelo agente principal")
                appendLine(extraContext.take(CONTEXT_CHARS))
            }
            appendLine()
            appendLine("# Como trabalhar")
            appendLine("- Você não tem acesso à conversa do agente principal nem ao usuário: não pergunte nada, não peça confirmação e não peça mais contexto. Decida com o que está aqui.")
            appendLine("- Use as ferramentas disponíveis em vez de responder de memória ou inventar.")
            appendLine("- Vá direto ao ponto: passos pequenos e verificáveis, sem repetir uma chamada que já falhou.")
            appendLine("- Responda no idioma da tarefa. O padrão é português do Brasil.")
            appendLine()
            appendLine("# Como responder")
            appendLine("- A sua resposta final é a única coisa que o agente principal recebe: ele não vê os seus passos nem a saída bruta das suas ferramentas.")
            appendLine("- Não narre o que você fez, não liste as ferramentas que usou e não descreva o seu processo.")
            appendLine("- Entregue o resultado já destilado para quem for usá-lo: o que foi descoberto, os números, os caminhos, o que não foi possível fazer e por quê.")
            appendLine("- Se não conseguiu concluir, diga exatamente o que faltou e qual foi o erro. Não invente resultado.")
            appendLine()
            appendLine("# Limites")
            appendLine(com.talos.harness.agent.SystemPrompt.UNTRUSTED_POLICY)
            appendLine("- Comandos de shell e escritas em disco passam por confirmação do usuário; se ele recusar, não insista: siga por outro caminho ou relate o bloqueio.")
        }

        /** Keeps a one-line-ish preview readable when the task is a paragraph. */
        private fun excerpt(text: String): String {
            val flat = text.replace(Regex("\\s+"), " ").trim()
            return if (flat.length <= PREVIEW_CHARS) flat else flat.take(PREVIEW_CHARS) + "…"
        }
    }
}

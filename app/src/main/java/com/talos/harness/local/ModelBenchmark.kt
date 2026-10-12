package com.talos.harness.local

import com.talos.harness.llm.ChatRequest
import com.talos.harness.llm.StreamEvent
import com.talos.harness.llm.WireMessage
import com.talos.harness.llm.textContent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.math.roundToInt

/**
 * What a measured model turned out to be, from the point of view of somebody
 * waiting for an answer.
 *
 * [SLOW] is not a failure: the model ran and produced text, but at a rate that
 * makes an agent turn — two to four model calls plus the tools between them —
 * take minutes instead of seconds. Keeping it apart from [OK] is the whole
 * point of measuring instead of predicting.
 */
enum class BenchmarkOutcome(val label: String) {
    OK("OK"),
    SLOW("Lento"),
    FAILED_TO_LOAD("Não carregou"),
    ERROR("Falhou"),
}

/**
 * Where [BenchmarkResult.approxTokensPerSecond] comes from.
 *
 * This exists so a speed figure can never reach the screen without its basis:
 * the UI renders [label] right next to the number, and it is part of the
 * result rather than something a caller has to remember to add.
 */
enum class SpeedBasis(val label: String) {
    KV_TOTAL(
        "no máximo: tokens no KV no fim da conversa (prefácio + prompt + resposta) ÷ tempo de geração",
    ),
    TEXT_ESTIMATE(
        "aproximado: caracteres gerados ÷ 4 (≈4 caracteres por token) ÷ tempo de geração",
    ),
    NONE("não foi possível medir o ritmo"),
}

/**
 * One model's measurement.
 *
 * [loadMs] and [generateMs] are separate because they fail separately and are
 * noticed separately: `Engine.initialize()` is the multi-second wait before the
 * first token (LiteRT-LM's own guide warns about ~10 s), while [generateMs] is
 * how long the answer took once the weights were in.
 */
data class BenchmarkResult(
    val modelName: String,
    val loadMs: Long,
    val generateMs: Long,
    val outputChars: Int,
    val approxTokensPerSecond: Double?,
    val outcome: BenchmarkOutcome,
    /** Portuguese, self-contained: numbers, basis and what happened to the previous model. */
    val detail: String,
    val speedBasis: SpeedBasis = SpeedBasis.NONE,
    /** Accelerator that actually initialised, which may be the CPU after a GPU fallback. */
    val backend: LocalBackend? = null,
    /** Time from "send" to the first streamed token, when one arrived. */
    val timeToFirstTokenMs: Long? = null,
    /** Output tokens the rate was computed from; see [speedBasis]. */
    val approxTokens: Int? = null,
)

/**
 * Measures whether one installed model actually runs, and how fast.
 *
 * The capability probe answers "does this fit in RAM?" from measured *memory*.
 * It cannot answer "is this usable?", and it is deliberately pessimistic: its
 * budget is half the free headroom because weights may be resident while the KV
 * cache and activations certainly are. A model it rejects may still run, either
 * because the weights are mmapped and evictable or because the probe's reserve
 * was conservative. Only running it settles that, which is what this does.
 *
 * What it does, in order:
 *  1. records whatever is loaded, then releases it, so the load is a real one
 *     and the benchmarked model gets the whole memory budget;
 *  2. times `ensureLoaded` — this is [BenchmarkResult.loadMs];
 *  3. streams one fixed prompt (see [BENCHMARK_PROMPT]) with a fixed output cap
 *     through [OnDeviceBackend.streamChat], so the harness, the fallback
 *     cascade and the sampler are exactly the ones a real turn uses
 *  4. times and counts the answer, derives a tokens/second figure **with its
 *     basis**, classifies the outcome;
 *  5. puts back whatever was loaded before — or leaves nothing loaded and says
 *     so.
 *
 * Callers must not run this while a chat turn is streaming. The generation
 * itself is serialised by [OnDeviceBackend]'s own lock, but the release/load
 * around it is not, and closing an engine that is mid-decode is not something a
 * benchmark should be able to do to the conversation. `ChatViewModel` refuses to
 * start one while `isRunning`, and joins the previous benchmark before starting
 * another.
 *
 * @param backend the engine that owns the model. It is taken as an instance
 *   rather than built here because the app keeps exactly one, and a second one
 *   would double the resident memory — the variable being measured.
 * @param accelerator which accelerator to prefer; the load path still falls
 *   back on its own, and [BenchmarkResult.backend] reports what it ended up on.
 */
suspend fun benchmark(
    model: ModelFile,
    backend: OnDeviceBackend,
    accelerator: LocalBackend = LocalBackend.GPU,
    onStage: (String) -> Unit = {},
): BenchmarkResult {
    val previousPath = backend.loadedModelPath
    val previousBackend = backend.activeBackend

    onStage("Descarregando o modelo atual…")
    backend.release()

    var restoreNote = ""
    val draft = try {
        measure(model, backend, accelerator, onStage)
    } finally {
        // The reload of the previous model can take seconds too, and the user is
        // still looking at the row: say what is happening.
        if (previousPath != null) onStage("Recarregando o modelo que estava em uso…")
        // Runs on cancellation too, and must not throw: the result is already
        // decided by here and a failure to tidy up would replace it.
        restoreNote = restore(backend, previousPath, previousBackend)
    }
    return draft.result(restoreNote)
}

// ---------------------------------------------------------------------------
// Measurement
// ---------------------------------------------------------------------------

/**
 * The load and generation phases, ending in a [Draft] for every path — this
 * function never throws except on cancellation, because a benchmark that
 * crashes the app while measuring whether a model fits would be worse than no
 * benchmark at all.
 */
private suspend fun measure(
    model: ModelFile,
    backend: OnDeviceBackend,
    accelerator: LocalBackend,
    onStage: (String) -> Unit,
): Draft {
    onStage("Carregando ${model.name} (${model.sizeLabel})…")
    val loadStart = System.nanoTime()
    val loadFailure = try {
        // Cooperative: a native initialize() that never returns cannot be
        // interrupted, so this bounds a load that is merely slow, not one that
        // is wedged. The elapsed time is reported either way.
        withTimeout(LOAD_TIMEOUT_MS) { backend.ensureLoaded(model.path, accelerator) }
        null
    } catch (e: TimeoutCancellationException) {
        LoadTimeout
    } catch (e: CancellationException) {
        // A real cancellation (the user left, or stopped the measurement) is
        // not a benchmark outcome: it must keep unwinding.
        throw e
    } catch (e: Throwable) {
        e
    }
    val loadMs = elapsedMs(loadStart)

    if (loadFailure != null) {
        return Draft(
            model = model,
            loadMs = loadMs,
            outcome = BenchmarkOutcome.FAILED_TO_LOAD,
            reason = describeLoadFailure(loadFailure),
        )
    }

    val usedBackend = backend.activeBackend
    onStage("Carregado em ${formatDuration(loadMs)}. Gerando até $BENCHMARK_MAX_OUTPUT_TOKENS tokens…")
    val pass = generate(backend, model)

    if (pass.failure != null) {
        return Draft(
            model = model,
            loadMs = loadMs,
            generateMs = pass.durationMs,
            outputChars = pass.chars,
            backend = usedBackend,
            outcome = BenchmarkOutcome.ERROR,
            reason = "Carregou, mas a geração falhou: ${pass.failure}",
        )
    }

    if (pass.timedOut) {
        val producedSomething = pass.chars > 0
        return Draft(
            model = model,
            loadMs = loadMs,
            generateMs = pass.durationMs,
            outputChars = pass.chars,
            backend = usedBackend,
            timeToFirstTokenMs = pass.firstTokenMs,
            outcome = if (producedSomething) BenchmarkOutcome.SLOW else BenchmarkOutcome.ERROR,
            reason = "Não terminou em ${formatDuration(GENERATE_TIMEOUT_MS)}: " +
                if (producedSomething) {
                    "gerou ${pass.chars} caracteres antes de ser interrompido"
                } else {
                    "não produziu nenhum texto dentro do limite"
                },
        )
    }

    if (pass.chars == 0) {
        return Draft(
            model = model,
            loadMs = loadMs,
            generateMs = pass.durationMs,
            backend = usedBackend,
            outcome = BenchmarkOutcome.ERROR,
            reason = "Carregou, mas terminou sem produzir texto",
        )
    }

    val speed = deriveSpeed(
        kvTokens = pass.kvTokens,
        promptChars = BENCHMARK_PROMPT.length,
        outputChars = pass.chars,
        generateMs = pass.durationMs,
    )
    val slowBecause = when {
        // An upper bound below the floor is a proof of slowness: the real rate
        // can only be lower than the ceiling.
        speed.rate != null && speed.rate < SLOW_TOKENS_PER_SECOND ->
            "o ritmo medido (${formatTokensPerSecond(speed.rate)} tokens/s) fica abaixo de " +
                "${formatTokensPerSecond(SLOW_TOKENS_PER_SECOND)} tokens/s"

        loadMs > SLOW_LOAD_MS ->
            "levou ${formatDuration(loadMs)} para carregar, acima de ${formatDuration(SLOW_LOAD_MS)}"

        else -> null
    }

    return Draft(
        model = model,
        loadMs = loadMs,
        generateMs = pass.durationMs,
        outputChars = pass.chars,
        speed = speed,
        backend = usedBackend,
        timeToFirstTokenMs = pass.firstTokenMs,
        outcome = if (slowBecause == null) BenchmarkOutcome.OK else BenchmarkOutcome.SLOW,
        reason = if (slowBecause == null) {
            "Rodou"
        } else {
            "Rodou, mas devagar: $slowBecause"
        },
    )
}

/**
 * One generation through the same seam a real turn uses.
 *
 * `streamChat` resolves the model from `ChatRequest.model` through
 * [ModelStore], creates a **fresh** conversation per call and closes it
 * afterwards, so the KV cache this reads is that conversation's own and the
 * next call starts from zero. That is what makes the KV reading attributable to
 * this generation rather than to whatever ran before it.
 */
private suspend fun generate(backend: OnDeviceBackend, model: ModelFile): Pass {
    val request = ChatRequest(
        model = model.name,
        messages = listOf(WireMessage(role = ROLE_USER, content = textContent(BENCHMARK_PROMPT))),
        // Greedy: two runs of the same benchmark should decode the same number
        // of tokens, otherwise a chatty run would look slower than a terse one.
        temperature = BENCHMARK_TEMPERATURE,
        maxTokens = BENCHMARK_MAX_OUTPUT_TOKENS,
    )

    var chars = 0
    var kvTokens: Int? = null
    var failure: String? = null
    var firstTokenMs: Long? = null
    val start = System.nanoTime()

    val finished = withTimeoutOrNull(GENERATE_TIMEOUT_MS) {
        backend.streamChat(apiKey = "", baseUrl = "", request = request).collect { event ->
            when (event) {
                is StreamEvent.Content -> {
                    if (event.text.isNotEmpty() && firstTokenMs == null) {
                        firstTokenMs = elapsedMs(start)
                    }
                    chars += event.text.length
                }

                // A thinking model emits reasoning before the answer; either one
                // is the first thing the user sees.
                is StreamEvent.Reasoning -> if (event.text.isNotEmpty() && firstTokenMs == null) {
                    firstTokenMs = elapsedMs(start)
                }

                // KV-cache occupancy after the turn, the only token count
                // LiteRT-LM exposes here. It has no prompt/completion split;
                // [deriveSpeed] says what that costs.
                is StreamEvent.Usage -> kvTokens = event.usage.totalTokens.takeIf { it > 0 }

                is StreamEvent.Failed -> failure = event.message

                else -> Unit
            }
        }
    } != null

    return Pass(
        chars = chars,
        kvTokens = kvTokens,
        durationMs = elapsedMs(start),
        firstTokenMs = firstTokenMs,
        failure = failure,
        timedOut = !finished,
    )
}

/**
 * Turns the raw readings into a rate, or into `null` when no honest rate exists.
 *
 * LiteRT-LM exposes exactly one token count and it is KV-cache occupancy: it
 * mixes the model's preface, the prompt and the answer, and there is no
 * prompt/completion split anywhere in the API. So a precise decode rate is not
 * available, and the two things this can do are both labelled in [SpeedBasis]:
 *
 *  - [SpeedBasis.KV_TOTAL] uses the occupancy as the numerator. It counts the
 *    prompt as if it had been decoded, and the denominator is the whole
 *    generation including prefill; both push the figure **up**, so it is
 *    published as a ceiling, never as a rate. It is still useful: a ceiling
 *    below the usable floor proves the model is too slow.
 *  - [SpeedBasis.TEXT_ESTIMATE] counts the characters that actually arrived and
 *    divides by an assumed 4 characters per token. It ignores the prompt, so it
 *    is closer to a real rate at the cost of a heuristic ratio.
 *
 * The occupancy is only trusted when it is consistent with the text that
 * arrived. A token is a non-empty string, so the tokens of prompt+answer cannot
 * outnumber their characters (plus a small allowance for the model's own chat
 * template, which is not visible here); and a phone cannot decode a 4-bit LLM at
 * hundreds of tokens per second. A reading that fails either check is a
 * measurement artifact — `getTokenCount()` returning the window size, for
 * instance — and is discarded rather than published.
 */
private fun deriveSpeed(
    kvTokens: Int?,
    promptChars: Int,
    outputChars: Int,
    generateMs: Long,
): Speed {
    if (generateMs <= 0) return Speed(null, SpeedBasis.NONE, null)

    val kv = kvTokens
    if (kv != null &&
        kv > 0 &&
        kv <= promptChars + outputChars + PREFACE_TOKEN_ALLOWANCE &&
        kv * 1000.0 / generateMs <= MAX_PLAUSIBLE_TOKENS_PER_SECOND
    ) {
        return Speed(kv * 1000.0 / generateMs, SpeedBasis.KV_TOTAL, kv)
    }

    if (outputChars >= MIN_CHARS_FOR_ESTIMATE) {
        val tokens = (outputChars / CHARS_PER_TOKEN_ESTIMATE).roundToInt()
        if (tokens > 0 && tokens * 1000.0 / generateMs <= MAX_PLAUSIBLE_TOKENS_PER_SECOND) {
            return Speed(tokens * 1000.0 / generateMs, SpeedBasis.TEXT_ESTIMATE, tokens)
        }
    }

    return Speed(null, SpeedBasis.NONE, null)
}

// ---------------------------------------------------------------------------
// Putting things back
// ---------------------------------------------------------------------------

/**
 * Restores the engine to what it was before the measurement, and describes what
 * it did in Portuguese for [BenchmarkResult.detail].
 *
 * A benchmark must never silently change which model serves the conversation.
 * The three honest end states are: the previous model is loaded again, nothing
 * was loaded before so nothing is loaded now, or the measurement was cancelled
 * and the engine is left released for the next turn to fill in.
 */
private suspend fun restore(
    backend: OnDeviceBackend,
    previousPath: String?,
    previousBackend: LocalBackend?,
): String {
    // On cancellation there is nobody left to wait for a multi-second native
    // load, so the engine is dropped instead. ChatViewModel's preload reloads
    // the right model before the next turn streams.
    if (!currentCoroutineContext().isActive) {
        backend.release()
        return "Medição cancelada: o modelo foi descarregado e volta a carregar na próxima mensagem."
    }

    if (previousPath == null) {
        backend.release()
        return "Nada estava carregado antes; o modelo medido foi descarregado."
    }

    // NonCancellable: a cancellation that arrives while the previous model is
    // being reloaded would otherwise abandon the engine half-way and leave the
    // conversation with a model it did not choose.
    val reloaded = withContext(NonCancellable) {
        runCatching { backend.ensureLoaded(previousPath, previousBackend ?: DEFAULT_ACCELERATOR) }
    }
    return if (reloaded.isSuccess) {
        "O modelo que estava em uso voltou a ser carregado."
    } else {
        "Nada está carregado agora: não consegui recarregar $previousPath " +
            "(${reasonOf(reloaded.exceptionOrNull())}). A próxima mensagem tenta de novo."
    }
}

// ---------------------------------------------------------------------------
// Internals
// ---------------------------------------------------------------------------

/** One finished generation, as read from the outside. */
private class Pass(
    val chars: Int,
    val kvTokens: Int?,
    val durationMs: Long,
    val firstTokenMs: Long?,
    val failure: String?,
    val timedOut: Boolean,
)

/** A rate and the basis it may be published under; `null` rate means "not measured". */
private class Speed(
    val rate: Double?,
    val basis: SpeedBasis,
    val tokens: Int?,
)

/**
 * Everything [measure] learned, before [restore] has run. The detail string is
 * composed in [result] so it can mention how the previous model was put back.
 */
private class Draft(
    val model: ModelFile,
    val loadMs: Long,
    val generateMs: Long = 0L,
    val outputChars: Int = 0,
    val speed: Speed = Speed(null, SpeedBasis.NONE, null),
    val outcome: BenchmarkOutcome,
    val reason: String,
    val backend: LocalBackend? = null,
    val timeToFirstTokenMs: Long? = null,
) {
    /**
     * The user-facing sentence. Numbers, basis and the restore note all live
     * here so a caller that shows only [BenchmarkResult.detail] still says what
     * the speed figure means.
     */
    fun result(restoreNote: String): BenchmarkResult {
        val numbers = buildString {
            // A load failure never got as far as generating, so it says how long
            // it waited instead of pretending the model "loaded".
            if (generateMs > 0) {
                append("Carregou em ")
                append(formatDuration(loadMs))
                if (backend != null) {
                    append(" (")
                    append(backend.name)
                    append(")")
                }
                append("; gerou ")
                append(outputChars)
                append(" caracteres em ")
                append(formatDuration(generateMs))
            } else {
                append("Levou ")
                append(formatDuration(loadMs))
                append(" tentando carregar")
            }
            if (timeToFirstTokenMs != null) {
                append(", o primeiro em ")
                append(formatDuration(timeToFirstTokenMs))
            }
            append(".")
        }
        val rate = speed.rate
        val speedLine = if (rate != null) {
            "Ritmo: ${formatTokensPerSecond(rate)} tokens/s — ${speed.basis.label}."
        } else {
            "Ritmo: não medido (${speed.basis.label})."
        }

        return BenchmarkResult(
            modelName = model.name,
            loadMs = loadMs,
            generateMs = generateMs,
            outputChars = outputChars,
            approxTokensPerSecond = speed.rate,
            outcome = outcome,
            detail = listOf(sentence(reason), numbers, speedLine, restoreNote)
                .filter { it.isNotBlank() }
                .joinToString(" "),
            speedBasis = speed.basis,
            backend = backend,
            timeToFirstTokenMs = timeToFirstTokenMs,
            approxTokens = speed.tokens,
        )
    }
}

/** One sentence, with exactly one full stop: reasons come from several places. */
private fun sentence(text: String): String {
    val trimmed = text.trim()
    return if (trimmed.isEmpty() || trimmed.last() in ".!?") trimmed else "$trimmed."
}

private fun describeLoadFailure(failure: Throwable): String = when {
    failure === LoadTimeout ->
        "Não terminou de carregar dentro do limite de ${formatDuration(LOAD_TIMEOUT_MS)}"

    failure is OutOfMemoryError ->
        "Faltou memória para carregar: o sistema mataria o app antes de o modelo rodar"

    else -> "Não foi possível carregar: ${reasonOf(failure)}"
}

private fun reasonOf(failure: Throwable?): String =
    failure?.message ?: failure?.let { it::class.simpleName } ?: "erro desconhecido"

private fun elapsedMs(startNanos: Long): Long = (System.nanoTime() - startNanos) / 1_000_000

/**
 * "2,4 s" / "840 ms" in pt-BR, where the decimal separator is a comma.
 *
 * Shared with the settings screen so a duration never appears in two formats,
 * the same way [formatBytes] is shared for sizes.
 */
internal fun formatDuration(ms: Long): String {
    val locale = Locale.forLanguageTag("pt-BR")
    return when {
        ms >= 60_000L -> java.lang.String.format(locale, "%.1f min", ms / 60_000.0)
        ms >= 1_000L -> java.lang.String.format(locale, "%.1f s", ms / 1_000.0)
        else -> "$ms ms"
    }
}

/** One decimal, comma-separated, to match the rest of the Portuguese UI. */
internal fun formatTokensPerSecond(value: Double): String =
    java.lang.String.format(Locale.forLanguageTag("pt-BR"), "%.1f", value)

/**
 * The prompt every measurement uses, deliberately fixed.
 *
 * Why *this* text:
 *  - it is one short sentence, so the prompt's share of the KV occupancy — the
 *    numerator of [SpeedBasis.KV_TOTAL] — stays small next to the answer's;
 *  - it asks for an enumeration that cannot fit in the output cap, so a
 *    cooperative model decodes the whole cap. A model that stops after one word
 *    would be timed on a handful of tokens and the rate would be noise;
 *  - it needs no tools and no system prompt, so no tool schema is counted and
 *    the measurement is of the weights, not of a prompt format;
 *  - it is the same for every model, which is what makes two rows in the
 *    settings list comparable at all.
 */
private const val BENCHMARK_PROMPT = "Conte de 1 a 500, um número por linha, sem parar para explicar."

/** The fixed output cap. See [BENCHMARK_PROMPT]; it is what bounds a rambling model. */
private const val BENCHMARK_MAX_OUTPUT_TOKENS = 96

/** Greedy sampling, so two runs of the same model decode the same amount. */
private const val BENCHMARK_TEMPERATURE = 0.0

private const val ROLE_USER = "user"

/**
 * Below this, a local turn stops being interactive: an agent turn is two to
 * four model calls, so 60 tokens at 5 tokens/s is already 12 s per call and
 * roughly a minute per turn.
 */
private const val SLOW_TOKENS_PER_SECOND = 5.0

/**
 * LiteRT-LM's guide warns that `initialize()` can take ~10 s, which is why the
 * weights are loaded before the loop asks for the first token. Past 20 s the
 * first message of a session reads as a hang.
 */
private const val SLOW_LOAD_MS = 20_000L

/** Bounds a load that is slow; a wedged native call cannot be interrupted. */
private const val LOAD_TIMEOUT_MS = 240_000L

/** At the slow floor the 96-token cap takes ~20 s, so three minutes separates "very slow" from "stuck". */
private const val GENERATE_TIMEOUT_MS = 180_000L

/** No phone decodes a 4-bit LLM this fast; a higher reading is an artifact. */
private const val MAX_PLAUSIBLE_TOKENS_PER_SECOND = 200.0

/** Chat-template tokens that are neither prompt nor answer. See [deriveSpeed]. */
private const val PREFACE_TOKEN_ALLOWANCE = 48

/** Used only when the KV reading is unusable; crude on purpose, and labelled as such. */
private const val CHARS_PER_TOKEN_ESTIMATE = 4.0

/** Under this many characters a text-based estimate is noise, not a measurement. */
private const val MIN_CHARS_FOR_ESTIMATE = 40

/** Fallback accelerator when nothing was loaded before and no preference survived. */
private val DEFAULT_ACCELERATOR = LocalBackend.GPU

/**
 * Sentinel for "the load did not finish inside [LOAD_TIMEOUT_MS]". A
 * `TimeoutCancellationException` cannot be constructed here (its constructor is
 * internal to kotlinx-coroutines), and a plain marker keeps the classification
 * readable.
 */
private object LoadTimeout : Throwable("tempo limite de carregamento excedido")

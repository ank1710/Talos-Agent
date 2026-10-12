package com.talos.harness.local

import android.content.Context
import android.util.Base64
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.OpenApiTool
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ToolCall as LiteRtToolCall
import com.google.ai.edge.litertlm.tool
import com.talos.harness.core.TalosJson
import com.talos.harness.llm.ChatBackend
import com.talos.harness.llm.ChatRequest
import com.talos.harness.llm.StreamEvent
import com.talos.harness.llm.WireMessage
import com.talos.harness.llm.WireTool
import com.talos.harness.llm.WireToolCall
import com.talos.harness.llm.WireUsage
import com.talos.harness.llm.asPlainText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File

/** A load or generation failure whose message is already user-facing (Portuguese). */
class LocalModelException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Runs the model on the phone, with no network at all.
 *
 * The engine is loaded once and kept: LiteRT-LM warns that `initialize()` can
 * take ~10 seconds, so doing it per turn would make every step feel broken. Only
 * one generation runs at a time — a second concurrent one would fight for the
 * same RAM and make both slower — so turns are serialised on [generationLock].
 *
 * Everything the seam requires of a [ChatBackend] is honoured here: exactly one
 * [StreamEvent.Finished] on success, ordinary failures reported as
 * [StreamEvent.Failed] with a Portuguese message, and no exception ever leaving
 * [streamChat].
 */
class OnDeviceBackend(
    context: Context,
    private val models: ModelStore = ModelStore(context),
    /**
     * What to try first. [DeviceCapability] measures this; the load path falls
     * back to the CPU on its own, so a wrong guess costs a few seconds, not a turn.
     */
    private val preferredBackend: LocalBackend = LocalBackend.GPU,
    /**
     * Sampler settings used only when the request carries a temperature.
     * LiteRT-LM's `SamplerConfig` has no defaults for these three (all are
     * required), while the wire request only carries `temperature`.
     */
    private val topK: Int = DEFAULT_TOP_K,
    private val topP: Double = DEFAULT_TOP_P,
    /**
     * Multi-token prediction. The LiteRT-LM docs recommend it "universally" on
     * GPU, but it throws for a model with no MTP drafter, so the load path
     * retries with it off before giving up.
     */
    private val speculativeDecoding: Boolean = true,
) : ChatBackend {

    /** Taken from the context and stored as a String: the engine outlives any screen. */
    private val cacheDir: String = context.cacheDir.absolutePath

    /** Guards [engine]/[loadedPath]/[loadedWith] against `release()` racing a load. */
    private val lock = Any()

    /** Serialises generations. Held across a whole turn, so a queue forms rather than a fight. */
    private val generationLock = Mutex()

    @Volatile
    private var engine: Engine? = null

    @Volatile
    private var loadedPath: String? = null

    @Volatile
    private var loadedWith: LocalBackend? = null

    /** Path of the model currently in memory, or null when nothing is loaded. */
    val loadedModelPath: String? get() = loadedPath

    /** Backend actually in use, which may differ from [preferredBackend] after a fallback. */
    val activeBackend: LocalBackend? get() = loadedWith

    /**
     * Loads [modelPath] if it is not already the loaded model on [backend].
     *
     * Blocking native work happens on [Dispatchers.IO] internally, so callers may
     * call this from anywhere. Throws [LocalModelException] with a Portuguese
     * message when the file is missing or no backend could initialise it; that is
     * deliberate for the explicit loading path (a settings screen wants the
     * reason) and is converted to [StreamEvent.Failed] inside [streamChat].
     */
    suspend fun ensureLoaded(modelPath: String, backend: LocalBackend = preferredBackend) {
        withContext(Dispatchers.IO) {
            val stale = synchronized(lock) {
                if (engine != null && loadedPath == modelPath && loadedWith == backend) {
                    return@withContext
                }
                val previous = engine
                engine = null
                loadedPath = null
                loadedWith = null
                previous
            }
            // Native teardown happens outside the lock, so a slow close cannot
            // block release() or a concurrent load.
            runCatching { stale?.close() }

            val file = File(modelPath)
            if (!file.isFile) {
                throw LocalModelException("Arquivo de modelo não encontrado: $modelPath")
            }

            val loaded = load(file, backend)
            synchronized(lock) {
                engine = loaded.engine
                loadedPath = file.absolutePath
                loadedWith = loaded.backend
            }
        }
    }

    /**
     * Loads the default installed model, or returns null when none is present.
     *
     * Returns the path that was loaded so a settings screen can record what is
     * actually in memory.
     */
    suspend fun ensureDefaultLoaded(recommendation: ModelRecommendation? = null): String? {
        val model = models.default(recommendation) ?: return null
        ensureLoaded(model.path, recommendation?.backend ?: preferredBackend)
        return model.path
    }

    /** Drops the engine, freeing the model's memory. Safe to call when nothing is loaded. */
    fun release() {
        val stale = synchronized(lock) {
            val previous = engine
            engine = null
            loadedPath = null
            loadedWith = null
            previous
        }
        runCatching { stale?.close() }
    }

    /**
     * Streams one completion.
     *
     * **One fresh conversation per call.** The loop rebuilds the whole transcript
     * on every step and passes it here, so seeding a new `Conversation` with
     * `initialMessages` and sending only the last message maps the wire request
     * onto LiteRT-LM exactly, and the transcript stays the single source of
     * truth. The cost is real: the preface is re-rendered and re-prefilled on
     * every step, so a long tool-using turn is O(transcript) work per step rather
     * than incremental decode.
     *
     * The alternative — caching the conversation and sending only the new tail
     * when the incoming transcript extends the previous one — would remove that
     * prefill, and it is where this should go next. It is not done here because
     * the native conversation's KV cache also contains the tokens it generated,
     * and cancelling mid-generation leaves that state inconsistent
     * (b/450903294: there is no rollback yet). Fresh conversations sidestep both,
     * and a cancelled turn simply throws the conversation away.
     */
    override fun streamChat(
        apiKey: String,
        baseUrl: String,
        request: ChatRequest,
    ): Flow<StreamEvent> = flow {
        val modelPath = models.pathFor(request.model) ?: loadedPath ?: models.default()?.path
        if (modelPath == null) {
            emit(StreamEvent.Failed(noModelMessage()))
            return@flow
        }

        generationLock.withLock {
            // Keep whatever an explicit ensureLoaded already picked for this file;
            // otherwise use the configured preference.
            val backend = loadedWith?.takeIf { modelPath == loadedPath } ?: preferredBackend
            try {
                ensureLoaded(modelPath, backend)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                emit(StreamEvent.Failed(e.message ?: LOAD_FAILED_MESSAGE))
                return@withLock
            }

            val active = engine
            if (active == null) {
                emit(StreamEvent.Failed(LOAD_FAILED_MESSAGE))
                return@withLock
            }

            val systemMessage = request.messages.firstOrNull { it.role == ROLE_SYSTEM }
            val turns = if (systemMessage == null) {
                request.messages
            } else {
                request.messages.filterNot { it === systemMessage }
            }
            val current = turns.lastOrNull()
            if (current == null) {
                emit(StreamEvent.Failed("Nada para enviar ao modelo local: a requisição veio sem mensagens."))
                return@withLock
            }

            val toolNames = toolNamesById(request.messages)
            // A tool response without a resolvable tool name cannot be rendered
            // as one; sending its text as a user turn at least keeps the
            // information in the transcript instead of dropping the turn.
            val currentMessage = current.toLocalMessage(toolNames)
                ?: Message.user(current.content.asPlainText().orEmpty())

            val config = ConversationConfig(
                systemInstruction = systemMessage?.content
                    ?.asPlainText()
                    ?.takeIf { it.isNotBlank() }
                    ?.let { Contents.of(it) },
                initialMessages = turns.dropLast(1).mapNotNull { it.toLocalMessage(toolNames) },
                // Declaring the tools is what enables tool calling at all; the
                // OpenAPI route is the only one that can express them, because
                // Talos tools have no Kotlin methods to annotate.
                tools = request.tools.orEmpty().map { tool(DynamicTool(it)) },
                samplerConfig = request.temperature?.let {
                    SamplerConfig(topK = topK, topP = topP, temperature = it)
                },
                // Never let LiteRT-LM execute tools: that path calls
                // OpenApiTool.execute() directly, which would run a risky tool
                // without AgentLoop's approval gate ever seeing it. With
                // automatic calling off, a tool call arrives as data and the loop
                // decides.
                automaticToolCalling = false,
                maxOutputToken = request.maxTokens?.takeIf { it > 0 },
            )

            var conversation: Conversation? = null
            // Runs across the whole turn, not per message: if the native layer
            // ever delivered two calls in two separate messages, a per-message
            // index would restart at 0 and the loop would append the second
            // call's arguments to the first slot.
            var toolCallIndex = 0
            try {
                conversation = active.createConversation(config)
                conversation.sendMessageAsync(currentMessage).collect { message ->
                    val text = message.contents.contents
                        .filterIsInstance<Content.Text>()
                        .joinToString(separator = "") { it.text }
                    val channels = message.channels.filterValues { it.isNotEmpty() }

                    // Reasoning first: a thinking model produces it before the
                    // answer, and the UI renders events in arrival order.
                    for ((name, value) in channels) {
                        if (isReasoningChannel(name)) emit(StreamEvent.Reasoning(value))
                    }

                    if (text.isNotEmpty()) emit(StreamEvent.Content(text))

                    for ((name, value) in channels) {
                        if (isReasoningChannel(name)) continue
                        // Some templates echo a channel's text into `content` as
                        // well; emitting both would duplicate it in the transcript.
                        if (text.isNotEmpty() && text.contains(value)) continue
                        emit(StreamEvent.Content(value))
                    }

                    // LiteRT-LM does not stream tool calls token by token: it
                    // delivers one Message whose arguments are already parsed.
                    // The loop accumulates (index, id, name, argsFragment), so
                    // each call is emitted whole, with a unique index and no id
                    // — the loop invents `call_$index` for a null id.
                    for (call in message.toolCalls) {
                        emit(
                            StreamEvent.ToolCallDelta(
                                index = toolCallIndex++,
                                id = null,
                                name = call.name,
                                argsFragment = call.arguments.toJsonObject().toString(),
                            ),
                        )
                    }
                }

                // The only token count LiteRT-LM exposes is the KV-cache
                // occupancy after the turn (Conversation.getTokenCount()), with
                // no prompt/completion split, so those two stay 0 and the UI
                // reads only the total for local turns.
                runCatching { conversation.getTokenCount() }
                    .getOrNull()
                    ?.takeIf { it > 0 }
                    ?.let {
                        emit(
                            StreamEvent.Usage(
                                WireUsage(promptTokens = 0, completionTokens = 0, totalTokens = it),
                            ),
                        )
                    }

                emit(StreamEvent.Finished(null))
            } catch (e: CancellationException) {
                // Stop the native decode before this coroutine unwinds; the
                // conversation is closed right after, so the inconsistent KV
                // state that cancelProcess leaves behind cannot be reused.
                runCatching { conversation?.cancelProcess() }
                throw e
            } catch (e: Throwable) {
                emit(StreamEvent.Failed(describe(e)))
            } finally {
                runCatching { conversation?.close() }
            }
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Loads the engine, degrading instead of failing.
     *
     * The official integration guide mandates this cascade: a vendor GPU driver
     * that cannot be dlopen'd throws from `initialize()`, and retrying on the CPU
     * is the fix. Speculative decoding is part of the cascade because it is
     * process-global state read when the engine is created, and it throws for a
     * model with no MTP drafter — so turning it off means building a new engine.
     */
    private fun load(file: File, backend: LocalBackend): Loaded {
        val attempts = mutableListOf(Attempt(backend, speculativeDecoding))
        if (speculativeDecoding) attempts += Attempt(backend, speculative = false)
        if (backend != LocalBackend.CPU) attempts += Attempt(LocalBackend.CPU, speculative = false)

        val failures = mutableListOf<String>()
        for (attempt in attempts) {
            try {
                return Loaded(createEngine(file, attempt), attempt.backend)
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                failures += "${attempt.label}: ${e.message ?: e::class.simpleName ?: "erro"}"
            }
        }

        throw LocalModelException(
            "Não foi possível carregar o modelo no dispositivo " +
                "(${failures.joinToString("; ")}).",
        )
    }

    @OptIn(ExperimentalApi::class)
    private fun createEngine(file: File, attempt: Attempt): Engine {
        ExperimentalFlags.enableSpeculativeDecoding = attempt.speculative
        val config = EngineConfig(
            modelPath = file.absolutePath,
            backend = when (attempt.backend) {
                LocalBackend.GPU -> Backend.GPU()
                LocalBackend.CPU -> Backend.CPU()
            },
            // LiteRT-LM notes this makes the second load of the same model faster.
            cacheDir = cacheDir,
        )
        val engine = Engine(config)
        try {
            engine.initialize()
        } catch (e: Throwable) {
            runCatching { engine.close() }
            throw e
        }
        return engine
    }

    private fun describe(e: Throwable): String = when {
        e is LocalModelException -> e.message ?: LOAD_FAILED_MESSAGE
        e is OutOfMemoryError ->
            "Memória insuficiente para o modelo local. Tente um modelo menor ou feche outros apps."
        else -> "Falha ao gerar resposta no modelo local: " +
            (e.message ?: e::class.simpleName ?: "erro desconhecido")
    }

    private fun noModelMessage(): String =
        "Nenhum modelo local instalado. Copie um arquivo .litertlm para " +
            "${models.directory().absolutePath} e tente de novo."

    private class Loaded(val engine: Engine, val backend: LocalBackend)

    private data class Attempt(val backend: LocalBackend, val speculative: Boolean) {
        val label: String get() = if (speculative) "${backend.name}+MTP" else backend.name
    }

    private companion object {
        const val ROLE_SYSTEM = "system"
        const val ROLE_USER = "user"
        const val ROLE_ASSISTANT = "assistant"
        const val ROLE_TOOL = "tool"

        const val DEFAULT_TOP_K = 40
        const val DEFAULT_TOP_P = 0.95

        const val LOAD_FAILED_MESSAGE = "Não foi possível carregar o modelo local."
    }
}

/**
 * One dynamic tool, declared through LiteRT-LM's OpenAPI route.
 *
 * The annotated `@Tool`/`ToolSet` route cannot express a Talos tool: tools are
 * data, not Kotlin methods. `OpenApiTool` takes exactly the object the registry
 * already holds — name, description and JSON Schema — as a JSON string, and
 * LiteRT-LM wraps it in `{"type":"function","function":…}` itself, so the schema
 * from `Tool.parameters` is passed through untouched.
 */
private class DynamicTool(private val spec: WireTool) : OpenApiTool {

    override fun getToolDescriptionJsonString(): String = buildJsonObject {
        put("name", spec.function.name)
        put("description", spec.function.description)
        put("parameters", spec.function.parameters)
    }.toString()

    /**
     * Never called: `automaticToolCalling = false` keeps execution in
     * `AgentLoop`, where the registry and the approval gate live. If a future
     * change flips that flag, this fails loudly instead of running a tool behind
     * the user's back.
     */
    override fun execute(paramsJsonString: String): String =
        """{"erro":"execução automática de ferramentas desativada neste backend"}"""
}

/**
 * The contents of one wire message, in the order LiteRT-LM expects: text first,
 * then any images.
 *
 * The loop flattens attachments into `data:` URLs before this point, so the
 * bytes are decoded back here — dropping them silently would make an attached
 * photo invisible to the model while the UI claimed it was sent.
 */
private fun WireMessage.localContents(): Contents {
    val parts = mutableListOf<Content>()
    when (val element = content) {
        null -> Unit
        is JsonPrimitive -> if (element.isString) parts += Content.Text(element.content)
        is JsonArray -> for (part in element) {
            val partObject = part as? JsonObject ?: continue
            when ((partObject["type"] as? JsonPrimitive)?.content) {
                "text" -> (partObject["text"] as? JsonPrimitive)?.content?.let {
                    parts += Content.Text(it)
                }

                "image_url" -> {
                    val url = ((partObject["image_url"] as? JsonObject)?.get("url") as? JsonPrimitive)
                        ?.content
                    decodeDataUrl(url)?.let { parts += Content.ImageBytes(it) }
                }
            }
        }

        else -> Unit
    }
    return if (parts.isEmpty()) Contents.of("") else Contents.of(parts)
}

/**
 * `data:image/png;base64,…` to bytes, or null when the URL is not one.
 *
 * A malformed attachment is dropped rather than thrown: it must not cost the
 * user the whole turn.
 */
private fun decodeDataUrl(url: String?): ByteArray? {
    if (url == null || !url.startsWith("data:")) return null
    val comma = url.indexOf(',')
    if (comma < 0) return null
    if (!url.substring(0, comma).contains("base64", ignoreCase = true)) return null
    return runCatching { Base64.decode(url.substring(comma + 1), Base64.DEFAULT) }.getOrNull()
}

/**
 * One wire turn as a LiteRT-LM message, or null when it carries nothing.
 *
 * [toolNames] recovers the tool *name* for a `role:"tool"` turn; see
 * [toolNamesById].
 */
private fun WireMessage.toLocalMessage(toolNames: Map<String, String>): Message? = when (role) {
    "system" -> content.asPlainText()?.let { Message.system(it) }

    "user" -> Message.user(localContents())

    "assistant" -> {
        val calls = toolCalls.orEmpty().mapNotNull { it.toLocalToolCall() }
        val text = content.asPlainText().orEmpty()
        when {
            // Tool call only: `contents` is omitted entirely so the rendered
            // turn is the call and nothing else.
            calls.isNotEmpty() && text.isBlank() -> Message.model(toolCalls = calls)
            calls.isNotEmpty() -> Message.model(Contents.of(text), calls)
            text.isNotBlank() -> Message.model(text)
            // A blank assistant turn with no calls teaches the model that empty
            // answers are acceptable; Wire.kt drops the same case upstream.
            else -> null
        }
    }

    "tool" -> {
        val name = toolCallId?.let { toolNames[it] } ?: name ?: toolCallId
        if (name.isNullOrBlank()) {
            null
        } else {
            Message.tool(
                Contents.of(
                    Content.ToolResponse(name, toolResponseValue(content.asPlainText().orEmpty())),
                ),
            )
        }
    }

    else -> null
}

/**
 * A tool result in the shape the model should read it.
 *
 * Talos tools return text, which is frequently a JSON payload. LiteRT-LM
 * serialises `ToolResponse.response` with gson's `Any?.toJsonElement()`, so the
 * raw string would arrive as `"response":"{\"ok\":true}"` — a JSON document
 * escaped inside a string — while plain Kotlin values arrive as
 * `"response":{"ok":true}`. Verified against the 0.18.0 classes on the JVM.
 * Objects and arrays are therefore parsed back into plain values; everything
 * else (prose, a bare number, or text that merely happens to parse) is passed
 * through exactly as the tool wrote it.
 */
private fun toolResponseValue(text: String): Any? {
    val parsed = runCatching { TalosJson.parseToJsonElement(text) }.getOrNull()
    return when (parsed) {
        is JsonObject -> parsed.toPlainMap()
        is JsonArray -> parsed.map { it.toPlainValue() }
        else -> text
    }
}

/**
 * `tool_call_id` to tool name.
 *
 * LiteRT-LM keys a tool response by the tool's *name*, while the OpenAI-shaped
 * wire message that carries a result only has `tool_call_id` (`ChatMessage` is
 * flattened by `toWireMessages`, which cannot be changed). The assistant turn
 * that requested the call is earlier in the same transcript, so the name is
 * recovered from there.
 */
private fun toolNamesById(messages: List<WireMessage>): Map<String, String> = buildMap {
    for (message in messages) {
        for (call in message.toolCalls.orEmpty()) {
            val id = call.id ?: continue
            val name = call.function?.name ?: continue
            put(id, name)
        }
    }
}

/**
 * A stored tool call back into LiteRT-LM's shape.
 *
 * `arguments` must be plain Kotlin values, not kotlinx JSON: LiteRT-LM
 * re-serialises `ToolCall.arguments` with gson, which understands
 * String/Number/Boolean/Map/List and falls back to `toString()` for anything
 * else — which would hand the model a *quoted* JSON string instead of an object.
 */
private fun WireToolCall.toLocalToolCall(): LiteRtToolCall? {
    val function = function ?: return null
    val name = function.name ?: return null
    val arguments = runCatching {
        (TalosJson.parseToJsonElement(function.arguments.ifBlank { "{}" }) as? JsonObject)
            ?.toPlainMap()
    }.getOrNull() ?: emptyMap()
    return LiteRtToolCall(name, arguments)
}

private fun JsonObject.toPlainMap(): Map<String, Any?> =
    entries.associate { (key, value) -> key to value.toPlainValue() }

/** kotlinx JSON down to the plain Kotlin values gson round-trips. */
private fun JsonElement.toPlainValue(): Any? = when (this) {
    is JsonNull -> null
    is JsonObject -> entries.associate { (key, value) -> key to value.toPlainValue() }
    is JsonArray -> map { it.toPlainValue() }
    is JsonPrimitive -> when {
        isString -> content
        booleanOrNull != null -> booleanOrNull
        longOrNull != null -> longOrNull
        doubleOrNull != null -> doubleOrNull
        else -> content
    }
}

/** Plain Kotlin values back to JSON, for the arguments fragment of a tool call delta. */
private fun Map<String, Any?>.toJsonObject(): JsonObject =
    JsonObject(entries.associate { (key, value) -> key to value.toJsonElement() })

private fun Any?.toJsonElement(): JsonElement = when (this) {
    null -> JsonNull
    is String -> JsonPrimitive(this)
    is Number -> JsonPrimitive(this)
    is Boolean -> JsonPrimitive(this)
    is Map<*, *> -> JsonObject(entries.associate { (key, value) -> key.toString() to value.toJsonElement() })
    is List<*> -> JsonArray(map { it.toJsonElement() })
    else -> JsonPrimitive(toString())
}

/**
 * Channel names that carry chain-of-thought rather than the answer. LiteRT-LM
 * fills `Message.channels` from the model's own chat template (the conversation
 * is created without an explicit channel list, so the model metadata decides),
 * and the names differ per model, so this matches loosely.
 */
private fun isReasoningChannel(name: String): Boolean {
    val key = name.lowercase()
    return REASONING_CHANNEL_HINTS.any { key.contains(it) }
}

private val REASONING_CHANNEL_HINTS = listOf("think", "thought", "reason", "analysis", "cot")

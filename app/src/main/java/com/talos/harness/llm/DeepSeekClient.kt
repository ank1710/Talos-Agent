package com.talos.harness.llm

import com.talos.harness.core.TalosJson
import com.talos.harness.store.Provider
import com.talos.harness.store.ProviderAuthStyle
import com.talos.harness.store.ProviderCatalog
import com.talos.harness.store.ProviderModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.job
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Talks to an OpenAI-compatible chat endpoint using the selected provider's auth.
 *
 * Streaming reads the raw SSE body on the IO dispatcher and turns each
 * `data:` frame into a [StreamEvent]. Cancelling the collecting coroutine
 * cancels the HTTP call, because [ensureActive] runs between frames.
 */
class DeepSeekClient(
    private val http: OkHttpClient = defaultHttpClient(),
    private val provider: Provider = ProviderCatalog.DEEPSEEK,
) : ChatBackend {

    // SSE may be long-lived, but a silent stream and an abandoned call must be bounded.
    private val streamHttp by lazy {
        http.newBuilder().readTimeout(STREAM_IDLE_TIMEOUT_SECONDS, TimeUnit.SECONDS).build()
    }

    // Settings checks must finish promptly and must not forward a scoped key through a redirect.
    private val discoveryHttp by lazy {
        http.newBuilder().callTimeout(15, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
            .followRedirects(false).followSslRedirects(false).build()
    }

    /**
     * Sink for the gateway's own control frames (see [StreamEvent.GatewayApprovalRequested]).
     *
     * They travel on the same stream as every other [StreamEvent], but
     * [com.talos.harness.agent.AgentLoop] — frozen for this feature — matches
     * the stream exhaustively and has no branch for them, so it would drop them
     * before the view model ever saw one. The owner of this client installs a
     * listener here instead. Invoked on the IO dispatcher while the stream is
     * being read, so implementations must not block.
     */
    var onGatewayEvent: ((StreamEvent) -> Unit)? = null

    /** Run id observed on the current stream, before an approval prompt may arrive. */
    var onGatewayRunId: ((String) -> Unit)? = null

    override fun streamChat(
        apiKey: String,
        baseUrl: String,
        request: ChatRequest,
    ): Flow<StreamEvent> = flow {
        val payload = TalosJson.encodeToString(ChatRequest.serializer(), request)
        val httpRequest = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/chat/completions")
            .applyProviderAuth(provider, apiKey)
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
            .post(payload.toRequestBody(JSON_MEDIA))
            .build()

        try {
            val call = streamHttp.newCall(httpRequest).apply {
                timeout().timeout(STREAM_CALL_TIMEOUT_MINUTES, TimeUnit.MINUTES)
            }
            val cancellation = currentCoroutineContext().job.cancelCallOnCancellation(call)
            try {
                call.awaitResponse().use { response ->
                val body = response.body
                if (!response.isSuccessful) {
                    val text = runCatching { body.string() }.getOrDefault("")
                    emit(StreamEvent.Failed(describeHttpError(response.code, text), retryable = response.code == 429 || response.code >= 500))
                    return@use
                }

                val source = body.source()
                var sawTerminal = false
                // The gateway labels a chat-completions turn with the chatcmpl id
                // and reuses it as run_id; keep it as a fallback for frames that
                // omit run_id.
                var runId: String? = null
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val line = source.readUtf8Line() ?: break
                    if (line.isEmpty() || line.startsWith(":")) continue
                    if (!line.startsWith("data:")) continue

                    val data = line.removePrefix("data:").trim()
                    if (data == "[DONE]") {
                        sawTerminal = true
                        break
                    }

                    // Gateway control frames carry a top-level `event` and are NOT
                    // OpenAI chunks. They must be recognised *before* the chunk
                    // decode below: `StreamChunk` has an empty `choices` default
                    // and `ignoreUnknownKeys`, so a bare approval frame decodes
                    // "successfully" into an empty chunk and silently vanishes.
                    val frame = runCatching { TalosJson.parseToJsonElement(data).jsonObject }.getOrNull()
                    if (frame != null && frame.containsKey("event")) {
                        gatewayEvent(frame, runId)?.let { event ->
                            emit(event)
                            onGatewayEvent?.invoke(event)
                        }
                        continue
                    }

                    val chunk = runCatching {
                        TalosJson.decodeFromString(StreamChunk.serializer(), data)
                    }.getOrNull() ?: continue

                    chunk.id?.let {
                        runId = it
                        onGatewayRunId?.invoke(it)
                    }
                    chunk.usage?.let { emit(StreamEvent.Usage(it)) }

                    for (choice in chunk.choices) {
                        choice.delta.reasoningContent
                            ?.takeIf { it.isNotEmpty() }
                            ?.let { emit(StreamEvent.Reasoning(it)) }

                        choice.delta.content
                            ?.takeIf { it.isNotEmpty() }
                            ?.let { emit(StreamEvent.Content(it)) }

                        choice.delta.toolCalls?.forEach { call ->
                            emit(
                                StreamEvent.ToolCallDelta(
                                    index = call.index,
                                    id = call.id,
                                    name = call.function?.name,
                                    argsFragment = call.function?.arguments,
                                ),
                            )
                        }

                        choice.finishReason?.let {
                            sawTerminal = true
                            emit(StreamEvent.Finished(it))
                        }
                    }
                }
                    if (!sawTerminal) emit(StreamEvent.Finished(null))
                }
            } finally {
                cancellation.dispose()
            }
        } catch (e: IOException) {
            currentCoroutineContext().ensureActive()
            emit(StreamEvent.Failed(e.message ?: "falha de rede", retryable = true))
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Decodes one gateway control frame, or null for the ~18 event names this
     * app does not act on (token deltas, tool starts, run lifecycle, …).
     *
     * Wire shapes live in `docs/remote-approvals.md`; only the two approval
     * events are surfaced. Ignoring the rest silently is intentional — they are
     * progress noise, and the frames they arrive in never carry a chat chunk.
     */
    private fun gatewayEvent(frame: JsonObject, runId: String?): StreamEvent? {
        fun text(key: String): String? = (frame[key] as? JsonPrimitive)?.contentOrNull

        // Prefer the frame's own run_id; fall back to the chatcmpl id of the turn.
        val resolvedRunId = text("run_id") ?: runId.orEmpty()
        return when (text("event")) {
            "approval.request" -> StreamEvent.GatewayApprovalRequested(
                runId = resolvedRunId,
                requestId = text("request_id"),
                command = text("command").orEmpty(),
                choices = (frame["choices"] as? JsonArray)
                    ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                    .orEmpty(),
            )

            "approval.responded" -> StreamEvent.GatewayApprovalResolved(
                runId = resolvedRunId,
                requestId = text("request_id"),
            )

            else -> null
        }
    }

    /** Reuses the HTTP pool while binding authentication to one provider per turn. */
    fun forProvider(value: Provider): DeepSeekClient = DeepSeekClient(http, value).also {
        it.onGatewayEvent = onGatewayEvent
        it.onGatewayRunId = onGatewayRunId
    }

    /** Best-effort model discovery so settings can show provider names and ids. */
    suspend fun listModels(
        apiKey: String,
        baseUrl: String,
        provider: Provider,
    ): List<ProviderModel> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/models")
            .applyProviderAuth(provider, apiKey)
            .get()
            .build()
        runCatching {
            discoveryHttp.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use emptyList()
                val text = response.body.string()
                val data = (TalosJson.parseToJsonElement(text) as? JsonObject)?.get("data") as? JsonArray
                data.orEmpty().mapNotNull { item ->
                    val model = item as? JsonObject ?: return@mapNotNull null
                    val id = (model["id"] as? JsonPrimitive)?.contentOrNull
                        ?.takeIf(String::isNotBlank)
                        ?: return@mapNotNull null
                    val name = (model["name"] as? JsonPrimitive)?.contentOrNull
                        ?.takeIf(String::isNotBlank)
                        ?: id
                    ProviderModel(id = id, name = name)
                }.distinctBy(ProviderModel::id)
            }
        }.getOrDefault(emptyList())
    }

    /** Verifies a key with a cheap round trip; returns null when the key works. */
    suspend fun probe(apiKey: String, baseUrl: String, provider: Provider): String? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/models")
            .applyProviderAuth(provider, apiKey)
            .get()
            .build()
        try {
            discoveryHttp.newCall(request).execute().use { response ->
                if (response.isSuccessful) null
                else describeHttpError(response.code, response.body.string())
            }
        } catch (e: IOException) {
            e.message ?: "falha de rede"
        }
    }

    private fun describeHttpError(code: Int, rawBody: String): String {
        val apiMessage = runCatching {
            TalosJson.decodeFromString(ApiErrorEnvelope.serializer(), rawBody).error?.message
        }.getOrNull()

        val hint = when (code) {
            400 -> "Requisição inválida."
            401 -> "Chave de API inválida ou ausente."
            402 -> "Saldo insuficiente na conta do provedor."
            403 -> "Acesso negado para esta chave."
            404 -> "Endpoint não encontrado — confira a URL base."
            422 -> "Parâmetros rejeitados pelo modelo."
            429 -> "Limite de requisições atingido. Tente de novo em instantes."
            // Provider-neutral on purpose: this client serves every configured
            // provider, and naming one of them told the user "DeepSeek" while
            // they were talking to Ollama on their own machine.
            in 500..599 -> "Erro no servidor do provedor. Tente novamente."
            else -> "Falha HTTP $code."
        }
        return if (apiMessage.isNullOrBlank()) hint else "$hint ($apiMessage)"
    }

    /** Centralizes provider-specific header rules so UI and settings stay protocol-agnostic. */
    private fun Request.Builder.applyProviderAuth(provider: Provider, apiKey: String): Request.Builder {
        if (apiKey.isNotBlank()) {
            when (provider.authStyle) {
                ProviderAuthStyle.BEARER -> header("Authorization", "Bearer $apiKey")
                ProviderAuthStyle.X_API_KEY -> header("x-api-key", apiKey)
            }
        }
        provider.extraHeaders.forEach { (name, value) -> header(name, value) }
        return this
    }

    companion object {
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        private const val STREAM_IDLE_TIMEOUT_SECONDS = 60L
        private const val STREAM_CALL_TIMEOUT_MINUTES = 5L

        /** Streaming responses stay open for a long time, so the read timeout is generous. */
        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}

/** Enqueueing keeps cancellation connected to OkHttp even while the SSE body is blocked in a read. */
private suspend fun Call.awaitResponse(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            continuation.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            continuation.resume(response) { _, value, _ -> value.close() }
        }
    })
}

/** A Job cancellation must cancel the Call even after headers arrived and a blocking body read began. */
@OptIn(InternalCoroutinesApi::class)
private fun Job.cancelCallOnCancellation(call: Call): DisposableHandle =
    invokeOnCompletion(onCancelling = true, invokeImmediately = true) { cause ->
        if (cause != null) call.cancel()
    }

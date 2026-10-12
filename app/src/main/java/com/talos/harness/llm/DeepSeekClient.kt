package com.talos.harness.llm

import com.talos.harness.core.TalosJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Talks to a DeepSeek (OpenAI-compatible) chat endpoint.
 *
 * Streaming reads the raw SSE body on the IO dispatcher and turns each
 * `data:` frame into a [StreamEvent]. Cancelling the collecting coroutine
 * cancels the HTTP call, because [ensureActive] runs between frames.
 */
class DeepSeekClient(private val http: OkHttpClient = defaultHttpClient()) {

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

    fun streamChat(
        apiKey: String,
        baseUrl: String,
        request: ChatRequest,
    ): Flow<StreamEvent> = flow {
        val payload = TalosJson.encodeToString(ChatRequest.serializer(), request)
        val httpRequest = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
            .post(payload.toRequestBody(JSON_MEDIA))
            .build()

        try {
            http.newCall(httpRequest).execute().use { response ->
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

                    chunk.id?.let { runId = it }
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
        } catch (e: IOException) {
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

    /** Best-effort model discovery so the settings screen can offer real choices. */
    suspend fun listModels(apiKey: String, baseUrl: String): List<String> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/models")
            .header("Authorization", "Bearer $apiKey")
            .get()
            .build()
        runCatching {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use emptyList()
                val text = response.body.string()
                TalosJson.decodeFromString(ModelsResponse.serializer(), text).data.map { it.id }
            }
        }.getOrDefault(emptyList())
    }

    /** Verifies a key with a cheap round trip; returns null when the key works. */
    suspend fun probe(apiKey: String, baseUrl: String): String? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/models")
            .header("Authorization", "Bearer $apiKey")
            .get()
            .build()
        try {
            http.newCall(request).execute().use { response ->
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
            402 -> "Saldo insuficiente na conta DeepSeek."
            403 -> "Acesso negado para esta chave."
            404 -> "Endpoint não encontrado — confira a URL base."
            422 -> "Parâmetros rejeitados pelo modelo."
            429 -> "Limite de requisições atingido. Tente de novo em instantes."
            in 500..599 -> "Erro no servidor da DeepSeek. Tente novamente."
            else -> "Falha HTTP $code."
        }
        return if (apiMessage.isNullOrBlank()) hint else "$hint ($apiMessage)"
    }

    companion object {
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

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

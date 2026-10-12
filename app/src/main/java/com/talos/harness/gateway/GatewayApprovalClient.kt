package com.talos.harness.gateway

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * Sends the user's answer back to a prompt the *desktop* agent raised.
 *
 * The desktop is the side that blocks: it is parked inside its own tool gate
 * waiting for `POST {baseUrl}/runs/{runId}/approval`. So the phone owes it a
 * decision, not just a local coroutine resume.
 *
 * Uses its own short-timeout client rather than [com.talos.harness.llm.DeepSeekClient]'s
 * streaming one: a streaming client has no call timeout (a chat may run for
 * minutes), while this is a small POST that must not be able to hang the UI
 * forever if the desktop has gone away.
 */
class GatewayApprovalClient(private val http: OkHttpClient = shortTimeoutHttpClient()) {

    /**
     * Posts [choice] for [runId] and reports the outcome.
     *
     * [requestId] targets one prompt when several are pending on the same run;
     * omit it to let the gateway pick the pending one.
     *
     * A 409 is *soft*, not a failure of the request: the server says
     * `approval_not_pending`, i.e. the prompt was already answered (from the
     * desktop, another client, or a retry) or it expired. Callers should close
     * quietly on [AlreadyResolved] instead of showing an error.
     *
     * Never throws: the base URL and the key come from free-form settings, and
     * a URL `HttpUrl` rejects (or an unusable header value) is reported as a
     * failed [Result] like any other transport problem, so a typo cannot crash
     * the caller.
     */
    suspend fun respond(
        baseUrl: String,
        apiKey: String,
        runId: String,
        choice: String,
        requestId: String? = null,
    ): Result<Unit> {
        if (runId.isBlank() || apiKey.isBlank()) {
            return Result.failure(IOException("Identificador ou chave do gateway ausente."))
        }
        val payload = buildJsonObject {
            put("choice", choice)
            if (requestId != null) put("request_id", requestId)
        }.toString()

        return try {
            // Inside the guard on purpose: url() and the header setters throw
            // IllegalArgumentException on free-form input, and that must come
            // back as Result.failure rather than escape this function.
            val request = Request.Builder()
                .url(runEndpoint(baseUrl, runId, "approval"))
                .header("Authorization", "Bearer $apiKey")
                .header("Content-Type", "application/json")
                .post(payload.toRequestBody(JSON_MEDIA))
                .build()

            http.newCall(request).awaitResponse().use { response ->
                when {
                    response.isSuccessful -> Result.success(Unit)

                    response.code == 409 -> Result.failure(
                        AlreadyResolved(
                            "Este pedido já foi respondido ou expirou no PC.",
                        ),
                    )

                    else -> {
                        val body = runCatching { response.body.string() }.getOrDefault("")
                        Result.failure(
                            IOException("O PC recusou a resposta (HTTP ${response.code}). $body".trim()),
                        )
                    }
                }
            }
        } catch (e: IOException) {
            Result.failure(e)
        } catch (e: IllegalArgumentException) {
            // A malformed base URL (or a key that cannot be a header value) is a
            // settings mistake, not a crash: surface it in Portuguese the same
            // way a transport error would be, with the cause kept for logs.
            Result.failure(
                IOException(
                    "Endereço do gateway inválido. Confira a URL base nos ajustes.",
                    e,
                ),
            )
        }
    }

    /** Stops the desktop run captured by the active chat turn. */
    suspend fun stopRun(baseUrl: String, apiKey: String, runId: String): Result<Unit> {
        if (runId.isBlank() || apiKey.isBlank()) {
            return Result.failure(IOException("Identificador ou chave do gateway ausente."))
        }
        return try {
            val request = Request.Builder()
                .url(runEndpoint(baseUrl, runId, "stop"))
                .header("Authorization", "Bearer $apiKey")
                .post(ByteArray(0).toRequestBody(null))
                .build()
            http.newCall(request).awaitResponse().use { response ->
                if (response.isSuccessful) {
                    Result.success(Unit)
                } else {
                    Result.failure(IOException("O PC recusou o cancelamento (HTTP ${response.code})."))
                }
            }
        } catch (e: IOException) {
            Result.failure(e)
        } catch (e: IllegalArgumentException) {
            Result.failure(IOException("Endereço do gateway inválido. Confira a URL base nos ajustes.", e))
        }
    }

    /**
     * The prompt is no longer pending — someone else answered it, or it timed
     * out. Distinguishable so the UI can treat it as "already handled" rather
     * than as a network or server error.
     */
    class AlreadyResolved(message: String) : IOException(message)

    companion object {
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        /** Hard 30s bound on the whole call, matching the brief. */
        fun shortTimeoutHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}

/** Canonical origin only, used to keep a queued approval with its run's host. */
fun gatewayOrigin(baseUrl: String): String? = baseUrl.toHttpUrlOrNull()?.let { url ->
    val host = if (':' in url.host) "[${url.host}]" else url.host
    "${url.scheme}://$host:${url.port}"
}

private fun runEndpoint(baseUrl: String, runId: String, action: String): HttpUrl {
    require(runId.isNotBlank()) { "run_id ausente" }
    val base = baseUrl.toHttpUrlOrNull() ?: throw IllegalArgumentException("URL base inválida")
    return base.newBuilder()
        .encodedPath(base.encodedPath.trimEnd('/').ifEmpty { "/" })
        .query(null)
        .fragment(null)
        .addPathSegment("runs")
        .addPathSegment(runId)
        .addPathSegment(action)
        .build()
}

/** Cancellable continuation owns the OkHttp call so cancellation stops its network I/O. */
private suspend fun Call.awaitResponse(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWith(Result.failure(e))
        }

        override fun onResponse(call: Call, response: Response) {
            continuation.resume(response) { _, abandoned, _ -> abandoned.close() }
        }
    })
}

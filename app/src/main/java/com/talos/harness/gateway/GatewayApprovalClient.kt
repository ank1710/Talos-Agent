package com.talos.harness.gateway

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

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
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val payload = buildJsonObject {
            put("choice", choice)
            if (requestId != null) put("request_id", requestId)
        }.toString()

        try {
            // Inside the guard on purpose: url() and the header setters throw
            // IllegalArgumentException on free-form input, and that must come
            // back as Result.failure rather than escape this function.
            val request = Request.Builder()
                .url("${baseUrl.trimEnd('/')}/runs/$runId/approval")
                .header("Authorization", "Bearer $apiKey")
                .header("Content-Type", "application/json")
                .post(payload.toRequestBody(JSON_MEDIA))
                .build()

            http.newCall(request).execute().use { response ->
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

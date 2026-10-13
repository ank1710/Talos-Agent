package com.talos.harness.local

import com.talos.harness.core.TalosJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** A single downloadable LiteRT-LM bundle listed by the Hugging Face Hub. */
data class HuggingFaceModelBundle(
    val repository: String,
    val filename: String,
    val bytes: Long,
    val revision: String,
    val license: String?,
    val downloads: Long,
    val sha256: String?,
) {
    /** Repository id is validated before it can be shown or opened. */
    val repositoryUrl: String get() = "https://huggingface.co/$repository"
    val title: String get() = filename.substringAfterLast('/').removeSuffix(ModelStore.EXTENSION)
    val sizeLabel: String get() = formatBytes(bytes)
}

/**
 * Searches the public Hugging Face Hub for files the app's LiteRT-LM runtime
 * can attempt to load. A matching extension is a format filter, not a runtime
 * guarantee: the engine still verifies a bundle when the user selects it.
 */
class HuggingFaceModelCatalog(
    private val http: OkHttpClient = defaultHttpClient(),
) {
    suspend fun search(query: String): List<HuggingFaceModelBundle> = withContext(Dispatchers.IO) {
        val normalizedQuery = query.trim().take(MAX_QUERY_LENGTH)
        val url = "https://huggingface.co/api/models".toHttpUrl().newBuilder()
            .addQueryParameter("filter", "litert-lm")
            .addQueryParameter("pipeline_tag", "text-generation")
            .addQueryParameter("limit", MAX_REPOSITORIES.toString())
            .addQueryParameter("sort", "downloads")
            .addQueryParameter("direction", "-1")
            .addQueryParameter("full", "true")
            .apply { if (normalizedQuery.isNotEmpty()) addQueryParameter("search", normalizedQuery) }
            .build()

        val candidates = parseHuggingFaceCandidates(get(url.toString()))
        if (candidates.isEmpty()) return@withContext emptyList()
        val bundles = coroutineScope {
            candidates.map { candidate ->
                async {
                    val detailsUrl = "https://huggingface.co/api/models/${candidate.repository}"
                        .toHttpUrl().newBuilder()
                        .addQueryParameter("blobs", "true")
                        .addQueryParameter("revision", candidate.revision)
                        .build()
                    runCatching {
                        parseHuggingFaceBundleDetails(
                            get(detailsUrl.toString()),
                            candidate,
                        )
                    }.getOrDefault(emptyList())
                }
            }.awaitAll().flatten()
        }
        if (bundles.isEmpty()) {
            throw IOException("O Hugging Face não retornou tamanho e hash verificáveis para os bundles encontrados.")
        }
        bundles
    }

    private fun get(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("User-Agent", "Talos-Android")
            .get()
            .build()
        return http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Hugging Face respondeu HTTP ${response.code}.")
            }
            response.body.string()
        }
    }

    companion object {
        private const val MAX_QUERY_LENGTH = 100
        private const val MAX_REPOSITORIES = 12

        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}

internal data class HuggingFaceModelCandidate(
    val repository: String,
    val revision: String,
    val downloads: Long,
    val bundleNames: Set<String>,
)

/** Pure parsers kept separate so Hub result filtering can be checked on the host. */
internal fun parseHuggingFaceCandidates(json: String): List<HuggingFaceModelCandidate> {
    val models = runCatching { TalosJson.parseToJsonElement(json).jsonArray }.getOrNull()
        ?: return emptyList()
    return models.mapNotNull { element ->
        val model = element as? JsonObject ?: return@mapNotNull null
        val repository = model.string("id")?.takeIf(::isSafeRepositoryId)
            ?: return@mapNotNull null
        if (model.string("pipeline_tag") != "text-generation") return@mapNotNull null
        if (model.boolean("gated") == true || model.boolean("private") == true) return@mapNotNull null
        val revision = model.string("sha")?.takeIf { it.matches(Regex("[a-fA-F0-9]{7,64}")) }
            ?: return@mapNotNull null
        val bundleNames = model.array("siblings")
            .orEmpty()
            .mapNotNull { (it as? JsonObject)?.string("rfilename") }
            .filter { it.endsWith(ModelStore.EXTENSION, ignoreCase = true) }
            .toSet()
        if (bundleNames.isEmpty()) return@mapNotNull null
        HuggingFaceModelCandidate(
            repository = repository,
            revision = revision,
            downloads = model.long("downloads") ?: 0L,
            bundleNames = bundleNames,
        )
    }
}

internal fun parseHuggingFaceBundleDetails(
    json: String,
    candidate: HuggingFaceModelCandidate,
): List<HuggingFaceModelBundle> {
    val model = runCatching { TalosJson.parseToJsonElement(json).jsonObject }.getOrNull()
        ?: return emptyList()
    if (model.string("id") != candidate.repository || model.string("sha") != candidate.revision) {
        return emptyList()
    }
    val card = model.obj("cardData")
    val license = card?.string("license")
        ?: model.array("tags").orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            .firstOrNull { it.startsWith("license:") }
            ?.removePrefix("license:")
    return model.array("siblings").orEmpty().mapNotNull { element ->
        val file = element as? JsonObject ?: return@mapNotNull null
        val filename = file.string("rfilename") ?: return@mapNotNull null
        if (filename !in candidate.bundleNames || !filename.endsWith(ModelStore.EXTENSION, ignoreCase = true)) {
            return@mapNotNull null
        }
        val lfs = file.obj("lfs")
        val size = lfs?.long("size") ?: file.long("size") ?: return@mapNotNull null
        if (size <= 0) return@mapNotNull null
        HuggingFaceModelBundle(
            repository = candidate.repository,
            filename = filename,
            bytes = size,
            revision = candidate.revision,
            license = license,
            downloads = candidate.downloads,
            sha256 = lfs?.string("sha256"),
        )
    }
}

/**
 * Downloads a public HF bundle to the app's model directory. It only publishes
 * the final `.litertlm` file after the Hub size and SHA-256 both match.
 */
class HuggingFaceModelInstaller(
    private val modelStore: ModelStore,
    private val http: OkHttpClient = downloadHttpClient(),
) {
    suspend fun install(
        bundle: HuggingFaceModelBundle,
        onProgress: (downloadedBytes: Long, totalBytes: Long, verifying: Boolean) -> Unit,
    ): ModelFile = withContext(Dispatchers.IO) {
        validateBundle(bundle)
        val directory = modelStore.directory()
        val finalName = safeInstalledName(bundle)
        val target = File(directory, finalName)
        if (target.exists()) throw IOException("Este bundle já está instalado.")
        if (bundle.bytes > Long.MAX_VALUE - STORAGE_RESERVE_BYTES) {
            throw IOException("O tamanho informado pelo Hub é inválido.")
        }

        val requiredSpace = bundle.bytes + STORAGE_RESERVE_BYTES
        if (directory.usableSpace < requiredSpace) {
            throw IOException(
                "Espaço insuficiente: o bundle ocupa ${formatBytes(bundle.bytes)} e o Talos reserva " +
                    "${formatBytes(STORAGE_RESERVE_BYTES)} para o aparelho.",
            )
        }

        val temporary = File.createTempFile(".talos-model-", ".part", directory)
        var installed = false
        try {
            onProgress(0L, bundle.bytes, false)
            downloadAndVerify(bundle, temporary, onProgress)
            coroutineContext.ensureActive()
            // Same-directory move publishes a complete verified file in one step;
            // omitting REPLACE_EXISTING protects a bundle installed in the meantime.
            Files.move(temporary.toPath(), target.toPath())
            installed = true
            ModelFile(name = target.name, bytes = target.length(), path = target.absolutePath)
        } finally {
            if (!installed) temporary.delete()
        }
    }

    private suspend fun downloadAndVerify(
        bundle: HuggingFaceModelBundle,
        temporary: File,
        onProgress: (Long, Long, Boolean) -> Unit,
    ) {
        val urlBuilder = "https://huggingface.co".toHttpUrl().newBuilder()
        bundle.repository.split('/').forEach(urlBuilder::addPathSegment)
        urlBuilder.addPathSegment("resolve").addPathSegment(bundle.revision)
        bundle.filename.split('/').forEach(urlBuilder::addPathSegment)
        val request = Request.Builder()
            .url(urlBuilder.addQueryParameter("download", "true").build())
            .header("User-Agent", "Talos-Android")
            .get()
            .build()
        val call = http.newCall(request)

        suspendCancellableCoroutine<Unit> { continuation ->
            continuation.invokeOnCancellation {
                call.cancel()
                temporary.delete()
            }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        response.use { httpResponse ->
                            if (!httpResponse.isSuccessful) {
                                throw IOException("O Hugging Face respondeu HTTP ${httpResponse.code} ao baixar o bundle.")
                            }
                            val body = httpResponse.body
                            val digest = MessageDigest.getInstance("SHA-256")
                            var written = 0L
                            var lastReported = 0L
                            body.byteStream().use { input ->
                                FileOutputStream(temporary, false).use { output ->
                                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                                    while (true) {
                                        if (!continuation.isActive) {
                                            call.cancel()
                                            throw CancellationException("Download cancelado.")
                                        }
                                        val count = input.read(buffer)
                                        if (count < 0) break
                                        written += count
                                        if (written > bundle.bytes) {
                                            throw IOException("O bundle recebido é maior que o tamanho informado pelo Hub.")
                                        }
                                        output.write(buffer, 0, count)
                                        digest.update(buffer, 0, count)
                                        if (written - lastReported >= PROGRESS_INTERVAL_BYTES) {
                                            onProgress(written, bundle.bytes, false)
                                            lastReported = written
                                        }
                                    }
                                    output.fd.sync()
                                }
                            }
                            onProgress(written, bundle.bytes, true)
                            val actualHash = digest.digest().joinToString("") { byte ->
                                "%02x".format(byte.toInt() and 0xff)
                            }
                            verifyDownloadedModelIntegrity(
                                expectedBytes = bundle.bytes,
                                actualBytes = written,
                                expectedSha256 = bundle.sha256.orEmpty(),
                                actualSha256 = actualHash,
                            )
                        }
                        if (continuation.isActive) continuation.resume(Unit)
                    } catch (e: Exception) {
                        temporary.delete()
                        if (continuation.isActive) continuation.resumeWithException(e)
                    }
                }
            })
        }
    }

    private fun validateBundle(bundle: HuggingFaceModelBundle) {
        if (!bundle.repository.matches(Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+"))) {
            throw IOException("Repositório do modelo inválido.")
        }
        if (!bundle.revision.matches(Regex("[a-fA-F0-9]{7,64}"))) {
            throw IOException("Revisão do modelo inválida.")
        }
        if (bundle.filename.split('/').any { it.isBlank() || it == "." || it == ".." }) {
            throw IOException("Caminho do bundle inválido.")
        }
        if (!bundle.filename.endsWith(ModelStore.EXTENSION, ignoreCase = true)) {
            throw IOException("O Talos só instala bundles .litertlm.")
        }
        if (bundle.bytes <= 0L || bundle.sha256?.matches(Regex("[a-fA-F0-9]{64}")) != true) {
            throw IOException("O Hub não forneceu tamanho e SHA-256 verificáveis para este arquivo.")
        }
    }

    private fun safeInstalledName(bundle: HuggingFaceModelBundle): String {
        val base = bundle.filename.substringAfterLast('/')
        if (!base.matches(Regex("[A-Za-z0-9._-]{1,180}\\.litertlm", RegexOption.IGNORE_CASE))) {
            throw IOException("O nome deste bundle não pode ser usado com segurança no aparelho.")
        }
        val ownerPrefix = bundle.repository.substringBefore('/').take(48)
        return "${ownerPrefix}__${base}"
    }

    companion object {
        private const val STORAGE_RESERVE_BYTES = 500L * 1024L * 1024L
        private const val PROGRESS_INTERVAL_BYTES = 512L * 1024L

        private fun downloadHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            // Large model files may take longer than an API metadata request.
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}

internal fun verifyDownloadedModelIntegrity(
    expectedBytes: Long,
    actualBytes: Long,
    expectedSha256: String,
    actualSha256: String,
) {
    if (actualBytes != expectedBytes) {
        throw IOException(
            "Download incompleto: esperados ${formatBytes(expectedBytes)}, recebidos ${formatBytes(actualBytes)}.",
        )
    }
    if (!expectedSha256.matches(Regex("[a-fA-F0-9]{64}")) ||
        !expectedSha256.equals(actualSha256, ignoreCase = true)
    ) {
        throw IOException("SHA-256 diferente do publicado pelo Hugging Face; arquivo descartado.")
    }
}

private fun isSafeRepositoryId(value: String): Boolean =
    value.matches(Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+"))

private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.long(key: String): Long? =
    (this[key] as? JsonPrimitive)?.longOrNull

private fun JsonObject.boolean(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull()

private fun JsonObject.obj(key: String): JsonObject? =
    this[key] as? JsonObject

private fun JsonObject.array(key: String): JsonArray? =
    (this[key] as? JsonArray)

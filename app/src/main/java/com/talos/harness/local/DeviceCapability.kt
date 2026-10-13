package com.talos.harness.local

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import java.io.File
import java.util.Locale
import kotlin.math.max

internal const val MB: Long = 1024L * 1024L

/** Which accelerator to try first. LiteRT-LM falls back to the CPU at runtime when this fails. */
enum class LocalBackend { GPU, CPU }

/**
 * Model size classes, named by parameter count because that is how the bundles
 * are advertised on HuggingFace.
 *
 * [approxFileBytes] is the on-disk size of a typical 4-bit bundle of that class,
 * which is also the memory its weights occupy: LiteRT-LM maps the file, so the
 * residency is roughly the file size (a 2 GB bundle is not a 2 GB copy plus the
 * file, it is one 2 GB mapping). These are deliberately round numbers — they
 * exist to classify, not to predict exactly.
 */
enum class ModelSizeClass(val label: String, val approxFileBytes: Long) {
    TINY("~0,3B (ex.: FunctionGemma 270M)", 350L * MB),
    SMALL("1–2B (ex.: Gemma 3 1B)", 1_200L * MB),
    MEDIUM("4B (ex.: Gemma 3 4B)", 3_400L * MB),
    LARGE("12B ou mais", 8_000L * MB),
}

/**
 * What the probe concluded, with the reasoning that produced it.
 *
 * [explanation] is user-facing and in Portuguese: the point of measuring instead
 * of consulting a table is that the user can see *why* this phone got this
 * answer.
 */
data class ModelRecommendation(
    val sizeClass: ModelSizeClass,
    val backend: LocalBackend,
    /**
     * Largest model file that should be loaded, derived from free RAM above the
     * low-memory threshold. Also the budget [ModelStore.default] filters on.
     */
    val modelFileBudgetBytes: Long,
    /** False when this device cannot run LiteRT-LM at all (no 64-bit ABI, no RAM). */
    val runnable: Boolean,
    val explanation: String,
)

/** Everything the probe measured on this device. */
data class DeviceProfile(
    val totalRamBytes: Long,
    val freeRamBytes: Long,
    /** `MemoryInfo.threshold`: below this much free RAM Android considers the app killable. */
    val lowMemoryThresholdBytes: Long,
    val freeStorageBytes: Long,
    val abis: List<String>,
    val soc: String,
    val androidRelease: String,
    val sdkInt: Int,
    val lowRamDevice: Boolean,
    val openClPresent: Boolean,
    val recommendation: ModelRecommendation,
) {
    /** Free RAM that is actually usable, once Android's kill threshold is set aside. */
    val ramHeadroomBytes: Long get() = max(0L, freeRamBytes - lowMemoryThresholdBytes)
}

/**
 * Works out what this phone can run, by measuring it.
 *
 * A published table cannot answer this question: the same "8 GB" phone may have
 * 5.9 GB or 2.1 GB free depending on what the user has open, and a model that
 * does not fit is not slow — it is killed. Android's low-memory killer acts on
 * *free* RAM relative to `MemoryInfo.threshold`, so the probe works from that
 * budget and keeps a large reserve, rather than from `totalMem`, which no app
 * has ever been allowed to allocate.
 */
object DeviceCapability {

    /** Share of the usable headroom the model weights may take. See [probe]. */
    private const val WEIGHT_SHARE_OF_HEADROOM = 0.5

    /** Below this there is no point loading anything. */
    private const val MIN_BUDGET_BYTES = 200L * MB

    /**
     * How many times the weight budget must fit in the headroom before the GPU
     * is recommended. The OpenCL driver allocates its own buffers and copies
     * activations, so the GPU path costs noticeably more memory than the CPU one;
     * this is a conservative heuristic, not a measurement.
     */
    private const val GPU_HEADROOM_FACTOR = 3

    /** Free storage worth keeping for the app beyond the model file itself. */
    private const val STORAGE_RESERVE_BYTES = 500L * MB

    /** The ABIs for which LiteRT-LM publishes native code. */
    private val SUPPORTED_ABIS = setOf("arm64-v8a", "x86_64")

    /** Where a vendor OpenCL implementation is normally found. */
    private val OPEN_CL_PATHS = listOf(
        "/vendor/lib64/libOpenCL.so",
        "/vendor/lib/libOpenCL.so",
        "/system/lib64/libOpenCL.so",
        "/system/lib/libOpenCL.so",
    )

    fun probe(context: Context): DeviceProfile {
        val activityManager = context.getSystemService(ActivityManager::class.java)
        val memory = ActivityManager.MemoryInfo().also { activityManager?.getMemoryInfo(it) }

        val abis = Build.SUPPORTED_ABIS?.toList().orEmpty()
        val openCl = openClPresent(context)
        // The models live on the same volume as this directory, so this is the
        // free space that actually matters.
        val freeStorage = (context.getExternalFilesDir(null) ?: context.filesDir).usableSpace

        val headroom = max(0L, memory.availMem - memory.threshold)
        // Weights are a file mapping and the kernel can evict them, but the KV
        // cache, the activation buffers and everything the app already holds are
        // anonymous memory Android cannot reclaim. Spending half the headroom on
        // weights leaves the other half for those; the LMK does not send a
        // warning before it kills the process, so the reserve is deliberately
        // generous.
        val budget = (headroom * WEIGHT_SHARE_OF_HEADROOM).toLong()
        val sizeClass = ModelSizeClass.entries.lastOrNull { it.approxFileBytes <= budget }
            ?: ModelSizeClass.TINY

        val abiSupported = abis.any { it in SUPPORTED_ABIS }
        val runnable = abiSupported && budget >= MIN_BUDGET_BYTES
        val backend = if (
            openCl && runnable && headroom >= sizeClass.approxFileBytes * GPU_HEADROOM_FACTOR
        ) {
            LocalBackend.GPU
        } else {
            LocalBackend.CPU
        }

        val explanation = buildString {
            if (memory.totalMem == 0L) {
                append("Não foi possível medir a memória deste aparelho. ")
            }
            append("RAM livre ")
            append(formatBytes(memory.availMem))
            append(" de ")
            append(formatBytes(memory.totalMem))
            append("; o Android considera o app descartável abaixo de ")
            append(formatBytes(memory.threshold))
            append(" livres, então o espaço real de trabalho é ")
            append(formatBytes(headroom))
            append(". Guardando metade disso para a cache KV, as ativações e o resto do app, sobram ")
            append(formatBytes(budget))
            append(" para os pesos — a maior classe que cabe com folga é ")
            append(sizeClass.label)
            append(" (~")
            append(formatBytes(sizeClass.approxFileBytes))
            append(" em disco). ")

            append(
                when {
                    backend == LocalBackend.GPU ->
                        "Backend GPU: libOpenCL.so está presente e há RAM para as cópias do driver " +
                            "(${GPU_HEADROOM_FACTOR}x o tamanho do modelo). Se o driver falhar na " +
                            "inicialização, o backend cai sozinho para CPU."
                    !openCl ->
                        "Backend CPU: libOpenCL.so não foi encontrado nas pastas do sistema, " +
                            "então a GPU não pode ser usada."
                    else ->
                        "Backend CPU: com ${formatBytes(headroom)} livres não há folga para o driver " +
                            "OpenCL (seriam precisos ~${formatBytes(sizeClass.approxFileBytes * GPU_HEADROOM_FACTOR)}); " +
                            "a GPU responde mais rápido, mas custa mais memória."
                },
            )

            append(" Armazenamento livre: ")
            append(formatBytes(freeStorage))
            if (freeStorage < sizeClass.approxFileBytes + STORAGE_RESERVE_BYTES) {
                append(" (pouco para a classe recomendada)")
            }
            append(".")

            append(" ABIs: ")
            append(if (abis.isEmpty()) "desconhecidas" else abis.joinToString(", "))
            if (!abiSupported) {
                append(
                    " — nenhuma é arm64-v8a ou x86_64, e são as únicas para as quais o LiteRT-LM " +
                        "publica código nativo, então nenhum modelo vai rodar neste aparelho",
                )
            }
            append(".")

            append(" SoC: ")
            append(socName())
            append(" · Android ")
            append(Build.VERSION.RELEASE)
            append(" (API ")
            append(Build.VERSION.SDK_INT)
            append(")")
            if (activityManager?.isLowRamDevice == true) {
                append(" · aparelho marcado pelo sistema como de pouca RAM")
            }
            if (!runnable) {
                append(
                    " AVISO: mesmo o menor modelo recomendado não cabe com segurança; " +
                        "fechar outros apps libera RAM.",
                )
            }
        }

        return DeviceProfile(
            totalRamBytes = memory.totalMem,
            freeRamBytes = memory.availMem,
            lowMemoryThresholdBytes = memory.threshold,
            freeStorageBytes = freeStorage,
            abis = abis,
            soc = socName(),
            androidRelease = Build.VERSION.RELEASE,
            sdkInt = Build.VERSION.SDK_INT,
            lowRamDevice = activityManager?.isLowRamDevice == true,
            openClPresent = openCl,
            recommendation = ModelRecommendation(
                sizeClass = sizeClass,
                backend = backend,
                modelFileBudgetBytes = budget,
                runnable = runnable,
                explanation = explanation,
            ),
        )
    }

    /**
     * Best-effort: a false negative only costs the GPU backend, and the runtime
     * falls back to the CPU anyway. A false positive would be worse, so only
     * real files count — `System.loadLibrary` is not attempted from here.
     */
    private fun openClPresent(context: Context): Boolean {
        if (OPEN_CL_PATHS.any { File(it).isFile }) return true
        // A driver bundled with the app is extracted into the native lib dir.
        return File(context.applicationInfo.nativeLibraryDir, "libOpenCL.so").isFile
    }

    /**
     * `Build.SOC_MODEL` only exists from API 31, and reading it below that throws
     * `NoSuchFieldError` — it is not a compile-time constant, so the reference
     * survives compilation. Guarding on the SDK level is not optional here.
     */
    private fun socName(): String {
        val socModel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL else null
        return firstReal(socModel, Build.BOARD, Build.HARDWARE)
    }

    private fun firstReal(vararg candidates: String?): String =
        candidates.firstOrNull { !it.isNullOrBlank() && !it.equals("unknown", ignoreCase = true) }
            ?: "desconhecido"
}

/**
 * "1,2 GB" / "340 MB" in pt-BR, where the decimal separator is a comma.
 *
 * Used both here and by [ModelFile.sizeLabel] so a size never appears in two
 * different formats.
 */
fun formatBytes(value: Long): String {
    val locale = Locale.forLanguageTag("pt-BR")
    return when {
        value >= 1024L * MB -> java.lang.String.format(locale, "%.1f GB", value.toDouble() / (1024L * MB))
        value >= MB -> java.lang.String.format(locale, "%.0f MB", value.toDouble() / MB)
        else -> java.lang.String.format(locale, "%.0f KB", value.toDouble() / 1024)
    }
}

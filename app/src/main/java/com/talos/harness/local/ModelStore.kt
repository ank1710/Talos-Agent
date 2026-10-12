package com.talos.harness.local

import android.content.Context
import java.io.File

/** A model bundle installed on this device. */
data class ModelFile(
    val name: String,
    val bytes: Long,
    val path: String,
) {
    /** Size in the form a settings row would show it. */
    val sizeLabel: String get() = formatBytes(bytes)
}

/**
 * Finds the `.litertlm` bundles the agent can run.
 *
 * Files live in `getExternalFilesDir("models")` — app-specific *external*
 * storage. That location is the whole point: `adb push` can write there without
 * root, the app reads it with no runtime permission, the files survive an app
 * update, and uninstalling removes them. The alternatives are worse —
 * `MANAGE_EXTERNAL_STORAGE` or a Storage Access Framework picker would add a
 * permission prompt to a step that only happens once, on the developer's desk.
 *
 * Nothing here throws when there is no model: an empty list is the normal state
 * of a fresh install, and the caller decides what to tell the user.
 */
class ModelStore(private val context: Context) {

    /**
     * The directory models are read from. Created on first call so that an
     * `adb push` always has a target.
     *
     * `getExternalFilesDir` returns null while external storage is unmounted, so
     * this falls back to internal storage rather than crashing. Models pushed
     * there are only reachable through `run-as`, which is acceptable for a
     * degraded case that otherwise has no answer at all.
     */
    fun directory(): File {
        val dir = context.getExternalFilesDir(MODELS_DIR) ?: File(context.filesDir, MODELS_DIR)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /** Every `.litertlm` file in [directory], by name. Empty when none is installed. */
    fun installed(): List<ModelFile> = installedIn(directory())

    /**
     * Absolute path for [name], or null when nothing matches.
     *
     * Resolution order:
     * 1. a file in the models directory, by name with or without the extension
     *    (settings store whatever the user or an importer wrote);
     * 2. an absolute path that is already a real file, because an importer that
     *    copies from another volume is free to keep its own location.
     *
     * Step 2 accepts any path the app can read. That is not an injection surface:
     * the value comes from Talos's own settings, never from the model or the
     * network, and the app is already allowed to read its own storage.
     */
    fun pathFor(name: String): String? {
        val wanted = name.trim()
        if (wanted.isEmpty()) return null

        val files = installed()
        files.firstOrNull { it.name.equals(wanted, ignoreCase = true) }?.let { return it.path }
        files.firstOrNull { it.name.equals("$wanted$EXTENSION", ignoreCase = true) }
            ?.let { return it.path }

        val direct = File(wanted)
        if (direct.isAbsolute && direct.isFile) return direct.absolutePath
        return null
    }

    /**
     * The model to load when the caller has nothing better.
     *
     * With a [ModelRecommendation] this is the *largest* installed model that
     * fits the measured budget — the best quality the phone can actually hold.
     * When nothing fits, the smallest, because a slow answer beats none.
     *
     * Without a recommendation the choice is only "the one model" or, when
     * several are installed, the first by name: picking by size without
     * measuring RAM would be a guess dressed up as a decision.
     */
    fun default(recommendation: ModelRecommendation? = null): ModelFile? {
        val files = installed()
        if (files.isEmpty()) return null
        if (recommendation == null) return files.first()

        return files.filter { it.bytes <= recommendation.modelFileBudgetBytes }
            .maxByOrNull { it.bytes }
            ?: files.minByOrNull { it.bytes }
    }

    /** True when at least one model is present. */
    fun hasAny(): Boolean = installed().isNotEmpty()

    private fun installedIn(dir: File): List<ModelFile> =
        dir.listFiles()
            ?.filter { it.isFile && !it.name.startsWith(".") && it.name.endsWith(EXTENSION, ignoreCase = true) }
            ?.sortedBy { it.name.lowercase() }
            ?.map { ModelFile(name = it.name, bytes = it.length(), path = it.absolutePath) }
            .orEmpty()

    companion object {
        /** App-specific external directory that holds the models. */
        const val MODELS_DIR = "models"

        /** The bundle format LiteRT-LM loads. */
        const val EXTENSION = ".litertlm"
    }
}

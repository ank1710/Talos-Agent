package com.talos.harness.media

import android.annotation.TargetApi
import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * Turns a `content://` (or `file://`) image URI into a size-capped
 * `data:image/...;base64,...` URL that can be dropped straight into an LLM request body.
 *
 * Everything expensive happens on [Dispatchers.IO], every stream is closed, and every bitmap
 * this object creates is recycled before returning; the only retained state is a tiny LRU of
 * finished data URLs (see [cache]).
 */
object MediaResolver {

    /**
     * Hard cap on the raw (still encoded) input size. Anything larger is refused before it is
     * decoded, so a corrupt file, a mis-declared MIME type or a huge camera original can never
     * be turned into a multi-hundred-megabyte bitmap allocation.
     */
    const val MAX_INPUT_BYTES: Int = 25 * 1024 * 1024

    private const val DEFAULT_IMAGE_MIME = "image/jpeg"
    private const val PNG_MIME = "image/png"
    private const val MAX_CACHE_ENTRIES = 6
    private const val READ_BUFFER_BYTES = 64 * 1024

    /**
     * In-memory LRU of finished data URLs, keyed by `"$uri|$maxEdge|$quality"`.
     *
     * Why this exists: the agent loop rebuilds the *entire* wire transcript on every step, so the
     * same attached photo is re-serialized over and over. Without this cache each step would
     * re-open the stream, re-decode and re-encode (and re-base64) the identical bytes while the
     * user waits. Access order is enabled so the working set stays hot, and the map drops the
     * least recently used entry beyond [MAX_CACHE_ENTRIES] so a long session cannot grow without
     * bound. Every access is guarded by `synchronized(cache)` because [toDataUrl] may be invoked
     * from several coroutines at once.
     */
    private val cache = object : LinkedHashMap<String, String>(MAX_CACHE_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>): Boolean =
            size > MAX_CACHE_ENTRIES
    }

    /**
     * Reads [uri] (a `content://` or `file://` URI string), downscales it so the
     * longest edge is at most [maxEdge], re-encodes it, and returns a
     * `data:image/...;base64,...` URL. Returns null when the image cannot be read.
     *
     * The result is cached (see [cache]); a cache hit returns immediately without touching the
     * file. This function never throws for I/O, decode or allocation failures — it returns `null`
     * instead — with the single exception of coroutine cancellation, which is rethrown so that
     * structured concurrency keeps working (swallowing it would leak a hung step).
     *
     * @param maxEdge longest edge of the decoded image, in pixels. Values below 1 are clamped to 1.
     * @param quality JPEG quality in `1..100`; ignored for PNG.
     */
    suspend fun toDataUrl(
        context: Context,
        uri: String,
        maxEdge: Int = 1280,
        quality: Int = 80,
    ): String? = withContext(Dispatchers.IO) {
        val key = "$uri|$maxEdge|$quality"
        cacheGet(key)?.let { return@withContext it }

        val result = try {
            resolve(context, uri, maxEdge, quality)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (t: Throwable) {
            // Never let a bad URI, a dead provider, a decode failure or an OOM escape to the caller.
            null
        }

        if (result != null) cachePut(key, result)
        result
    }

    /** Approximate byte size of a data URL's decoded payload, for logging/limits. */
    fun approximateBytes(dataUrl: String): Int {
        val comma = dataUrl.indexOf(',')
        if (comma < 0 || comma >= dataUrl.lastIndex) return 0
        val payloadLength = dataUrl.length - comma - 1
        if (payloadLength <= 0) return 0

        var padding = 0
        var index = dataUrl.length - 1
        while (index > comma && padding < 2 && dataUrl[index] == '=') {
            padding++
            index--
        }

        // 4 base64 characters carry up to 3 bytes; each '=' pad removes one byte.
        val bytes = (payloadLength.toLong() * 3L) / 4L - padding.toLong()
        return when {
            bytes <= 0L -> 0
            bytes >= Int.MAX_VALUE.toLong() -> Int.MAX_VALUE
            else -> bytes.toInt()
        }
    }

    /** Drops the in-memory cache. */
    fun clearCache() {
        synchronized(cache) { cache.clear() }
    }

    /** True when the URI looks like an image the resolver should attempt. */
    fun isImageUri(context: Context, uri: String): Boolean = try {
        val mime = context.contentResolver.getType(Uri.parse(uri))
        mime != null && mime.startsWith("image/", ignoreCase = true)
    } catch (t: Throwable) {
        false
    }

    // ---------------------------------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------------------------------

    private fun resolve(context: Context, uri: String, maxEdge: Int, quality: Int): String? {
        val edge = maxEdge.coerceAtLeast(1)
        val jpegQuality = quality.coerceIn(1, 100)
        val resolver = context.contentResolver
        val parsed = Uri.parse(uri)
        val mime = resolver.getType(parsed) ?: DEFAULT_IMAGE_MIME

        val sourceBytes = readCappedBytes(resolver, parsed) ?: return null

        val decoded = decodeWithinEdge(sourceBytes, edge) ?: return null
        var scaled: Bitmap? = null
        try {
            scaled = scaleWithinEdge(decoded, edge)
            return encodeAsDataUrl(scaled, mime, jpegQuality)
        } finally {
            // Only recycle bitmaps this class created; scaleWithinEdge returns `decoded` itself
            // when no extra scaling was needed, so guard against double recycling.
            val scaledBitmap = scaled
            if (scaledBitmap != null && scaledBitmap !== decoded && !scaledBitmap.isRecycled) {
                scaledBitmap.recycle()
            }
            if (!decoded.isRecycled) decoded.recycle()
        }
    }

    /**
     * Reads at most [MAX_INPUT_BYTES] from [uri], returning null when the source exceeds the cap
     * or cannot be opened. The declared length is checked first (cheap, avoids touching the
     * stream at all); the actual read is independently counted because providers frequently
     * report [android.content.res.AssetFileDescriptor.UNKNOWN_LENGTH] or lie.
     */
    private fun readCappedBytes(resolver: ContentResolver, uri: Uri): ByteArray? {
        try {
            resolver.openAssetFileDescriptor(uri, "r")?.use { descriptor ->
                val declaredLength = descriptor.length
                if (declaredLength > MAX_INPUT_BYTES) return null
            }
        } catch (t: Throwable) {
            // Unknown length or a provider that does not support asset file descriptors:
            // fall through to the guarded streaming read below.
        }

        val input = try {
            resolver.openInputStream(uri)
        } catch (t: Throwable) {
            null
        } ?: return null

        input.use { stream ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(READ_BUFFER_BYTES)
            var total = 0
            while (true) {
                val read = stream.read(buffer)
                if (read == -1) break
                total += read
                if (total > MAX_INPUT_BYTES) return null
                out.write(buffer, 0, read)
            }
            return out.toByteArray()
        }
    }

    /**
     * Decodes [bytes] with the longest edge bounded by [maxEdge], preferring the smallest
     * allocation possible.
     */
    private fun decodeWithinEdge(bytes: ByteArray, maxEdge: Int): Bitmap? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            decodeWithImageDecoder(bytes, maxEdge)
        } else {
            decodeWithBitmapFactory(bytes, maxEdge)
        }

    /**
     * API 28+: [ImageDecoder] applies the EXIF orientation for us (camera photos arrive rotated
     * otherwise) and `setTargetSampleSize` is applied while the image is still being decoded, so a
     * 12 MP original is never fully materialized. `ALLOCATOR_SOFTWARE` is required because a
     * hardware-backed bitmap cannot be read back for compression.
     */
    @TargetApi(Build.VERSION_CODES.P)
    private fun decodeWithImageDecoder(bytes: ByteArray, maxEdge: Int): Bitmap? {
        val source = ImageDecoder.createSource(ByteBuffer.wrap(bytes))
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetSampleSize(
                calculateSampleSize(info.size.width, info.size.height, maxEdge),
            )
        }
    }

    /**
     * API 26–27 fallback. EXIF orientation is *not* applied here (that needs a library this module
     * does not depend on), so photos from some cameras may appear rotated on those two API levels;
     * bounds are read with `inJustDecodeBounds` first so `inSampleSize` can shrink the allocation.
     */
    private fun decodeWithBitmapFactory(bytes: ByteArray, maxEdge: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width <= 0 || height <= 0) return null

        val options = BitmapFactory.Options().apply {
            inSampleSize = calculateSampleSize(width, height, maxEdge)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    /** Largest power-of-two sample size whose result is still at least [maxEdge] on the long edge. */
    private fun calculateSampleSize(width: Int, height: Int, maxEdge: Int): Int {
        val longest = maxOf(width, height)
        if (longest <= maxEdge) return 1
        var sample = 1
        while (longest / (sample * 2) >= maxEdge) {
            sample *= 2
        }
        return sample
    }

    /**
     * Exact post-decode scaling for whatever the sampler left above [maxEdge]; returns [bitmap]
     * itself (no copy) when it is already small enough.
     */
    private fun scaleWithinEdge(bitmap: Bitmap, maxEdge: Int): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val longest = maxOf(width, height)
        if (longest <= maxEdge) return bitmap

        val ratio = maxEdge.toDouble() / longest.toDouble()
        val targetWidth = (width * ratio).toInt().coerceAtLeast(1)
        val targetHeight = (height * ratio).toInt().coerceAtLeast(1)
        if (targetWidth == width && targetHeight == height) return bitmap
        return Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
    }

    /**
     * Compresses [bitmap] and base64-encodes it into a `data:` URL: PNG when the source MIME type
     * was `image/png`, JPEG otherwise. A JPEG cannot store alpha, so an alpha-carrying bitmap is
     * first flattened onto white — otherwise transparent PNGs turn black.
     */
    private fun encodeAsDataUrl(bitmap: Bitmap, sourceMime: String, quality: Int): String? {
        val isPng = sourceMime.startsWith(PNG_MIME, ignoreCase = true)
        val format = if (isPng) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
        val outputMime = if (isPng) PNG_MIME else DEFAULT_IMAGE_MIME

        var flattened: Bitmap? = null
        val payload: ByteArray = try {
            val target = if (!isPng && bitmap.hasAlpha()) {
                flattenOntoWhite(bitmap).also { flattened = it }
            } else {
                bitmap
            }
            val stream = ByteArrayOutputStream()
            if (!target.compress(format, quality, stream)) return null
            stream.toByteArray()
        } finally {
            flattened?.let { if (!it.isRecycled) it.recycle() }
        }

        if (payload.isEmpty()) return null
        val encoded = Base64.encodeToString(payload, Base64.NO_WRAP)
        return "data:$outputMime;base64,$encoded"
    }

    /** Opaque copy of [bitmap] drawn over a solid white background. */
    private fun flattenOntoWhite(bitmap: Bitmap): Bitmap {
        val opaque = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(opaque)
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(bitmap, 0f, 0f, null)
        return opaque
    }

    private fun cacheGet(key: String): String? = synchronized(cache) { cache[key] }

    private fun cachePut(key: String, value: String) {
        synchronized(cache) { cache[key] = value }
    }
}

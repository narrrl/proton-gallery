package io.narl.protongallery

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.media.ThumbnailUtils
import android.os.CancellationSignal
import android.util.LruCache
import android.util.Size
import io.narl.protongallery.bridge.GalleryItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * Grid thumbnails in two tiers: decoded bitmaps in memory, and small WebP
 * files on disk, so a photo seen once shows at once afterwards, also after a
 * restart and without a network. Grid requests never decode the original.
 *
 * Thumbnails of hidden albums are private: they stay in memory only, are never
 * written to disk, and [forgetPrivate] drops them when hidden albums lock.
 */
class ThumbnailLoader(private val directory: File,
    private val remote: suspend (GalleryItem) -> ByteArray? = { null },
    private val rememberShape: suspend (GalleryItem, Int, Int) -> Unit = { _, _, _ -> },
    // The system's own thumbnail of a file on this phone; offline, but slower than ours.
    private val phone: (GalleryItem) -> Bitmap? = { null },
) {
    // Shown tiles and read-ahead use separate threads, so read-ahead never
    // holds up what is on screen.
    private val shown = Dispatchers.IO.limitedParallelism(4)
    private val ahead = Dispatchers.IO.limitedParallelism(2)
    private val locks = Array(64) { Mutex() }
    private val memory = object : LruCache<String, Bitmap>(memoryBudget()) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    private val private = java.util.Collections.synchronizedSet(HashSet<String>())
    private val scope = CoroutineScope(SupervisorJob() + ahead)
    private var reading: Job? = null

    /** The thumbnail if it is in memory; never loads. Lets a tile show its
     * photo in its first frame instead of a placeholder. */
    fun peek(item: GalleryItem): Bitmap? = memory.get(key(item))

    suspend fun load(item: GalleryItem, private: Boolean = false): Bitmap? = withContext(shown) { obtain(item, private) }

    /** Read `items` into memory ahead of the grid, in order, replacing the
     * previous read-ahead. Remote ones are fetched too. */
    fun prefetch(items: List<GalleryItem>, private: Boolean = false) {
        reading?.cancel()
        if (items.isEmpty()) return
        reading = scope.launch {
            for (item in items) {
                if (memory.get(key(item)) != null) continue
                obtain(item, private)
            }
        }
    }

    /** Hidden albums locked: drop their thumbnails from memory. */
    fun forgetPrivate() {
        reading?.cancel()
        val keys = synchronized(private) { private.toList().also { private.clear() } }
        for (key in keys) memory.remove(key)
    }

    private suspend fun obtain(item: GalleryItem, private: Boolean): Bitmap? {
        val cacheKey = key(item)
        memory.get(cacheKey)?.let { return it }
        return locks[(cacheKey.hashCode() and Int.MAX_VALUE) % locks.size].withLock {
            memory.get(cacheKey)?.let { return@withLock it }
            currentCoroutineContext().ensureActive()
            try {
                val file = File(directory, fileName(cacheKey))
                val cached = if (file.isFile) {
                    try { ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, _, _ -> decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE } }
                    catch (_: Exception) { file.delete(); null }
                } else null
                if (cached != null) {
                    // Recently used files are the last to be trimmed.
                    file.setLastModified(System.currentTimeMillis())
                    remember(cacheKey, cached, private)
                    return@withLock cached
                }
                val decoded = decode(item) ?: return@withLock null
                currentCoroutineContext().ensureActive()
                remember(cacheKey, decoded, private)
                if (!private) store(file, decoded)
                decoded
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { null }
        }
    }

    private suspend fun decode(item: GalleryItem): Bitmap? {
        if (item.phoneId != null) {
            runCatching { phone(item) }.getOrNull()?.let { return it }
            if (item.remoteKey == null) return null
        }
        if (item.remoteKey != null) {
            val bytes = remote(item) ?: return null
            try {
                val decoded = decodeBytes(bytes, EDGE)
                if (item.aspectRatio == null) runCatching { rememberShape(item, decoded.width, decoded.height) }
                return decoded
            } finally { bytes.fill(0) }
        }
        if (item.localPath.isEmpty()) return null
        return if (item.isVideo) {
            val ratio = item.aspectRatio?.takeIf { it.isFinite() && it > 0 } ?: 1.0
            val size = if (ratio >= 1) Size(EDGE, (EDGE / ratio).toInt().coerceAtLeast(1)) else Size((EDGE * ratio).toInt().coerceAtLeast(1), EDGE)
            ThumbnailUtils.createVideoThumbnail(File(item.localPath), size, CancellationSignal())
        } else decodeImage(File(item.localPath), EDGE)
    }

    private fun remember(cacheKey: String, bitmap: Bitmap, private: Boolean) {
        if (private) this.private.add(cacheKey)
        memory.put(cacheKey, bitmap)
    }

    // A failed write must not hide the decoded image.
    private fun store(file: File, bitmap: Bitmap) = runCatching {
        directory.mkdirs()
        val temp = File.createTempFile("thumb", ".tmp", directory)
        try {
            temp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, QUALITY, it) }
            if (!temp.renameTo(file)) temp.delete()
        } finally { temp.delete() }
        trimDisk()
    }

    private var writesSinceTrim = 100
    @Synchronized private fun trimDisk() {
        if (++writesSinceTrim < 64) return
        writesSinceTrim = 0
        val files = directory.listFiles()?.filter { it.extension == "webp" }?.sortedBy { it.lastModified() } ?: return
        var bytes = files.sumOf { it.length() }
        for (file in files) {
            if (bytes <= DISK_BUDGET) break
            val length = file.length()
            if (file.delete()) bytes -= length
        }
    }

    companion object {
        /** Longest edge of a grid thumbnail in pixels: sharp at the default
         * tile height, and about 10 to 20 KB as WebP. */
        const val EDGE = 320
        private const val QUALITY = 72
        private const val DISK_BUDGET = 256L * 1024 * 1024

        private fun key(item: GalleryItem) = item.id + (item.revision?.let { ":$it" } ?: "")

        // Ids and revisions may hold characters a file name cannot.
        private fun fileName(key: String): String =
            MessageDigest.getInstance("SHA-1").digest(key.toByteArray()).joinToString("") { "%02x".format(it) } + ".webp"

        /** A sixth of the app's memory, between 24 and 160 MB: several screens of tiles. */
        private fun memoryBudget(): Int = (Runtime.getRuntime().maxMemory() / 6).coerceIn(24L shl 20, 160L shl 20).toInt()

        fun decodeUri(context: android.content.Context, uri: android.net.Uri, longestEdge: Int): Bitmap =
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
                val scale = minOf(1.0, longestEdge.toDouble() / maxOf(info.size.width, info.size.height))
                decoder.setTargetSize(maxOf(1, (info.size.width * scale).toInt()), maxOf(1, (info.size.height * scale).toInt()))
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }

        fun decodeBytes(bytes: ByteArray, longestEdge: Int): Bitmap =
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(java.nio.ByteBuffer.wrap(bytes))) { decoder, info, _ ->
                val scale = minOf(1.0, longestEdge.toDouble() / maxOf(info.size.width, info.size.height))
                decoder.setTargetSize(maxOf(1, (info.size.width * scale).toInt()), maxOf(1, (info.size.height * scale).toInt()))
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }

        fun decodeImage(file: File, longestEdge: Int): Bitmap =
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
                val scale = minOf(1.0, longestEdge.toDouble() / maxOf(info.size.width, info.size.height))
                decoder.setTargetSize(maxOf(1, (info.size.width * scale).toInt()), maxOf(1, (info.size.height * scale).toInt()))
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
    }
}

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
import kotlinx.coroutines.delay
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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
    private class LoadLock(val mutex: Mutex = Mutex(), var users: Int = 0)
    private val locks = mutableMapOf<String, LoadLock>()
    // Remote fetches suspend and cancel with their tile, so a tile that
    // scrolls away frees its slot at once. Read-ahead has slots of its own
    // and never delays what is on screen.
    private val shownSlots = Semaphore(SHOWN_FETCHES)
    private val aheadSlots = Semaphore(AHEAD_FETCHES)
    private val memory = object : LruCache<String, Bitmap>(memoryBudget()) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    private var privateGeneration = 0L
    private val scope = CoroutineScope(SupervisorJob() + ahead)
    private var reading: Job? = null

    /** The thumbnail if it is in memory; never loads. Lets a tile show its
     * photo in its first frame instead of a placeholder. */
    fun peek(item: GalleryItem, private: Boolean = false): Bitmap? = memory.get(key(item, private))

    suspend fun load(item: GalleryItem, private: Boolean = false): Bitmap? = withContext(shown) {
        val generation = synchronized(memory) { privateGeneration }
        repeat(3) { attempt ->
            try { return@withContext obtain(item, private, generation, shownSlots) }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) {
                if (attempt < 2) delay(350L * (attempt + 1))
            }
        }
        null
    }

    /** Read ahead in order, [AHEAD_FETCHES] items at a time. */
    fun prefetch(items: List<GalleryItem>, private: Boolean = false) {
        reading?.cancel()
        if (items.isEmpty()) return
        val generation = synchronized(memory) { privateGeneration }
        reading = scope.launch {
            val started = Semaphore(AHEAD_FETCHES)
            for (item in items) {
                if (peek(item, private) != null) continue
                started.acquire()
                launch {
                    try { obtain(item, private, generation, aheadSlots) }
                    catch (cancel: CancellationException) { throw cancel }
                    catch (_: Exception) {}
                    finally { started.release() }
                }
            }
        }
    }

    /** No longer shown (another account, or visibility changed): stop reading
     * ahead and free the memory tier now instead of when it is collected. */
    fun close() {
        scope.cancel()
        memory.evictAll()
    }

    /** Hidden albums locked: drop their thumbnails and discard in-flight results. */
    fun forgetPrivate() {
        reading?.cancel()
        synchronized(memory) {
            privateGeneration++
            memory.snapshot().keys.filter { it.startsWith(PRIVATE_PREFIX) }.forEach(memory::remove)
        }
    }

    private suspend fun obtain(item: GalleryItem, private: Boolean, generation: Long, slots: Semaphore): Bitmap? {
        val cacheKey = key(item, private)
        synchronized(memory) {
            if (private && generation != privateGeneration) return null
            memory.get(cacheKey)?.let { return it }
        }
        val lock = synchronized(locks) {
            locks.getOrPut(cacheKey) { LoadLock() }.also { it.users++ }
        }
        try {
            return lock.mutex.withLock {
                synchronized(memory) {
                    if (private && generation != privateGeneration) return@withLock null
                    memory.get(cacheKey)?.let { return@withLock it }
                }
                currentCoroutineContext().ensureActive()
                val file = File(directory, fileName(cacheKey))
                // Only shown, never read back: the default allocator puts it in
                // graphics memory instead of the Java heap where it can.
                val cached = if (!private && file.isFile) {
                    try { ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, _, _ -> decoder.allocator = ImageDecoder.ALLOCATOR_DEFAULT } }
                    catch (_: Exception) { file.delete(); null }
                } else null
                if (cached != null) {
                    file.setLastModified(System.currentTimeMillis())
                    memory.put(cacheKey, cached)
                    return@withLock cached
                }
                // Decoded in software so it can be written to disk as WebP;
                // memory keeps a hardware copy for display.
                val decoded = decode(item, slots) ?: return@withLock null
                currentCoroutineContext().ensureActive()
                val shown = forDisplay(decoded)
                synchronized(memory) {
                    if (private && generation != privateGeneration) return@withLock null
                    memory.put(cacheKey, shown)
                }
                if (!private) store(file, decoded)
                shown
            }
        } finally {
            synchronized(locks) {
                lock.users--
                if (lock.users == 0) locks.remove(cacheKey)
            }
        }
    }

    private suspend fun decode(item: GalleryItem, slots: Semaphore): Bitmap? {
        if (item.phoneId != null) {
            runCatching { phone(item) }.getOrNull()?.let { return it }
            if (item.remoteKey == null) return null
        }
        if (item.remoteKey != null) {
            val bytes = slots.withPermit { remote(item) } ?: return null
            try {
                val decoded = decodeBytes(bytes, EDGE)
                if (item.aspectRatio == null) scope.launch {
                    try { rememberShape(item, decoded.width, decoded.height) }
                    catch (cancel: CancellationException) { throw cancel }
                    catch (_: Exception) {}
                }
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

    // A hardware copy keeps grid thumbnails off the Java heap. The software
    // original is still needed once, to write the disk tier.
    private fun forDisplay(bitmap: Bitmap): Bitmap =
        if (bitmap.config == Bitmap.Config.HARDWARE) bitmap
        else runCatching { bitmap.copy(Bitmap.Config.HARDWARE, false) }.getOrNull() ?: bitmap

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
        /** Remote fetches at once for tiles on screen, and for read-ahead. */
        const val SHOWN_FETCHES = 6
        const val AHEAD_FETCHES = 2
        private const val QUALITY = 72
        private const val DISK_BUDGET = 256L * 1024 * 1024

        private const val PRIVATE_PREFIX = "private/"
        private fun key(item: GalleryItem, private: Boolean): String {
            val prefix = if (private) PRIVATE_PREFIX else ""
            return prefix + item.id + (item.revision?.let { ":$it" } ?: "")
        }

        // Ids and revisions may hold characters a file name cannot.
        private fun fileName(key: String): String =
            MessageDigest.getInstance("SHA-1").digest(key.toByteArray()).joinToString("") { "%02x".format(it) } + ".webp"

        /** A sixth of the app's memory, between 24 and 160 MB: several screens of tiles. */
        private fun memoryBudget(): Int = (Runtime.getRuntime().maxMemory() / 6).coerceIn(24L shl 20, 160L shl 20).toInt()

        /** The longest edge of the screen: a photo shown whole never needs
         * more pixels than that. */
        fun screenEdge(context: android.content.Context): Int {
            val metrics = context.resources.displayMetrics
            return maxOf(metrics.widthPixels, metrics.heightPixels).coerceIn(1024, 4096)
        }

        // Pass ImageDecoder.ALLOCATOR_DEFAULT for bitmaps that are only shown,
        // never read back or encoded: they then live in graphics memory.
        fun decodeUri(context: android.content.Context, uri: android.net.Uri, longestEdge: Int,
            allocator: Int = ImageDecoder.ALLOCATOR_SOFTWARE): Bitmap =
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
                val scale = minOf(1.0, longestEdge.toDouble() / maxOf(info.size.width, info.size.height))
                decoder.setTargetSize(maxOf(1, (info.size.width * scale).toInt()), maxOf(1, (info.size.height * scale).toInt()))
                decoder.allocator = allocator
            }

        fun decodeBytes(bytes: ByteArray, longestEdge: Int, allocator: Int = ImageDecoder.ALLOCATOR_SOFTWARE): Bitmap =
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(java.nio.ByteBuffer.wrap(bytes))) { decoder, info, _ ->
                val scale = minOf(1.0, longestEdge.toDouble() / maxOf(info.size.width, info.size.height))
                decoder.setTargetSize(maxOf(1, (info.size.width * scale).toInt()), maxOf(1, (info.size.height * scale).toInt()))
                decoder.allocator = allocator
            }

        fun decodeImage(file: File, longestEdge: Int, allocator: Int = ImageDecoder.ALLOCATOR_SOFTWARE): Bitmap =
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
                val scale = minOf(1.0, longestEdge.toDouble() / maxOf(info.size.width, info.size.height))
                decoder.setTargetSize(maxOf(1, (info.size.width * scale).toInt()), maxOf(1, (info.size.height * scale).toInt()))
                decoder.allocator = allocator
            }
    }
}

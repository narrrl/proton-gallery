package io.narl.protongallery

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.media.ThumbnailUtils
import android.os.CancellationSignal
import android.util.LruCache
import android.util.Size
import io.narl.protongallery.bridge.GalleryItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** Two decodes at once, a byte-bounded memory cache, and a bounded disk cache.
 * Grid requests never decode the full original. Android handles EXIF rotation.
 */
class ThumbnailLoader(private val directory: File,
    private val remote: suspend (GalleryItem) -> ByteArray? = { null },
    private val rememberShape: suspend (GalleryItem, Int, Int) -> Unit = { _, _, _ -> },
) {
    private val dispatcher = Dispatchers.IO.limitedParallelism(2)
    private val locks = Array(32) { Mutex() }
    private val memory = object : LruCache<String, Bitmap>(20 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }

    suspend fun load(item: GalleryItem): Bitmap? = withContext(dispatcher) {
        val cacheKey = item.id + (item.revision?.let { ":$it" } ?: "")
        memory.get(cacheKey)?.let { return@withContext it }
        locks[(item.id.hashCode() and Int.MAX_VALUE) % locks.size].withLock {
            memory.get(cacheKey)?.let { return@withLock it }
            currentCoroutineContext().ensureActive()
            try {
                if (item.remoteKey != null) {
                    val bytes = remote(item) ?: return@withLock null
                    try {
                        val decoded = ImageDecoder.decodeBitmap(ImageDecoder.createSource(java.nio.ByteBuffer.wrap(bytes))) { decoder, info, _ ->
                            val scale = minOf(1.0, 384.0 / maxOf(info.size.width, info.size.height))
                            decoder.setTargetSize(maxOf(1, (info.size.width * scale).toInt()), maxOf(1, (info.size.height * scale).toInt()))
                            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                        }
                        currentCoroutineContext().ensureActive()
                        memory.put(cacheKey, decoded)
                        if (item.aspectRatio == null) runCatching { rememberShape(item, decoded.width, decoded.height) }
                        return@withLock decoded
                    } finally { bytes.fill(0) }
                }
                directory.mkdirs()
                val cached = File(directory, "${item.id}.webp")
                val bitmap = try {
                    if (cached.isFile) ImageDecoder.decodeBitmap(ImageDecoder.createSource(cached)) else null
                } catch (_: Exception) { cached.delete(); null }
                val decoded = bitmap ?: if (item.isVideo) {
                    val ratio = item.aspectRatio?.takeIf { it.isFinite() && it > 0 } ?: 1.0
                    val size = if (ratio >= 1) Size(384, (384 / ratio).toInt().coerceAtLeast(1)) else Size((384 * ratio).toInt().coerceAtLeast(1), 384)
                    ThumbnailUtils.createVideoThumbnail(File(item.localPath), size, CancellationSignal())
                } else {
                    decodeImage(File(item.localPath), 384)
                }
                currentCoroutineContext().ensureActive()
                memory.put(cacheKey, decoded)
                if (bitmap == null) {
                    // A failed thumbnail write must not hide the decoded image.
                    runCatching {
                        val temp = File.createTempFile("thumb", ".tmp", directory)
                        try {
                            temp.outputStream().use { decoded.compress(Bitmap.CompressFormat.WEBP_LOSSY, 82, it) }
                            if (!temp.renameTo(cached)) temp.delete()
                        } finally { temp.delete() }
                        trimDisk()
                    }
                }
                decoded
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { null }
        }
    }

    private var writesSinceTrim = 100
    @Synchronized private fun trimDisk() {
        if (++writesSinceTrim < 32) return
        writesSinceTrim = 0
        val files = directory.listFiles()?.filter { it.extension == "webp" }?.sortedBy { it.lastModified() } ?: return
        var bytes = files.sumOf { it.length() }
        for (file in files) {
            if (bytes <= 128L * 1024 * 1024) break
            val length = file.length()
            if (file.delete()) bytes -= length
        }
    }

    companion object {
        fun decodeImage(file: File, longestEdge: Int): Bitmap =
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
                val scale = minOf(1.0, longestEdge.toDouble() / maxOf(info.size.width, info.size.height))
                decoder.setTargetSize(maxOf(1, (info.size.width * scale).toInt()), maxOf(1, (info.size.height * scale).toInt()))
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
    }
}

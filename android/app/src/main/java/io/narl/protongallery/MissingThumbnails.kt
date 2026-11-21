package io.narl.protongallery

import android.graphics.Bitmap
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import io.narl.protongallery.bridge.GalleryItem
import io.narl.protongallery.bridge.VideoStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Grid thumbnails for Proton photos uploaded without one, made from the
 * original. Some uploaders never attach thumbnails, and Proton cannot add one
 * to a stored photo, so this phone makes its own.
 *
 * Returns null when the photo can never have one here: too large to download
 * for a thumbnail, or not something Android can decode. Throws when a later
 * try may work, such as on a network error.
 */
object MissingThumbnails {
    /** Largest image downloaded whole to make a thumbnail of. */
    const val MAX_IMAGE_BYTES = 40L shl 20
    /** Where in a video its thumbnail frame is taken: past a black lead-in. */
    private const val FRAME_MICROS = 1_000_000L

    suspend fun make(repository: GalleryRepository, item: GalleryItem, sources: File): Bitmap? {
        if (item.remoteKey == null) return null
        return if (item.isVideo) videoFrame(repository, item) else image(repository, item, sources)
    }

    private suspend fun image(repository: GalleryRepository, item: GalleryItem, sources: File): Bitmap? {
        if (item.sizeBytes.toLong() > MAX_IMAGE_BYTES) return null
        sources.mkdirs()
        val file = repository.downloadOriginal(item, sources)
        return try {
            withContext(Dispatchers.IO) { runCatching { ThumbnailLoader.decodeImage(file, ThumbnailLoader.EDGE) }.getOrNull() }
        } finally {
            withContext(Dispatchers.IO) { file.delete() }
        }
    }

    /** One frame, read straight from the stream: the retriever fetches only
     * the parts of the video it needs, and nothing is written to disk. */
    private suspend fun videoFrame(repository: GalleryRepository, item: GalleryItem): Bitmap? {
        val stream = repository.openVideo(item)
        val source = StreamSource(stream)
        return try {
            withContext(Dispatchers.IO) {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(source)
                    retriever.getScaledFrameAtTime(FRAME_MICROS, MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                        ThumbnailLoader.EDGE, ThumbnailLoader.EDGE)
                } catch (error: RuntimeException) {
                    if (source.failed) throw IOException("video read failed", error)
                    null
                } finally {
                    retriever.release()
                }
            }?.let { frame ->
                if (frame.config == Bitmap.Config.ARGB_8888) frame
                else frame.copy(Bitmap.Config.ARGB_8888, false).also { frame.recycle() }
            } ?: if (source.failed) throw IOException("video read failed") else null
        } finally {
            stream.stop()
            stream.destroy()
        }
    }

    private class StreamSource(private val stream: VideoStream) : MediaDataSource() {
        @Volatile var failed = false
        private val length = stream.length().toLong()

        override fun getSize() = length

        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (position >= length) return -1
            val bytes = try { stream.read(position.toULong(), size.toUInt()) }
            catch (error: Exception) { failed = true; throw IOException(error) }
            if (bytes.isEmpty()) return -1
            val count = minOf(bytes.size, size)
            bytes.copyInto(buffer, offset, 0, count)
            return count
        }

        override fun close() {}
    }
}

package io.narl.protongallery

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import io.narl.protongallery.bridge.GalleryException
import io.narl.protongallery.bridge.VideoStream
import java.io.IOException

/**
 * Media3 reads a Proton video through this: each read is a byte range that
 * Rust serves from the video's decrypted blocks, so playback and seeking
 * start before the whole file has arrived. Reads block the player's loading
 * thread, which is what Media3 expects of a data source.
 */
@UnstableApi
class ProtonDataSource(private val stream: VideoStream) : BaseDataSource(/* isNetwork = */ true) {
    private var uri: Uri? = null
    private var position = 0L
    private var remaining = 0L
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        val length = stream.length().toLong()
        if (dataSpec.position > length) throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        position = dataSpec.position
        remaining = if (dataSpec.length == C.LENGTH_UNSET.toLong()) length - position else minOf(dataSpec.length, length - position)
        opened = true
        transferStarted(dataSpec)
        return remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val bytes = try {
            stream.read(position.toULong(), minOf(length.toLong(), remaining).toUInt())
        } catch (error: GalleryException) {
            throw IOException(error.message, error)
        }
        if (bytes.isEmpty()) return C.RESULT_END_OF_INPUT
        System.arraycopy(bytes, 0, buffer, offset, bytes.size)
        position += bytes.size
        remaining -= bytes.size
        bytesTransferred(bytes.size)
        return bytes.size
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        uri = null
        if (opened) {
            opened = false
            transferEnded()
        }
    }
}

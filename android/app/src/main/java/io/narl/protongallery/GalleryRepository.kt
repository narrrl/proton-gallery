package io.narl.protongallery

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.paging.PagingSource
import androidx.paging.PagingState
import io.narl.protongallery.bridge.GalleryEngine
import io.narl.protongallery.bridge.GalleryItem
import io.narl.protongallery.bridge.LibraryView
import io.narl.protongallery.bridge.AlbumOptions
import io.narl.protongallery.bridge.MediaReader
import io.narl.protongallery.bridge.PhoneHash
import io.narl.protongallery.bridge.PhoneMedia
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import android.media.MediaMetadataRetriever
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.util.UUID

/**
 * Threads for calls into Rust. Each call blocks on async Rust code, which needs
 * more stack than Android's default for pooled threads (about 1 MB).
 */
private val Native = run {
    val count = java.util.concurrent.atomic.AtomicInteger()
    java.util.concurrent.Executors.newFixedThreadPool(8) { task ->
        Thread(null, task, "pg-native-${count.incrementAndGet()}", 8L shl 20).apply { isDaemon = true }
    }.asCoroutineDispatcher()
}

data class PageKey(val cursor: String?, val backwards: Boolean, val refreshCursor: String? = null, val refreshBackwards: Boolean = false)

/** Byte streams stay on disk; typed metadata is the only FFI payload. */
class GalleryRepository private constructor(private val context: Context) {
    companion object {
        @Volatile private var shared: GalleryRepository? = null
        private val creating = Mutex()

        /** One engine per process: the screens and background backup share it. */
        suspend fun get(context: Context): GalleryRepository = shared ?: creating.withLock {
            shared ?: withContext(Dispatchers.IO) { GalleryRepository(context.applicationContext) }.also { shared = it }
        }
    }

    private val session = Mutex()
    private var restored = false

    /** Restore the saved sign-in once per process. */
    suspend fun ensureSession() = session.withLock {
        if (!restored) { restoreSession(); restored = true }
    }

    // Constructed on IO, never while composing or on the Android main thread.
    private val engine: GalleryEngine
    init {
        PlatformTls.ensure(context)
        engine = GalleryEngine.openWithVault(File(context.noBackupFilesDir, "gallery").path, SessionStorage(context))
    }
    suspend fun restoreSession() = withContext(Native) { engine.restoreSession() }
    suspend fun accountStatus() = withContext(Native) { engine.accountStatus() }
    suspend fun signIn(username: String, password: String) = withContext(Native) { engine.signIn(username, password) }
    suspend fun secondFactor(code: String) = withContext(Native) { engine.submitSecondFactor(code) }
    suspend fun mailboxPassword(password: String) = withContext(Native) { engine.submitMailboxPassword(password) }
    suspend fun verification(token: String) = withContext(Native) { engine.submitVerification(token) }
    suspend fun connectionReport() = withContext(Native) { engine.connectionReport() }
    suspend fun cancelSignIn() = withContext(Native) { engine.cancelSignIn() }
    suspend fun signOut() = withContext(Native) { engine.signOut() }
    suspend fun syncPhotos() = withContext(Native) { engine.syncPhotos() }
    suspend fun driveProgress() = withContext(Native) { engine.driveProgress() }
    suspend fun syncProgress() = withContext(Native) { engine.syncProgress() }
    suspend fun thumbnail(item: GalleryItem): ByteArray? = withContext(Native) { item.remoteKey?.let(engine::photoThumbnail) }
    suspend fun rememberShape(item: GalleryItem, width: Int, height: Int) = withContext(Native) {
        item.remoteKey?.let { engine.rememberPhotoShape(it, width.toUInt(), height.toUInt()) }
    }

    fun pagingSource(view: LibraryView) = object : PagingSource<PageKey, GalleryItem>() {
        override suspend fun load(params: LoadParams<PageKey>): LoadResult<PageKey, GalleryItem> =
            withContext(Native) {
                try {
                    val page = engine.pageAt(view, params.key?.cursor, params.key?.backwards ?: false, params.loadSize.coerceAtMost(250).toUInt())
                    LoadResult.Page(page.items,
                        page.previous?.let { PageKey(it, true, params.key?.cursor, params.key?.backwards ?: false) },
                        page.next?.let { PageKey(it, false, params.key?.cursor, params.key?.backwards ?: false) })
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (error: Exception) {
                    LoadResult.Error(error)
                }
            }

        // The loaded page's preceding seek returns the same neighborhood after
        // refresh, including after older pages were dropped from memory.
        override fun getRefreshKey(state: PagingState<PageKey, GalleryItem>): PageKey? {
            val anchor = state.anchorPosition ?: return null
            val page = state.closestPageToPosition(anchor) ?: return null
            val key = page.prevKey ?: page.nextKey ?: return null
            return PageKey(key.refreshCursor, key.refreshBackwards)
        }
    }

    suspend fun phoneIndex(scan: Long, items: List<PhoneMedia>) = withContext(Native) { engine.phoneIndex(scan, items) }
    suspend fun phoneFinish(scan: Long) = withContext(Native) { engine.phoneFinish(scan) }
    suspend fun phoneFolders() = withContext(Native) { engine.phoneFolders() }
    suspend fun setBackupFolder(folder: String, enabled: Boolean) = withContext(Native) { engine.setBackupFolder(folder, enabled) }
    suspend fun backupQueue(limit: Int) = withContext(Native) { engine.backupQueue(limit.toUInt()) }
    suspend fun checkBackup() = withContext(Native) { engine.checkBackup() }
    suspend fun setPhoneHashes(hashes: List<PhoneHash>) = withContext(Native) { engine.setPhoneHashes(hashes) }
    suspend fun uploadPhone(id: String, sha1: String, reader: MediaReader, thumbnail: ByteArray?) =
        withContext(Native) { engine.uploadPhone(id, sha1, reader, thumbnail) }

    suspend fun totals() = withContext(Native) { engine.totals() }
    suspend fun count(location: GalleryLocation) = withContext(Native) { location.nativeView()?.let(engine::countAt)?.toLong() ?: 0L }
    suspend fun albums(location: GalleryLocation) = withContext(Native) {
        if (location.section != GallerySection.Drive) emptyList()
        else engine.albums(location.album?.gallery, location.album?.id)
    }
    suspend fun options(location: GalleryLocation): AlbumOptions? = withContext(Native) { location.nativeView()?.let(engine::viewOptions) }
    suspend fun setOptions(location: GalleryLocation, options: AlbumOptions): AlbumOptions? = withContext(Native) {
        location.nativeView()?.let { engine.setViewOptions(it, options) }
    }
    suspend fun albumTags() = withContext(Native) { engine.albumTags() }
    suspend fun taggedAlbums(tag: String) = withContext(Native) { engine.taggedAlbums(tag) }
    suspend fun driveFolders(parent: String?) = withContext(Native) { engine.driveFolders(parent) }
    suspend fun addDriveGallery(folder: String) = withContext(Native) { engine.addDriveGallery(folder) }
    suspend fun refreshDriveGallery(gallery: String) = withContext(Native) { engine.refreshDriveGallery(gallery) }
    suspend fun removeDriveGallery(gallery: String) = withContext(Native) { engine.removeDriveGallery(gallery) }

    suspend fun import(uri: Uri) = withContext(Native) {
        val resolver = context.contentResolver
        var name = "Photo"
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) name = cursor.getString(0) ?: name
        }
        val mime = resolver.getType(uri) ?: "application/octet-stream"
        val staged = File(engine.incomingDirectory(), UUID.randomUUID().toString())
        try {
            resolver.openInputStream(uri)?.use { input ->
                staged.outputStream().use { output ->
                    val buffer = ByteArray(65_536)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                    }
                }
            } ?: error("The selected photo or video could not be opened.")
            // Import time is deliberately a fallback, not a capture-date claim.
            val shape = try {
                if (mime.startsWith("video/")) MediaMetadataRetriever().use { retriever ->
                    retriever.setDataSource(staged.path)
                    val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                    val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                    val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                    if (rotation % 180 != 0) height to width else width to height
                } else {
                    val preview = ThumbnailLoader.decodeImage(staged, 384)
                    (preview.width to preview.height).also { preview.recycle() }
                }
            } catch (_: Exception) { 0 to 0 }
            val known = shape.first > 0 && shape.second > 0
            engine.importFileWithShape(staged.path, name, mime, System.currentTimeMillis() / 1000,
                if (known) shape.first.toUInt() else 0u, if (known) shape.second.toUInt() else 0u)
        } finally {
            staged.delete()
        }
    }
}

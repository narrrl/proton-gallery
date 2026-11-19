package io.narl.protongallery

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import io.narl.protongallery.bridge.GalleryEngine
import io.narl.protongallery.bridge.GalleryException
import io.narl.protongallery.bridge.FolderAlbum
import io.narl.protongallery.bridge.GalleryItem
import io.narl.protongallery.bridge.VideoStream
import io.narl.protongallery.bridge.LibraryView
import io.narl.protongallery.bridge.AlbumOptions
import io.narl.protongallery.bridge.MediaReader
import io.narl.protongallery.bridge.PhoneHash
import io.narl.protongallery.bridge.PhoneMedia
import kotlinx.coroutines.CoroutineScope
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
    suspend fun syncGallerySettings() = withContext(Native) { engine.syncGallerySettings() }
    suspend fun pollGallerySettings() = withContext(Native) { engine.pollGallerySettings() }
    suspend fun publicDriveLink(album: FolderAlbum) = withContext(Native) {
        engine.publicDriveLink(album.gallery, album.id)
    }
    suspend fun createDriveLink(album: FolderAlbum, password: String, expires: Long?) = withContext(Native) {
        engine.createPublicDriveLink(album.gallery, album.id, password, expires)
    }
    suspend fun revokeDriveLink(album: FolderAlbum) = withContext(Native) {
        engine.revokePublicDriveLink(album.gallery, album.id)
    }
    suspend fun galleryVisibilityReady() = withContext(Native) { engine.galleryVisibilityReady() }
    suspend fun driveProgress() = withContext(Native) { engine.driveProgress() }
    suspend fun syncProgress() = withContext(Native) { engine.syncProgress() }
    suspend fun thumbnail(item: GalleryItem): ByteArray? = withContext(Native) { item.remoteKey?.let(engine::photoThumbnail) }
    suspend fun preview(item: GalleryItem): ByteArray? = withContext(Native) { item.remoteKey?.let(engine::photoPreview) }
    suspend fun rememberShape(item: GalleryItem, width: Int, height: Int) = withContext(Native) {
        item.remoteKey?.let { engine.rememberPhotoShape(it, width.toUInt(), height.toUInt()) }
    }

    /** The whole of `view` for the grid; records load through it page by page. */
    suspend fun outline(view: LibraryView, scope: CoroutineScope, unlocked: Boolean): GalleryOutline = withContext(Native) {
        val native = if (unlocked) engine.unlockedAlbumOutline(view) else engine.outlineAt(view)
        GalleryOutline(native.ids(), native.times().toLongArray(), native.ratios().toFloatArray(), scope) { start, count ->
            withContext(Native) { engine.outlineItems(native, start.toUInt(), count.toUInt()) }
        }
    }

    suspend fun setFavorite(items: List<GalleryItem>, favorite: Boolean) = withContext(Native) {
        engine.setFavorite(items.map { it.assetKey }, favorite)
    }

    suspend fun phoneIndex(scan: Long, items: List<PhoneMedia>) = withContext(Native) { engine.phoneIndex(scan, items) }
    suspend fun phoneFinish(scan: Long) = withContext(Native) { engine.phoneFinish(scan) }
    suspend fun phoneReconcile(ids: List<String>) = withContext(Native) { engine.phoneReconcile(ids) }
    suspend fun phoneFolders() = withContext(Native) { engine.phoneFolders() }
    suspend fun setBackupFolder(folder: String, enabled: Boolean) = withContext(Native) { engine.setBackupFolder(folder, enabled) }
    suspend fun backupQueue(limit: Int) = withContext(Native) { engine.backupQueue(limit.toUInt()) }
    suspend fun checkBackup(skip: Collection<String> = emptyList()) = withContext(Native) { engine.checkBackup(skip.toList()) }
    suspend fun backupRemaining() = withContext(Native) { engine.backupRemaining().toLong() }
    suspend fun setPhoneHashes(hashes: List<PhoneHash>) = withContext(Native) { engine.setPhoneHashes(hashes) }
    suspend fun uploadPhone(id: String, sha1: String, reader: MediaReader, thumbnail: ByteArray?) =
        withContext(Native) { engine.uploadPhone(id, sha1, reader, thumbnail) }

    suspend fun pickerCollection() = withContext(Native) { engine.pickerCollection() }
    suspend fun pickerMedia(after: Long, limit: Int) = withContext(Native) { engine.pickerMedia(after.toULong(), limit.toUInt()) }
    suspend fun pickerItem(id: String) = withContext(Native) { engine.pickerItem(id) }
    suspend fun pickerDeleted(after: Long, limit: Int) = withContext(Native) { engine.pickerDeleted(after.toULong(), limit.toUInt()) }
    suspend fun trash(items: List<GalleryItem>) = withContext(Native) { engine.trashItems(items.map { it.assetKey }) }

    suspend fun freeUpSummary(keepDays: Int) = withContext(Native) { engine.freeUpSummary(keepDays.coerceAtLeast(0).toUInt()) }
    suspend fun freeUpVerified(keepDays: Int) = withContext(Native) { engine.freeUpVerified(keepDays.coerceAtLeast(0).toUInt()) }
    /** The decrypted original of a Proton item, in the app's cache. */
    suspend fun downloadOriginal(item: GalleryItem, directory: File): File = withContext(Native) {
        val key = item.remoteKey ?: throw GalleryException.Operation("This item isn’t in Proton.")
        File(engine.downloadOriginal(key, item.name, directory.path))
    }

    /** A Proton video, opened for playback while it downloads. */
    suspend fun openVideo(item: GalleryItem): VideoStream = withContext(Native) {
        val key = item.remoteKey ?: throw GalleryException.Operation("This item isn’t in Proton.")
        engine.openVideo(key)
    }

    suspend fun totals() = withContext(Native) { engine.totals() }
    suspend fun albums(location: GalleryLocation) = withContext(Native) {
        when {
            location.section == GallerySection.Albums && location.album == null -> engine.photoAlbums()
            location.section != GallerySection.Drive -> emptyList()
            else -> engine.albums(location.album?.gallery, location.album?.id)
        }
    }
    suspend fun photoAlbums() = withContext(Native) { engine.photoAlbums() }
    suspend fun setAlbumHidden(album: FolderAlbum, hidden: Boolean) = withContext(Native) { engine.setAlbumHidden(album.id, hidden) }
    suspend fun backupAlbums() = withContext(Native) { engine.backupAlbums() }
    suspend fun setBackupAlbum(folder: String?, album: FolderAlbum, add: Boolean, includeBackedUp: Boolean) =
        withContext(Native) { engine.setBackupAlbum(folder, album.id, add, includeBackedUp) }
    suspend fun queueAlbumAdds(album: FolderAlbum, phoneIds: List<String>) = withContext(Native) { engine.queueAlbumAdds(album.id, phoneIds) }
    suspend fun flushAlbumAdds() = withContext(Native) { engine.flushAlbumAdds() }
    suspend fun createAlbum(name: String) = withContext(Native) { engine.createAlbum(name) }
    suspend fun addToAlbum(album: FolderAlbum, items: List<GalleryItem>) = withContext(Native) { engine.addToAlbum(album.id, items.map { it.assetKey }) }
    suspend fun removeFromAlbum(album: FolderAlbum, items: List<GalleryItem>) = withContext(Native) { engine.removeFromAlbum(album.id, items.map { it.assetKey }) }
    suspend fun renameAlbum(album: FolderAlbum, name: String) = withContext(Native) { engine.renameAlbum(album.id, name) }
    suspend fun deleteAlbum(album: FolderAlbum) = withContext(Native) { engine.deleteAlbum(album.id) }
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

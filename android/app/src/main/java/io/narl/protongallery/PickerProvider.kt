package io.narl.protongallery

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Point
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.CloudMediaProvider
import android.provider.CloudMediaProviderContract.AlbumColumns
import android.provider.CloudMediaProviderContract.EXTRA_ALBUM_ID
import android.provider.CloudMediaProviderContract.EXTRA_MEDIA_COLLECTION_ID
import android.provider.CloudMediaProviderContract.EXTRA_PAGE_SIZE
import android.provider.CloudMediaProviderContract.EXTRA_PAGE_TOKEN
import android.provider.CloudMediaProviderContract.EXTRA_PREVIEW_THUMBNAIL
import android.provider.CloudMediaProviderContract.EXTRA_SYNC_GENERATION
import android.provider.CloudMediaProviderContract.MediaCollectionInfo
import android.provider.CloudMediaProviderContract.MediaColumns
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.RequiresApi
import io.narl.protongallery.bridge.PickerCollection
import io.narl.protongallery.bridge.PickerMedia
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.security.MessageDigest

/**
 * Proton Photos inside Android's own photo picker, beside the phone's media.
 *
 * The picker copies the library's metadata and asks only for changes after
 * the last generation it saw (the catalog counts them). Thumbnails come from
 * Proton and are kept in the cache; a picked file is the decrypted original.
 *
 * Android only lists cloud apps the phone maker allows. Others must be added
 * once with adb, see [PhotoPicker.command]; then the user chooses this app in
 * the picker's settings.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
class PickerProvider : CloudMediaProvider() {
    // Nothing here: the picker binds this provider from its own process, and
    // opening the catalog or the network must not happen on the main thread.
    override fun onCreate() = true

    private val app: Context get() = requireNotNull(context)

    private fun <T> work(signal: CancellationSignal? = null, block: suspend (GalleryRepository) -> T): T = runBlocking(Dispatchers.IO) {
        val repository = GalleryRepository.get(app)
        runCatching { repository.ensureSession() }.onFailure { Log.w(TAG, "Session restore failed", it) }
        val job = async { block(repository) }
        signal?.setOnCancelListener { job.cancel() }
        job.await()
    }

    override fun onGetMediaCollectionInfo(extras: Bundle): Bundle = work { repository ->
        val collection = repository.pickerCollection()
        Bundle().apply {
            putString(MediaCollectionInfo.MEDIA_COLLECTION_ID, collection.idOrEmpty())
            putLong(MediaCollectionInfo.LAST_MEDIA_SYNC_GENERATION, collection?.generation?.toLong() ?: 0L)
            collection?.accountName?.takeIf { it.isNotBlank() }?.let { putString(MediaCollectionInfo.ACCOUNT_NAME, it) }
            putParcelable(MediaCollectionInfo.ACCOUNT_CONFIGURATION_INTENT,
                Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    override fun onQueryMedia(extras: Bundle): Cursor = work { repository ->
        val collection = repository.pickerCollection()
        val honored = arrayListOf(EXTRA_SYNC_GENERATION, EXTRA_PAGE_TOKEN, EXTRA_PAGE_SIZE)
        val cursor = MatrixCursor(MEDIA_COLUMNS)
        val requested = extras.getString(EXTRA_MEDIA_COLLECTION_ID)
        // An album filter is honored by having no albums; a stale collection
        // gets nothing, and the picker reads everything again.
        if (extras.containsKey(EXTRA_ALBUM_ID)) honored += EXTRA_ALBUM_ID
        val stale = collection == null || (requested != null && requested != collection.id)
        var next: Long? = null
        if (!stale && !extras.containsKey(EXTRA_ALBUM_ID)) {
            val after = maxOf(extras.getLong(EXTRA_SYNC_GENERATION, 0L),
                extras.getString(EXTRA_PAGE_TOKEN)?.toLongOrNull() ?: 0L).coerceAtLeast(0L)
            val size = extras.getInt(EXTRA_PAGE_SIZE, PAGE).coerceIn(1, PAGE)
            val page = repository.pickerMedia(after, size)
            page.items.forEach { cursor.addRow(row(it)) }
            next = page.next?.toLong()
        }
        cursor.extras = Bundle().apply {
            putString(EXTRA_MEDIA_COLLECTION_ID, collection.idOrEmpty())
            putStringArrayList(ContentResolver.EXTRA_HONORED_ARGS, honored)
            next?.let { putString(EXTRA_PAGE_TOKEN, it.toString()) }
        }
        cursor
    }

    // Proton media is never removed from the catalog, so nothing is reported.
    override fun onQueryDeletedMedia(extras: Bundle): Cursor = work { repository ->
        MatrixCursor(arrayOf(MediaColumns.ID)).apply {
            this.extras = Bundle().apply {
                putString(EXTRA_MEDIA_COLLECTION_ID, repository.pickerCollection().idOrEmpty())
                putStringArrayList(ContentResolver.EXTRA_HONORED_ARGS, arrayListOf(EXTRA_SYNC_GENERATION, EXTRA_PAGE_TOKEN))
            }
        }
    }

    override fun onQueryAlbums(extras: Bundle): Cursor = work { repository ->
        MatrixCursor(arrayOf(AlbumColumns.ID, AlbumColumns.DISPLAY_NAME, AlbumColumns.DATE_TAKEN_MILLIS,
            AlbumColumns.MEDIA_COVER_ID, AlbumColumns.MEDIA_COUNT)).apply {
            this.extras = Bundle().apply { putString(EXTRA_MEDIA_COLLECTION_ID, repository.pickerCollection().idOrEmpty()) }
        }
    }

    override fun onOpenPreview(mediaId: String, size: Point, extras: Bundle?, signal: CancellationSignal?): AssetFileDescriptor {
        val large = extras?.getBoolean(EXTRA_PREVIEW_THUMBNAIL, false) == true || maxOf(size.x, size.y) > THUMBNAIL_EDGE
        val file = previewFile(app, mediaId, large)
        if (!file.isFile) {
            val bytes = work(signal) { repository ->
                val item = repository.pickerItem(mediaId) ?: throw FileNotFoundException("Not in Proton Photos")
                (if (large) runCatching { repository.preview(item) }.getOrNull() else null) ?: repository.thumbnail(item)
            } ?: throw FileNotFoundException("No preview")
            file.parentFile?.mkdirs()
            val temp = File.createTempFile("preview", ".tmp", file.parentFile)
            try {
                temp.writeBytes(bytes)
                if (!temp.renameTo(file)) throw FileNotFoundException("Preview not saved")
            } finally { temp.delete(); bytes.fill(0) }
            trimPreviews(app)
        }
        val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        return AssetFileDescriptor(descriptor, 0, AssetFileDescriptor.UNKNOWN_LENGTH)
    }

    override fun onOpenMedia(mediaId: String, extras: Bundle?, signal: CancellationSignal?): ParcelFileDescriptor {
        val file = try {
            work(signal) { repository ->
                val item = repository.pickerItem(mediaId) ?: throw FileNotFoundException("Not in Proton Photos")
                repository.downloadOriginal(item, Originals.directory(app))
            }
        } catch (missing: FileNotFoundException) { throw missing }
        catch (error: Exception) {
            Log.w(TAG, "Original download for the picker failed", error)
            throw FileNotFoundException(error.message ?: "Download failed")
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    private fun row(media: PickerMedia): Array<Any?> = arrayOf(
        media.id,
        media.takenMillis,
        media.generation.toLong(),
        media.mime,
        if (media.mime == "image/gif") MediaColumns.STANDARD_MIME_TYPE_EXTENSION_GIF else MediaColumns.STANDARD_MIME_TYPE_EXTENSION_NONE,
        media.sizeBytes.toLong(),
        media.phoneId?.let { PhoneLibrary.uri(it, media.isVideo).toString() },
        0L,
        0,
        media.width.toInt(),
        media.height.toInt(),
        0,
    )

    companion object {
        private const val TAG = "ProtonGallery"
        private const val PAGE = 1000
        private const val THUMBNAIL_EDGE = 512
        private const val PREVIEW_BYTES = 256L * 1024 * 1024

        private val MEDIA_COLUMNS = arrayOf(
            MediaColumns.ID, MediaColumns.DATE_TAKEN_MILLIS, MediaColumns.SYNC_GENERATION, MediaColumns.MIME_TYPE,
            MediaColumns.STANDARD_MIME_TYPE_EXTENSION, MediaColumns.SIZE_BYTES, MediaColumns.MEDIA_STORE_URI,
            MediaColumns.DURATION_MILLIS, MediaColumns.IS_FAVORITE, MediaColumns.WIDTH, MediaColumns.HEIGHT,
            MediaColumns.ORIENTATION,
        )

        private fun PickerCollection?.idOrEmpty() = this?.id ?: PhotoPicker.SIGNED_OUT

        private fun previewFile(context: Context, id: String, large: Boolean): File {
            val digest = MessageDigest.getInstance("SHA-256").digest(id.toByteArray())
            val name = digest.take(12).joinToString("") { "%02x".format(it) } + if (large) "-large" else "-small"
            return File(File(context.cacheDir, "picker"), name)
        }

        /** Oldest previews go first once they take more than [PREVIEW_BYTES]. */
        @Synchronized private fun trimPreviews(context: Context) {
            val files = File(context.cacheDir, "picker").listFiles()?.filter { it.isFile }?.sortedBy { it.lastModified() } ?: return
            var bytes = files.sumOf { it.length() }
            for (file in files) {
                if (bytes <= PREVIEW_BYTES) break
                val length = file.length()
                if (file.delete()) bytes -= length
            }
        }
    }
}

/** Whether Proton Photos shows in Android's photo picker, and how to get there. */
object PhotoPicker {
    const val SIGNED_OUT = "signed-out"
    private const val TAG = "ProtonGallery"
    enum class Status { Unavailable, NotAllowed, Allowed, Active }

    fun authority(context: Context) = context.packageName + ".picker"

    fun status(context: Context): Status {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return Status.Unavailable
        val authority = authority(context)
        return runCatching {
            when {
                MediaStore.isCurrentCloudMediaProviderAuthority(context.contentResolver, authority) -> Status.Active
                MediaStore.isSupportedCloudMediaProviderAuthority(context.contentResolver, authority) -> Status.Allowed
                else -> Status.NotAllowed
            }
        }.getOrDefault(Status.NotAllowed)
    }

    /** Tell the picker the library changed, when this app is its cloud source. */
    suspend fun changed(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) withContext(Dispatchers.IO) {
            runCatching { notify(context) }
                .onFailure { Log.i(TAG, "Photo picker not told about changes: ${it.javaClass.simpleName}") }
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private suspend fun notify(context: Context) {
        val authority = authority(context)
        if (!MediaStore.isCurrentCloudMediaProviderAuthority(context.contentResolver, authority)) return
        val collection = GalleryRepository.get(context).pickerCollection()
        MediaStore.notifyCloudMediaChangedEvent(context.contentResolver, authority, collection?.id ?: SIGNED_OUT)
    }

    /** Opens the picker's settings, where the cloud media app is chosen. */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    fun settings(): Intent = Intent(MediaStore.ACTION_PICK_IMAGES_SETTINGS)

    /**
     * The one-time adb command that lets the picker list this app. It sets the
     * phone's whole list: another cloud app already on it (such as Google
     * Photos) has to be named too, comma-separated.
     */
    fun command(packageName: String, sdk: Int = Build.VERSION.SDK_INT): String = when {
        sdk >= Build.VERSION_CODES.VANILLA_ICE_CREAM ->
            "adb shell device_config override mediaprovider allowed_cloud_providers $packageName"
        sdk >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ->
            "adb shell device_config put mediaprovider allowed_cloud_providers $packageName"
        // Android 13 as shipped reads the authority, and only at boot.
        else -> "adb shell device_config put storage_native_boot allowed_cloud_providers $packageName.picker\n" +
            "adb shell device_config put mediaprovider allowed_cloud_providers $packageName"
    }
}

package io.narl.protongallery

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Size
import io.narl.protongallery.bridge.GalleryItem
import io.narl.protongallery.bridge.PhoneMedia
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

/**
 * The phone's own photo library, read through the system media index. Files
 * stay where they are; the catalog keeps metadata, and reads go through
 * content URIs, so this works with full and with partial ("selected photos")
 * access alike.
 */
object PhoneLibrary {
    /** Permissions to request. Android 14 adds the "selected photos" choice. */
    val permissions: Array<String> = when {
        Build.VERSION.SDK_INT >= 34 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    fun hasAccess(context: Context): Boolean = permissions.any {
        context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
    }

    /** Only some photos were shared with the app (Android 14 and later). */
    fun partialAccess(context: Context): Boolean = Build.VERSION.SDK_INT >= 34 &&
        context.checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED &&
        context.checkSelfPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED

    private val images = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
    private val videos = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
    private val files = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)

    /** Change notifications for both photos and videos arrive here. */
    val observed: Uri = files

    fun uri(id: String, video: Boolean): Uri = ContentUris.withAppendedId(if (video) videos else images, id.toLong())
    fun uri(item: GalleryItem): Uri? = item.phoneId?.let { uri(it, item.isVideo) }

    /**
     * Index everything visible to the app, in batches. Media the scan no longer
     * sees is forgotten at the end, so an interrupted scan removes nothing.
     */
    suspend fun scan(context: Context, repository: GalleryRepository): Int {
        if (!hasAccess(context)) return 0
        val scan = System.currentTimeMillis()
        val columns = arrayOf(
            MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.BUCKET_ID, MediaStore.MediaColumns.BUCKET_DISPLAY_NAME,
            MediaStore.MediaColumns.DATE_TAKEN, MediaStore.MediaColumns.DATE_MODIFIED, MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.WIDTH, MediaStore.MediaColumns.HEIGHT,
            MediaStore.MediaColumns.ORIENTATION, MediaStore.MediaColumns.IS_FAVORITE,
        )
        val query = Bundle().apply {
            putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (?,?)")
            putStringArray(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf(
                MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(), MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString()))
        }
        var count = 0
        val batch = ArrayList<PhoneMedia>(500)
        context.contentResolver.query(files, columns, query, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val name = cursor.getString(1) ?: continue
                val mime = cursor.getString(2) ?: continue
                val modified = cursor.getLong(6)
                val taken = cursor.getLong(5).takeIf { it > 0 }?.div(1000)
                    ?: cursor.getLong(7).takeIf { it > 0 } ?: modified
                val turned = cursor.getInt(11) % 180 != 0
                val width = cursor.getInt(9).coerceAtLeast(0)
                val height = cursor.getInt(10).coerceAtLeast(0)
                batch += PhoneMedia(
                    id = cursor.getLong(0).toString(), name = name, mime = mime,
                    folder = cursor.getString(3) ?: "", folderName = folderLabel(cursor.getString(4)),
                    taken = taken, modified = modified, sizeBytes = cursor.getLong(8).coerceAtLeast(0).toULong(),
                    width = (if (turned) height else width).toUInt(), height = (if (turned) width else height).toUInt(),
                    favorite = cursor.getInt(12) == 1,
                )
                if (batch.size == 500) { repository.phoneIndex(scan, batch.toList()); count += batch.size; batch.clear() }
                currentCoroutineContext().ensureActive()
            }
        } ?: return 0
        if (batch.isNotEmpty()) { repository.phoneIndex(scan, batch.toList()); count += batch.size }
        repository.phoneFinish(scan)
        return count
    }

    private fun folderLabel(name: String?) = name?.takeIf { it.isNotBlank() } ?: "Phone"

    suspend fun sha1(context: Context, uri: Uri): String {
        val digest = MessageDigest.getInstance("SHA-1")
        context.contentResolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        } ?: error("The file couldn’t be opened.")
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * The small preview Proton's apps show in their grids: at most 512 px and
     * 60 KB of JPEG, like the Proton Drive Linux app makes.
     */
    fun uploadThumbnail(context: Context, uri: Uri): ByteArray? = runCatching {
        var bitmap = context.contentResolver.loadThumbnail(uri, Size(512, 512), null)
        try {
            repeat(3) {
                for (quality in intArrayOf(82, 65, 50, 35, 20)) {
                    val out = ByteArrayOutputStream()
                    bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
                    if (out.size() <= 60 * 1024) return@runCatching out.toByteArray()
                }
                val smaller = Bitmap.createScaledBitmap(bitmap, (bitmap.width / 2).coerceAtLeast(1), (bitmap.height / 2).coerceAtLeast(1), true)
                bitmap.recycle()
                bitmap = smaller
            }
            null
        } finally { bitmap.recycle() }
    }.getOrNull()
}

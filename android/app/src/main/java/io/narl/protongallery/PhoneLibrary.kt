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
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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

    private val scanning = Mutex()
    private var lastScan = 0L

    /**
     * Bring the catalog in line with the system media index. One scan at a
     * time; nothing is read when the index is unchanged since the last scan,
     * unless `force`.
     *
     * After the first complete scan, later ones are quick: only identifiers
     * are listed (to notice removed and newly visible items), and full
     * metadata is read only for media the system changed since then. A full
     * scan runs again when the index was rebuilt. Returns the number of items
     * read, or -1 when nothing changed.
     */
    suspend fun scan(context: Context, repository: GalleryRepository, force: Boolean = false): Int = scanning.withLock {
        if (!hasAccess(context)) return@withLock 0
        val started = android.os.SystemClock.elapsedRealtime()
        val (marker, visible) = indexMarker(context) ?: (null to -1L)
        val preferences = context.getSharedPreferences("phone-library", Context.MODE_PRIVATE)
        // The catalog may have been reset since, so it must hold as many items too.
        if (!force && marker != null && preferences.getString("indexed", null) == marker &&
            repository.phoneFolders().sumOf { it.count.toLong() } == visible) {
            android.util.Log.println(android.util.Log.INFO, "ProtonGallery", "Phone library unchanged: visible=$visible, metadata=0, elapsedMs=${android.os.SystemClock.elapsedRealtime() - started}")
            return@withLock -1
        }
        PhoneBackup.scanning(true)
        try {
            // Taken before reading, so changes made during the scan are read next time.
            val version = runCatching { MediaStore.getVersion(context) }.getOrNull()
            val generation = runCatching { generations(context) }.getOrNull()
            val since = preferences.getLong("generation", -1L)
                .takeIf { !force && version != null && it >= 0 && preferences.getString("version", null) == version }
            val count = withContext(Dispatchers.IO) {
                if (since != null) update(context, repository, since) else full(context, repository)
            } ?: return@withLock 0
            if (marker != null) preferences.edit {
                putString("indexed", marker)
                if (version != null && generation != null) { putString("version", version); putLong("generation", generation) }
                else { remove("version"); remove("generation") }
            }
            android.util.Log.println(android.util.Log.INFO, "ProtonGallery", "Phone library scan: visible=$visible, metadata=$count, full=${since == null}, elapsedMs=${android.os.SystemClock.elapsedRealtime() - started}")
            count
        } finally { PhoneBackup.scanning(false) }
    }

    /** Read every item, then forget what the scan did not see. */
    private suspend fun full(context: Context, repository: GalleryRepository): Int? {
        val scan = maxOf(System.currentTimeMillis(), lastScan + 1).also { lastScan = it }
        val count = read(context, repository, scan, mediaQuery()) ?: return null
        // A removed phone file can bring its Proton copy back into the picker.
        if (repository.phoneFinish(scan) > 0uL) PhotoPicker.changed(context)
        return count
    }

    /**
     * List identifiers only, forget what is gone, then read what is new to the
     * catalog or changed after generation `since`.
     */
    private suspend fun update(context: Context, repository: GalleryRepository, since: Long): Int? {
        val ids = ArrayList<String>()
        context.contentResolver.query(files, arrayOf(MediaStore.MediaColumns._ID), mediaQuery(), null)?.use { cursor ->
            while (cursor.moveToNext()) ids += cursor.getLong(0).toString()
        } ?: return null
        val reconciled = repository.phoneReconcile(ids)
        if (reconciled.removed > 0uL) PhotoPicker.changed(context)
        val scan = maxOf(System.currentTimeMillis(), lastScan + 1).also { lastScan = it }
        var count = read(context, repository, scan, mediaQuery("${MediaStore.MediaColumns.GENERATION_MODIFIED} > ?", since.toString())) ?: return null
        // Newly visible items (shared later with partial access) keep their old generation.
        for (part in reconciled.unknown.chunked(500)) {
            count += read(context, repository, scan, mediaQuery("${MediaStore.MediaColumns._ID} IN (${part.joinToString(",") { "?" }})",
                *part.toTypedArray())) ?: 0
        }
        return count
    }

    /** The highest generation of any external volume: every change raises it. */
    private fun generations(context: Context): Long =
        MediaStore.getExternalVolumeNames(context).maxOf { MediaStore.getGeneration(context, it) }

    /**
     * Changes whenever media is added, changed or removed: the index version
     * (new after a reset), its generation (raised by every change), the
     * number of items, and what the app may see.
     */
    private fun indexMarker(context: Context): Pair<String, Long>? = runCatching {
        val count = context.contentResolver.query(files, arrayOf(MediaStore.MediaColumns._ID), mediaQuery(), null)?.use { it.count.toLong() }
            ?: return null
        val version = MediaStore.getVersion(context)
        val generation = MediaStore.getExternalVolumeNames(context).sorted()
            .joinToString(",") { "$it=${MediaStore.getGeneration(context, it)}" }
        "$version:$generation:$count:${partialAccess(context)}" to count
    }.getOrNull()

    /** Photos and videos, optionally narrowed by `extra` with its arguments. */
    private fun mediaQuery(extra: String? = null, vararg arguments: String) = Bundle().apply {
        val types = "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (?,?)"
        putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION, if (extra == null) types else "$types AND ($extra)")
        putStringArray(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf(
            MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(), MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString(), *arguments))
    }

    private suspend fun read(context: Context, repository: GalleryRepository, scan: Long, query: Bundle): Int? {
        val columns = arrayOf(
            MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.BUCKET_ID, MediaStore.MediaColumns.BUCKET_DISPLAY_NAME,
            MediaStore.MediaColumns.DATE_TAKEN, MediaStore.MediaColumns.DATE_MODIFIED, MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.WIDTH, MediaStore.MediaColumns.HEIGHT,
            MediaStore.MediaColumns.ORIENTATION, MediaStore.MediaColumns.IS_FAVORITE,
        )
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
        } ?: return null
        if (batch.isNotEmpty()) { repository.phoneIndex(scan, batch.toList()); count += batch.size }
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

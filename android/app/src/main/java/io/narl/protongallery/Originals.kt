package io.narl.protongallery

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import io.narl.protongallery.bridge.GalleryItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Full-size files for viewing, sharing and handing to other apps. Phone media
 * is used where it is; Proton media is decrypted into the cache, which is
 * cleared of anything older than a day.
 */
object Originals {
    private const val AUTHORITY_SUFFIX = ".originals"
    private const val KEEP_MILLIS = 24L * 60 * 60 * 1000

    fun directory(context: Context) = File(context.cacheDir, "originals")

    /** A file on this device holding the item: the phone's own, or a decrypted copy. */
    suspend fun file(context: Context, repository: GalleryRepository, item: GalleryItem): File? = when {
        item.phoneId != null -> null
        item.localPath.isNotEmpty() -> File(item.localPath)
        item.remoteKey != null -> repository.downloadOriginal(item, directory(context))
        else -> null
    }

    /** Something another app may read once the caller grants it: a media or a cache URI. */
    suspend fun uri(context: Context, repository: GalleryRepository, item: GalleryItem, made: (File) -> Unit = {}): Uri {
        PhoneLibrary.uri(item)?.let { return it }
        val file = when {
            item.remoteKey != null -> repository.downloadOriginal(item, directory(context))
            // The device library is private; hand out a copy.
            item.localPath.isNotEmpty() -> withContext(Dispatchers.IO) {
                File(directory(context), "device/${item.id.hashCode().toUInt()}/${item.name.replace('/', '_')}").also { copy ->
                    if (!copy.exists()) { copy.parentFile?.mkdirs(); File(item.localPath).copyTo(copy, overwrite = true) }
                }
            }
            else -> throw IllegalStateException("This item has no file")
        }
        made(file)
        return FileProvider.getUriForFile(context, context.packageName + AUTHORITY_SUFFIX, file, item.name)
    }

    fun mime(item: GalleryItem): String = item.mime.ifEmpty { if (item.isVideo) "video/*" else "image/*" }

    /** Forget copies older than a day. */
    suspend fun trim(context: Context) = withContext(Dispatchers.IO) {
        val limit = System.currentTimeMillis() - KEEP_MILLIS
        directory(context).walkBottomUp().forEach { file ->
            if (file == directory(context)) return@forEach
            if (file.isFile && file.lastModified() < limit) file.delete()
            else if (file.isDirectory) file.delete() // Only succeeds when empty.
        }
    }
}

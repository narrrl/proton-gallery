package io.narl.protongallery

import android.content.Context
import io.narl.protongallery.bridge.PlacePhoto
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException

/**
 * What the last Places scan found, kept across launches so Places opens on the
 * map it showed last time instead of checking every photo again.
 *
 * [syncRevision] is the Proton Photos sync revision the scan started from:
 * while it is still the current one the photos are known to be current, and
 * otherwise Places shows them at once and checks again out of sight.
 *
 * The file lives in the cache directory and is deleted on sign-out and when
 * hidden albums change, so it never shows one account's places to another or
 * a hidden photo's place after it was hidden.
 */
class PlacesSnapshot(val syncRevision: ULong?, val photos: List<PlacePhoto>)

object PlacesStore {
    private const val MAGIC = 0x50475043 // "PGPC"
    private const val VERSION = 1

    fun file(context: Context) = File(context.cacheDir, "places.bin")

    /** The stored snapshot, or null when there is none or it can't be read. Call off the main thread. */
    fun read(context: Context): PlacesSnapshot? {
        val file = file(context)
        if (!file.isFile) return null
        return try {
            DataInputStream(file.inputStream().buffered()).use { input ->
                if (input.readInt() != MAGIC || input.readInt() != VERSION) return null
                val revision = if (input.readBoolean()) input.readLong().toULong() else null
                val count = input.readInt()
                if (count < 0) return null
                val photos = ArrayList<PlacePhoto>(count)
                repeat(count) {
                    val id = input.readUTF()
                    val name = input.readUTF()
                    val x = input.readDouble()
                    val y = input.readDouble()
                    val place = if (input.readBoolean()) input.readUTF() else null
                    photos += PlacePhoto(id, name, x, y, place, input.readInt().toUInt())
                }
                PlacesSnapshot(revision, photos)
            }
        } catch (_: IOException) {
            file.delete()
            null
        }
    }

    /** Replace the snapshot. Call off the main thread. */
    fun write(context: Context, snapshot: PlacesSnapshot) {
        val file = file(context)
        val partial = File(file.parentFile, file.name + ".partial")
        try {
            DataOutputStream(partial.outputStream().buffered()).use { out ->
                out.writeInt(MAGIC)
                out.writeInt(VERSION)
                out.writeBoolean(snapshot.syncRevision != null)
                snapshot.syncRevision?.let { out.writeLong(it.toLong()) }
                out.writeInt(snapshot.photos.size)
                for (photo in snapshot.photos) {
                    out.writeUTF(photo.id)
                    out.writeUTF(photo.name)
                    out.writeDouble(photo.x)
                    out.writeDouble(photo.y)
                    out.writeBoolean(photo.place != null)
                    photo.place?.let(out::writeUTF)
                    out.writeInt(photo.index.toInt())
                }
            }
            if (!partial.renameTo(file)) partial.delete()
        } catch (_: IOException) {
            partial.delete()
        }
    }

    fun clear(context: Context) {
        file(context).delete()
    }
}

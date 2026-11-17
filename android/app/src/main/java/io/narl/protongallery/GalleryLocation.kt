package io.narl.protongallery

import io.narl.protongallery.bridge.FolderAlbum
import io.narl.protongallery.bridge.LibraryView

enum class GallerySection { Photos, Drive, Device }

data class GalleryLocation(
    val section: GallerySection = GallerySection.Photos,
    val trail: List<FolderAlbum> = emptyList(),
) {
    val album: FolderAlbum? get() = trail.lastOrNull()
    val title: String get() = album?.name ?: when (section) {
        GallerySection.Photos -> "Photos"
        GallerySection.Drive -> "Drive"
        GallerySection.Device -> "On this device"
    }
    fun nativeView(): LibraryView? = when (section) {
        // A Proton album pages like a Drive album: it is a one-level gallery of itself.
        GallerySection.Photos -> album?.let { LibraryView.DriveAlbum(it.gallery, it.id) } ?: LibraryView.Photos
        GallerySection.Device -> LibraryView.Device
        GallerySection.Drive -> album?.let { LibraryView.DriveAlbum(it.gallery, it.id) }
    }
    fun child(album: FolderAlbum) = copy(trail = trail + album)

    /** Inside one of the account's Proton Photos albums. */
    val protonAlbum: Boolean get() = section == GallerySection.Photos && album != null
    fun back() = if (trail.isNotEmpty()) copy(trail = trail.dropLast(1)) else GalleryLocation()
    /// Same place with fresh album records (e.g. after a rename).
    fun renamed(album: FolderAlbum) = copy(trail = trail.map { if (it.gallery == album.gallery && it.id == album.id) album else it })
}

package io.narl.protongallery

import android.content.Context
import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import io.narl.protongallery.bridge.MapCamera
import io.narl.protongallery.bridge.PlacesMap
import io.narl.protongallery.bridge.mapSea
import java.nio.ByteBuffer
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.log2
import kotlin.math.min
import kotlin.math.roundToInt

/** The basemap bundled in the APK, or null when the build has none. Call off
 * the main thread; close the result when Places closes. */
fun openBundledBasemap(context: Context): PlacesMap? = try {
    context.assets.openFd(BASEMAP_ASSET).use { asset ->
        PlacesMap.openFd(asset.parcelFileDescriptor.fd, asset.startOffset.toULong(), asset.length.toULong())
    }
} catch (_: java.io.IOException) {
    null
} catch (_: io.narl.protongallery.bridge.GalleryException) {
    null
}

const val BASEMAP_ASSET = "basemap-v1.pmtiles"

/** Logical px of a basemap tile drawn at its own level. */
private const val TILE_DP = 256.0
/** Deepest tile level asked for; deeper zooms stretch these tiles. */
private const val MAX_LEVEL = 14

/** A basemap tile on screen, in px of the view. */
data class TileView(val level: Int, val x: Int, val y: Int, val left: Float, val top: Float, val size: Float, val density: Int) {
    fun key(dark: Boolean) = tileKey(level, x, y, density, dark)
}

/** Level, place, render density and theme of a tile in one number. */
fun tileKey(level: Int, x: Int, y: Int, density: Int, dark: Boolean): Long =
    (level.toLong() shl 50) or (x.toLong() shl 30) or (y.toLong() shl 10) or (density.toLong() shl 1) or (if (dark) 1L else 0L)

/**
 * The tiles covering a view of `size` px, nearest the centre first, then the
 * whole world under anything not drawn yet. Tile levels are chosen in
 * logical px, as on desktop, so lines and names keep their size; `pixelRatio`
 * renders them sharp on this screen.
 */
fun mapTiles(camera: MapCamera, size: IntSize, pixelRatio: Float): List<TileView> {
    if (size.width <= 0 || size.height <= 0) return emptyList()
    val width = size.width / pixelRatio.toDouble()
    val height = size.height / pixelRatio.toDouble()
    val scale = min(width, height) * camera.zoom
    val level = log2(scale / TILE_DP).roundToInt().coerceIn(0, MAX_LEVEL)
    val count = 1 shl level
    val tile = scale / count
    val left = width / 2 - camera.x * scale
    val top = height / 2 - camera.y * scale
    fun range(origin: Double, extent: Double): IntRange {
        val first = floor(-origin / tile).coerceAtLeast(0.0).toInt()
        val last = ceil((extent - origin) / tile).coerceAtMost(count.toDouble()).toInt()
        return first until maxOf(first, last)
    }
    val density = { dp: Double -> (dp / TILE_DP * pixelRatio * 4).roundToInt().coerceIn(2, 24) }
    val tiles = ArrayList<TileView>()
    for (y in range(top, height)) for (x in range(left, width)) {
        tiles += TileView(level, x, y, ((left + x * tile) * pixelRatio).toFloat(), ((top + y * tile) * pixelRatio).toFloat(),
            (tile * pixelRatio).toFloat(), density(tile))
    }
    val middleX = size.width / 2f
    val middleY = size.height / 2f
    tiles.sortBy { hypot(it.left + it.size / 2 - middleX, it.top + it.size / 2 - middleY) }
    if (tiles.none { it.level == 0 }) tiles += TileView(0, 0, 0, 0f, 0f, 0f, density(TILE_DP))
    return tiles
}

/**
 * Rendered basemap tiles, up to a byte budget. Rendering happens off the
 * main thread through [render]; drawing only uses what is already here.
 */
class MapTiles(private val map: PlacesMap, budget: Int = 64 shl 20) {
    private val cache = object : LruCache<Long, ImageBitmap>(budget) {
        override fun sizeOf(key: Long, value: ImageBitmap) = value.width * value.height * 4
    }

    fun has(key: Long) = cache.get(key) != null

    /** Render one tile. Call off the main thread. */
    fun render(tile: TileView, dark: Boolean) {
        val key = tile.key(dark)
        if (cache.get(key) != null) return
        val rendered = map.renderTile(tile.level.toUByte(), tile.x.toUInt(), tile.y.toUInt(), tile.density.toUByte(), dark)
        val bitmap = Bitmap.createBitmap(rendered.width.toInt(), rendered.height.toInt(), Bitmap.Config.ARGB_8888)
        // Premultiplied RGBA rows: the memory layout of ARGB_8888.
        bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(rendered.rgba))
        bitmap.prepareToDraw()
        cache.put(key, bitmap.asImageBitmap())
    }

    /** A rendered tile for `tile`: its own, the same place at another
     * density, or the nearest ancestor, with how many levels up it is. */
    private fun find(tile: TileView, dark: Boolean): Triple<TileView, ImageBitmap, Int>? {
        var at = tile
        var depth = 0
        while (true) {
            cache.get(at.key(dark))?.let { return Triple(at, it, depth) }
            for (density in 2..24) cache.get(tileKey(at.level, at.x, at.y, density, dark))?.let { return Triple(at, it, depth) }
            if (at.level == 0) return null
            at = at.copy(level = at.level - 1, x = at.x / 2, y = at.y / 2)
            depth++
        }
    }

    /** Draw `tiles` with the sea under them; false when nothing is ready. */
    fun draw(scope: DrawScope, tiles: List<TileView>, dark: Boolean) = with(scope) {
        drawRect(Color(mapSea(dark).toInt()))
        for (tile in tiles) {
            if (tile.size == 0f) continue
            // Whole px edges, so neighbours meet without a seam.
            val left = tile.left.roundToInt()
            val top = tile.top.roundToInt()
            val right = (tile.left + tile.size).roundToInt()
            val bottom = (tile.top + tile.size).roundToInt()
            val (found, image, depth) = find(tile, dark) ?: continue
            if (depth == 0) {
                drawImage(image, dstOffset = IntOffset(left, top), dstSize = IntSize(right - left, bottom - top))
                continue
            }
            // Until this tile is rendered, the matching part of an ancestor, magnified.
            val span = 1 shl depth
            val part = image.width / span
            val src = IntOffset((tile.x - (found.x shl depth)) * part, (tile.y - (found.y shl depth)) * part)
            clipRect(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat()) {
                drawImage(image, srcOffset = src, srcSize = IntSize(part, part),
                    dstOffset = IntOffset(left, top), dstSize = IntSize(right - left, bottom - top))
            }
        }
    }

    fun clear() = cache.evictAll()
}

/** Where world point `x`, `y` is on a view of `size` px. */
fun MapCamera.toScreen(x: Double, y: Double, size: IntSize): Offset {
    val scale = min(size.width, size.height) * zoom
    return Offset((size.width / 2.0 + (x - this.x) * scale).toFloat(), (size.height / 2.0 + (y - this.y) * scale).toFloat())
}

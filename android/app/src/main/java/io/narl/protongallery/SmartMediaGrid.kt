package io.narl.protongallery

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.narl.protongallery.bridge.AlbumGrouping
import io.narl.protongallery.bridge.AlbumTag
import io.narl.protongallery.bridge.FolderAlbum
import io.narl.protongallery.bridge.GalleryItem
import io.narl.protongallery.bridge.planSmartGrid
import io.narl.protongallery.icons.FolderOpen
import io.narl.protongallery.icons.PhotoLibrary
import io.narl.protongallery.icons.VisibilityOff
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Target row height in dp: the pinch range and its default. Rows are
 * justified to the width, so tiles land near this height, not on it. */
const val ZOOM_MIN = 48f
const val ZOOM_MAX = 240f
const val ZOOM_DEFAULT = 120f

/** Small tiles get coarser headings: a day rarely fills a row of small tiles. */
fun groupingFor(zoom: Float): AlbumGrouping = when {
    zoom < 64f -> AlbumGrouping.YEAR
    zoom < 104f -> AlbumGrouping.MONTH
    else -> AlbumGrouping.DAY
}

private const val GAP_DP = 2

private sealed interface GalleryRow {
    val key: String
    data class Heading(override val key: String, val label: String) : GalleryRow
    /** Items `start` until `end` of the outline. */
    data class Media(override val key: String, val start: Int, val widths: IntArray, val height: Int) : GalleryRow {
        val end get() = start + widths.size
    }
}

/** Rows planned for one outline at one size. */
private class GridLayout(val outline: GalleryOutline, val rows: List<GalleryRow>) {
    // Row starts in order, for finding an item's row.
    private val media = rows.withIndex().filter { it.value is GalleryRow.Media }
    fun rowOf(item: Int): Int {
        var low = 0; var high = media.size - 1
        while (low <= high) {
            val mid = (low + high) / 2
            val row = media[mid].value as GalleryRow.Media
            when {
                item < row.start -> high = mid - 1
                item >= row.end -> low = mid + 1
                else -> return media[mid].index
            }
        }
        return -1
    }
}

/** Section heading for a capture time, or `null` when the grid is ungrouped. */
fun groupLabel(time: Long, grouping: AlbumGrouping): String? {
    if (grouping == AlbumGrouping.NONE) return null
    if (grouping == AlbumGrouping.DAY) return galleryDate(time)
    val date = Instant.ofEpochSecond(time).atZone(ZoneId.systemDefault())
    return if (grouping == AlbumGrouping.YEAR) date.year.toString()
    else date.format(DateTimeFormatter.ofPattern("LLLL yyyy", Locale.getDefault()))
}

/** The group of a capture time; items of one group share a heading. */
private fun groupOf(time: Long, grouping: AlbumGrouping, zone: ZoneId): Long = if (grouping == AlbumGrouping.NONE) 0L else {
    val date = Instant.ofEpochSecond(time).atZone(zone).toLocalDate()
    when (grouping) {
        AlbumGrouping.YEAR -> date.year.toLong()
        AlbumGrouping.MONTH -> date.year * 12L + date.monthValue
        else -> date.toEpochDay()
    }
}

/** Plan the rows of items `from` until `until` of `outline`: a heading where
 * the group changes, then its photos justified to the width. Unknown shapes
 * are square, so the rows only change with the outline. */
private fun planRows(
    outline: GalleryOutline, grouping: AlbumGrouping, width: Int, target: Int, gap: Int,
    from: Int = 0, until: Int = outline.count,
): List<GalleryRow> {
    val zone = ZoneId.systemDefault()
    fun group(time: Long) = groupOf(time, grouping, zone)
    val rows = ArrayList<GalleryRow>()
    var start = from
    while (start < until) {
        val section = group(outline.times[start])
        var end = start + 1
        while (end < until && group(outline.times[end]) == section) end++
        groupLabel(outline.times[start], grouping)?.let { rows += GalleryRow.Heading("group-${outline.ids[start]}", it) }
        // The planner takes a bounded number of items. Its rows only look
        // ahead, so planning in pieces and keeping all but each piece's last
        // row gives the same rows as planning the whole section at once.
        var piece = start
        while (piece < end) {
            val pieceEnd = minOf(end, piece + 1024)
            val planned = planSmartGrid((piece until pieceEnd).map(outline::ratio), width.toUInt(), target.toUInt(), gap.toUInt())
            val keep = if (pieceEnd == end) planned else planned.dropLast(1).ifEmpty { planned }
            for (row in keep) {
                val first = piece + row.start.toInt()
                rows += GalleryRow.Media("row-${outline.ids[first]}", first, IntArray(row.tiles.size) { row.tiles[it].width.toInt() }, row.tiles.first().height.toInt())
            }
            piece = keep.last().let { piece + it.start.toInt() + it.tiles.size }
        }
        start = end
    }
    return rows
}

/** While pinching, only the rows around `anchor` are planned, on the spot, so
 * every finger movement resizes the tiles in the next frame however large the
 * view. They start at the anchor's group when it is near, so most rows already
 * match the full plan that replaces them once the fingers lift. */
private fun planAround(outline: GalleryOutline, anchor: Int, grouping: AlbumGrouping, width: Int, target: Int, gap: Int): List<GalleryRow> {
    val zone = ZoneId.systemDefault()
    val section = groupOf(outline.times[anchor], grouping, zone)
    var from = anchor
    while (from > 0 && anchor - from < PINCH_BEHIND && groupOf(outline.times[from - 1], grouping, zone) == section) from--
    return planRows(outline, grouping, width, target, gap, from, minOf(outline.count, anchor + PINCH_AHEAD))
}

// Items planned around the anchor while pinching: before it within its group,
// and after it, several screens of the smallest tiles.
private const val PINCH_BEHIND = 1500
private const val PINCH_AHEAD = 600

/** Every row is planned from the outline at once, so the grid only moves when
 * media is added or removed. Records and thumbnails load as rows come into
 * view, and the next screens in the scroll direction are read ahead.
 * Two fingers zoom: tiles grow or shrink and headings follow the size.
 */
@Composable
fun SmartMediaGrid(
    outline: GalleryOutline?, thumbnails: ThumbnailLoader,
    zoom: Float, onZoom: (Float) -> Unit, scroll: LazyListState, onOpen: (Int, GalleryItem) -> Unit,
    albums: List<FolderAlbum> = emptyList(), onAlbum: (FolderAlbum) -> Unit = {},
    tags: List<AlbumTag> = emptyList(), selectedTag: String? = null, onTag: (String?) -> Unit = {},
    albumsTitle: String = "Albums",
    onNewAlbum: (() -> Unit)? = null,
    // Hidden albums show only while unlocked with the phone's lock.
    hiddenUnlocked: Boolean = false,
    onToggleHidden: () -> Unit = {},
    header: (@Composable () -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(),
    // Long-press selects; while anything is selected, a tap adds or removes.
    selection: Set<String> = emptySet(),
    onLongPress: ((GalleryItem) -> Unit)? = null,
    // Inside a hidden album: thumbnails stay out of the disk cache.
    private: Boolean = false,
) {
    val density = LocalDensity.current
    val hiddenCount = albums.count { it.hidden }
    val shownAlbums = if (hiddenUnlocked) albums else albums.filterNot { it.hidden }
    val grouping = groupingFor(zoom)
    // The photo kept in view while pinching.
    var anchor by remember { mutableStateOf<String?>(null) }
    var pinching by remember { mutableStateOf(false) }
    val currentZoom by rememberUpdatedState(zoom)
    val currentOnZoom by rememberUpdatedState(onZoom)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val gapPx = with(density) { GAP_DP.dp.roundToPx() }.coerceAtLeast(1)
        val widthPx = constraints.maxWidth.coerceIn(1, 32768)
        val targetPx = with(density) { zoom.dp.roundToPx() }.coerceIn(1, 8192)
        val albumColumns = (maxWidth / 172.dp).toInt().coerceIn(2, 6)
        // The new-album card leads the album cards.
        val cards: List<FolderAlbum?> = (if (onNewAlbum != null) listOf(null) else emptyList<FolderAlbum?>()) + shownAlbums
        val albumRows = cards.chunked(albumColumns)
        // Items listed before the photo rows; keep in step with the LazyColumn below.
        val leading = (if (tags.isNotEmpty()) 1 else 0) + (if (header != null) 1 else 0) +
            (if (shownAlbums.isNotEmpty()) 1 else 0) + albumRows.size + (if (hiddenCount > 0) 1 else 0)
        val currentLeading by rememberUpdatedState(leading)
        var layout by remember { mutableStateOf<GridLayout?>(null) }
        var failed by remember { mutableStateOf(false) }
        var retry by remember { mutableIntStateOf(0) }
        LaunchedEffect(outline, widthPx, targetPx, gapPx, grouping, retry, pinching) {
            if (outline == null) { layout = null; return@LaunchedEffect }
            // The photo at the top stays at the top through the new layout.
            val before = layout
            val topRow = scroll.firstVisibleItemIndex - currentLeading
            val kept = anchor ?: (before?.rows?.getOrNull(topRow) as? GalleryRow.Media)?.let { before.outline.ids[it.start] }
            val offset = if (anchor != null) 0 else scroll.firstVisibleItemScrollOffset
            try {
                val around = if (pinching) anchor?.let(outline::indexOf)?.takeIf { it >= 0 } else null
                val rows = if (around != null) planAround(outline, around, grouping, widthPx, targetPx, gapPx)
                    else withContext(Dispatchers.Default) { planRows(outline, grouping, widthPx, targetPx, gapPx) }
                val next = GridLayout(outline, rows)
                layout = next
                failed = false
                val item = kept?.let(outline::indexOf) ?: -1
                if (item >= 0 && (anchor != null || topRow >= 0)) {
                    val row = next.rowOf(item)
                    if (row >= 0) scroll.scrollToItem(currentLeading + row, offset)
                }
                if (!pinching) anchor = null
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { failed = true }
        }
        val currentLayout by rememberUpdatedState(layout)
        // Read ahead: the records and thumbnails of the next rows in the
        // direction of travel, once scrolling slows down.
        LaunchedEffect(layout, private) {
            val shown = layout ?: return@LaunchedEffect
            var previous = 0
            snapshotFlow { scroll.firstVisibleItemIndex to (scroll.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) }
                .collectLatest { (first, last) ->
                    val down = first >= previous
                    previous = first
                    val rows = shown.rows
                    val firstRow = (first - currentLeading).coerceIn(0, maxOf(0, rows.size - 1))
                    val lastRow = (last - currentLeading).coerceIn(0, maxOf(0, rows.size - 1))
                    if (rows.isEmpty()) return@collectLatest
                    val ahead = if (down) (lastRow + 1)..minOf(rows.size - 1, lastRow + AHEAD_ROWS)
                        else maxOf(0, firstRow - AHEAD_ROWS) until firstRow
                    val items = ahead.flatMap { (rows[it] as? GalleryRow.Media)?.let { row -> row.start until row.end } ?: emptyList() }
                    if (items.isEmpty()) return@collectLatest
                    shown.outline.prefetch(items.min() - GalleryOutline.PAGE..items.max() + GalleryOutline.PAGE)
                    delay(120)
                    val records = (if (down) items else items.reversed()).mapNotNull {
                        try { shown.outline.fetchItem(it) } catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { null }
                    }
                    thumbnails.prefetch(records, private)
                }
        }
        val pinch = Modifier.pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                var start = currentZoom
                var scale = 1f
                do {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (event.changes.count { it.pressed } >= 2) {
                        if (!pinching) {
                            pinching = true; start = currentZoom; scale = 1f
                            val shown = currentLayout
                            val first = shown?.rows?.getOrNull(scroll.firstVisibleItemIndex - currentLeading)
                                ?: shown?.rows?.firstOrNull { it is GalleryRow.Media }
                            anchor = (first as? GalleryRow.Media)?.let { row -> shown?.outline?.ids?.getOrNull(row.start) }
                        }
                        scale *= event.calculateZoom()
                        val next = (start * scale).coerceIn(ZOOM_MIN, ZOOM_MAX)
                        if (kotlin.math.abs(next - currentZoom) >= 2f) currentOnZoom(next)
                        // Two fingers zoom; they must not also scroll or open a photo.
                        event.changes.forEach { if (it.positionChanged()) it.consume() }
                    }
                } while (event.changes.any { it.pressed })
                pinching = false
            }
        }
        LazyColumn(pinch.fillMaxSize(), state = scroll, verticalArrangement = Arrangement.spacedBy(GAP_DP.dp), contentPadding = contentPadding) {
            if (tags.isNotEmpty()) item(key = "tags", contentType = "tags") {
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = selectedTag == null, onClick = { onTag(null) }, label = { Text("All") })
                    tags.forEach { tag ->
                        FilterChip(selected = tag.name.equals(selectedTag, ignoreCase = true), onClick = { onTag(tag.name) }, label = { Text(tag.name) })
                    }
                }
            }
            if (header != null) item(key = "header", contentType = "header") { header() }
            if (shownAlbums.isNotEmpty()) item(key = "albums-heading", contentType = "heading") {
                Text(if (selectedTag != null) "Tagged “$selectedTag”" else albumsTitle, style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 16.dp).padding(top = 8.dp, bottom = 4.dp))
            }
            items(albumRows.size, key = { index -> albumRows[index].first()?.let { "albums-${it.gallery}-${it.id}" } ?: "albums-new" }, contentType = { "albums" }) { index ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    for (album in albumRows[index]) {
                        if (album == null) NewAlbumCard(onNewAlbum ?: {}, Modifier.weight(1f))
                        else AlbumCard(album, thumbnails, { onAlbum(album) }, Modifier.weight(1f))
                    }
                    repeat(albumColumns - albumRows[index].size) { Spacer(Modifier.weight(1f)) }
                }
            }
            if (hiddenCount > 0) item(key = "hidden-albums", contentType = "hidden-albums") {
                HiddenAlbumsRow(hiddenCount, hiddenUnlocked, onToggleHidden)
            }
            // One snapshot of the layout for count, keys and content, so a newer
            // layout never meets an older count during measurement.
            val shown = layout
            if (shown != null) items(count = shown.rows.size, key = { shown.rows[it].key }, contentType = { if (shown.rows[it] is GalleryRow.Heading) "date" else "media" }) { index ->
                when (val row = shown.rows[index]) {
                    is GalleryRow.Heading -> Text(row.label, style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = if (index == 0) 8.dp else 20.dp, bottom = 8.dp))
                    is GalleryRow.Media -> Row(
                        Modifier.fillMaxWidth().height(with(density) { row.height.toDp() }),
                        horizontalArrangement = Arrangement.spacedBy(with(density) { gapPx.toDp() }),
                    ) {
                        for (offset in row.widths.indices) {
                            val position = row.start + offset
                            key(shown.outline.ids[position]) {
                                val tile = Modifier.width(with(density) { row.widths[offset].toDp() }).fillMaxHeight()
                                val item = shown.outline.item(position)
                                if (item == null) Box(tile.background(MaterialTheme.colorScheme.surfaceContainerHigh))
                                else MediaTile(item, thumbnails,
                                    onClick = { if (selection.isNotEmpty() && onLongPress != null) onLongPress(item) else onOpen(position, item) },
                                    modifier = tile,
                                    selected = if (selection.isEmpty()) null else item.id in selection,
                                    onLongClick = onLongPress?.let { { it(item) } }, private = private)
                            }
                        }
                    }
                }
            }
            if (failed) item(key = "layout-error") { PageRetry { retry++ } }
            if (outline == null || (layout == null && outline.count > 0)) item(key = "loading") {
                Box(Modifier.fillMaxWidth().padding(vertical = 20.dp), contentAlignment = Alignment.Center) { ActivityBar(Activity("Loading"), Modifier.width(96.dp)) }
            }
        }
    }
}

/** Rows read ahead of the scroll: about two screens at the default size. */
private const val AHEAD_ROWS = 12

fun albumCaption(album: FolderAlbum): String = buildList {
    if (album.directItems > 0uL || album.childAlbums == 0uL) add(if (album.directItems == 1uL) "1 item" else "${album.directItems} items")
    if (album.childAlbums > 0uL) add(if (album.childAlbums == 1uL) "1 album" else "${album.childAlbums} albums")
}.joinToString(" · ")

@Composable
private fun AlbumCard(album: FolderAlbum, loader: ThumbnailLoader, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val cover = album.cover
    val bitmap by produceState(cover?.let(loader::peek), cover?.id, cover?.revision, loader) { if (value == null) value = cover?.let { loader.load(it, album.hidden) } }
    Column(modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick)
        .semantics(mergeDescendants = true) { contentDescription = "Album ${album.name}, ${albumCaption(album)}" }) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
            if (bitmap != null) Image(bitmap!!.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Icon(if (album.gallery.startsWith("album-")) Icons.Outlined.PhotoLibrary else Icons.Outlined.FolderOpen, null,
                Modifier.align(Alignment.Center).size(40.dp), tint = MaterialTheme.colorScheme.primary)
            if (album.hidden) Surface(Modifier.align(Alignment.TopStart).padding(8.dp), shape = RoundedCornerShape(50), color = Color.Black.copy(alpha = 0.5f)) {
                Icon(Icons.Outlined.VisibilityOff, "Hidden", Modifier.padding(4.dp).size(16.dp), tint = Color.White)
            }
            if (album.pinned) Surface(Modifier.align(Alignment.TopEnd).padding(8.dp), shape = RoundedCornerShape(50), color = Color.Black.copy(alpha = 0.5f)) {
                Icon(Icons.Outlined.Star, "Pinned", Modifier.padding(4.dp).size(16.dp), tint = Color.White)
            }
        }
        Text(album.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 2.dp, end = 2.dp, top = 8.dp))
        Text(albumCaption(album), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, modifier = Modifier.padding(horizontal = 2.dp).padding(bottom = 4.dp))
    }
}

/** Unlock hidden albums with the phone's lock, or lock them again. */
@Composable
private fun HiddenAlbumsRow(count: Int, unlocked: Boolean, onToggle: () -> Unit) {
    ListItem(
        headlineContent = { Text(if (unlocked) "Lock hidden albums" else "Hidden albums") },
        supportingContent = {
            Text(when {
                unlocked -> "They lock again when you leave the app"
                count == 1 -> "1 album · Unlock with your screen lock"
                else -> "$count albums · Unlock with your screen lock"
            })
        },
        leadingContent = {
            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                Icon(if (unlocked) Icons.Outlined.VisibilityOff else Icons.Outlined.Lock, null,
                    Modifier.padding(10.dp).size(20.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.padding(horizontal = 4.dp).clip(RoundedCornerShape(16.dp))
            .clickable(onClickLabel = if (unlocked) "Lock hidden albums" else "Unlock hidden albums", onClick = onToggle),
    )
}

/** The first card of the album row: start a new album. */
@Composable
private fun NewAlbumCard(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.clip(RoundedCornerShape(12.dp)).clickable(onClickLabel = "Create an album", onClick = onClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.Add, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.primary)
        }
        Text("New album", style = MaterialTheme.typography.titleSmall, maxLines = 1,
            modifier = Modifier.padding(start = 2.dp, end = 2.dp, top = 8.dp))
    }
}

@Composable
private fun PageRetry(retry: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Some photos couldn’t be loaded", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        FilledTonalButton(onClick = retry) { Text("Retry") }
    }
}

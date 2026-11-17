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
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import io.narl.protongallery.bridge.AlbumGrouping
import io.narl.protongallery.bridge.AlbumTag
import io.narl.protongallery.bridge.FolderAlbum
import io.narl.protongallery.bridge.GalleryItem
import io.narl.protongallery.bridge.planSmartGrid
import io.narl.protongallery.icons.FolderOpen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
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
    data class Media(override val key: String, val tiles: List<Tile>, val height: Int) : GalleryRow
    data class Tile(val index: Int, val item: GalleryItem, val width: Int)
}

/** Section heading for a capture time, or `null` when the grid is ungrouped. */
fun groupLabel(time: Long, grouping: AlbumGrouping): String? {
    if (grouping == AlbumGrouping.NONE) return null
    if (grouping == AlbumGrouping.DAY) return galleryDate(time)
    val date = Instant.ofEpochSecond(time).atZone(ZoneId.systemDefault())
    return if (grouping == AlbumGrouping.YEAR) date.year.toString()
    else date.format(DateTimeFormatter.ofPattern("LLLL yyyy", Locale.getDefault()))
}

/** Rust plans only the bounded loaded window. Compose virtualizes whole rows,
 * and item access still drives Paging's prefetch and bidirectional eviction.
 * Two fingers zoom: tiles grow or shrink and headings follow the size.
 */
@Composable
fun SmartMediaGrid(
    photos: LazyPagingItems<GalleryItem>, thumbnails: ThumbnailLoader,
    zoom: Float, onZoom: (Float) -> Unit, scroll: LazyListState, onOpen: (GalleryItem) -> Unit,
    albums: List<FolderAlbum> = emptyList(), onAlbum: (FolderAlbum) -> Unit = {},
    tags: List<AlbumTag> = emptyList(), selectedTag: String? = null, onTag: (String?) -> Unit = {},
    albumsTitle: String = "Albums",
    header: (@Composable () -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(),
    // Long-press selects; while anything is selected, a tap adds or removes.
    selection: Set<String> = emptySet(),
    onLongPress: ((GalleryItem) -> Unit)? = null,
) {
    val density = LocalDensity.current
    val snapshot = photos.itemSnapshotList.items
    var showHidden by rememberSaveable { mutableStateOf(false) }
    val hiddenCount = albums.count { it.hidden }
    val shownAlbums = if (showHidden) albums else albums.filterNot { it.hidden }
    val grouping = groupingFor(zoom)
    // The photo kept in view while pinching; cleared once a layout settles on it.
    var anchor by remember { mutableStateOf<String?>(null) }
    var pinching by remember { mutableStateOf(false) }
    val currentZoom by rememberUpdatedState(zoom)
    val currentOnZoom by rememberUpdatedState(onZoom)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val gapPx = with(density) { GAP_DP.dp.roundToPx() }.coerceAtLeast(1)
        val widthPx = constraints.maxWidth.coerceIn(1, 32768)
        val targetPx = with(density) { zoom.dp.roundToPx() }.coerceIn(1, 8192)
        val albumColumns = (maxWidth / 172.dp).toInt().coerceIn(2, 6)
        var failed by remember { mutableStateOf(false) }
        var retry by remember { mutableIntStateOf(0) }
        val rows by produceState<List<GalleryRow>>(emptyList(), snapshot, widthPx, targetPx, gapPx, retry, grouping) {
            try {
                value = withContext(Dispatchers.Default) {
                    val labels = snapshot.map { groupLabel(it.sortTime, grouping) }
                    buildList {
                        var start = 0
                        while (start < snapshot.size) {
                            val label = labels[start]
                            var end = start + 1
                            // Preserve row breaks when Paging drops/prepends a
                            // complete page. Its final row may stay short.
                            val pageEnd = minOf(snapshot.size, (start / 90 + 1) * 90)
                            while (end < pageEnd && labels[end] == label) end++
                            if (label != null && (start == 0 || labels[start - 1] != label)) {
                                add(GalleryRow.Heading("group-${snapshot[start].id}", label))
                            }
                            val section = snapshot.subList(start, end)
                            val planned = planSmartGrid(section.map { it.aspectRatio ?: 1.0 }, widthPx.toUInt(), targetPx.toUInt(), gapPx.toUInt())
                            for (row in planned) {
                                val first = start + row.start.toInt()
                                add(GalleryRow.Media("row-${snapshot[first].id}", row.tiles.mapIndexed { offset, size ->
                                    GalleryRow.Tile(first + offset, snapshot[first + offset], size.width.toInt())
                                }, row.tiles.first().height.toInt()))
                            }
                            start = end
                        }
                    }
                }
                failed = false
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { failed = true }
        }
        val albumRows = shownAlbums.chunked(albumColumns)
        val pageError = photos.loadState.prepend is LoadState.Error
        // Items listed before the photo rows; keep in step with the LazyColumn below.
        val leading = (if (tags.isNotEmpty()) 1 else 0) + (if (header != null) 1 else 0) +
            (if (shownAlbums.isNotEmpty()) 1 else 0) + albumRows.size + (if (hiddenCount > 0) 1 else 0) + (if (pageError) 1 else 0)
        val currentRows by rememberUpdatedState(rows)
        val currentLeading by rememberUpdatedState(leading)
        LaunchedEffect(rows) {
            val id = anchor ?: return@LaunchedEffect
            val index = rows.indexOfFirst { it is GalleryRow.Media && it.tiles.any { tile -> tile.item.id == id } }
            if (index >= 0) scroll.scrollToItem(leading + index)
            if (!pinching) anchor = null
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
                            val first = currentRows.getOrNull(scroll.firstVisibleItemIndex - currentLeading)
                                ?: currentRows.firstOrNull { it is GalleryRow.Media }
                            anchor = (first as? GalleryRow.Media)?.tiles?.firstOrNull()?.item?.id
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
            items(albumRows.size, key = { "albums-${albumRows[it].first().gallery}-${albumRows[it].first().id}" }, contentType = { "albums" }) { index ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    for (album in albumRows[index]) AlbumCard(album, thumbnails, { onAlbum(album) }, Modifier.weight(1f))
                    repeat(albumColumns - albumRows[index].size) { Spacer(Modifier.weight(1f)) }
                }
            }
            if (hiddenCount > 0) item(key = "hidden-albums") {
                OutlinedButton(onClick = { showHidden = !showHidden }, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Text(if (showHidden) "Hide hidden albums" else if (hiddenCount == 1) "Show 1 hidden album" else "Show $hiddenCount hidden albums")
                }
            }
            if (pageError) item(key = "prepend-error") { PageRetry(photos::retry) }
            // One snapshot of the layout for count, keys and content: reading the
            // state in each lambda let a newer, shorter layout meet an older count
            // during measurement and crash with IndexOutOfBoundsException.
            val laidOut = rows
            items(count = laidOut.size, key = { laidOut[it].key }, contentType = { if (laidOut[it] is GalleryRow.Heading) "date" else "media" }) { index ->
                when (val row = laidOut[index]) {
                    is GalleryRow.Heading -> Text(row.label, style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = if (index == 0) 8.dp else 20.dp, bottom = 8.dp))
                    is GalleryRow.Media -> Row(
                        Modifier.fillMaxWidth().height(with(density) { row.height.toDp() }),
                        horizontalArrangement = Arrangement.spacedBy(with(density) { gapPx.toDp() }),
                    ) {
                        for (tile in row.tiles) {
                            // Touch the current Paging index only while this row is visible.
                            // Snapshot replacement can briefly outrun the background layout.
                            val current = if (tile.index < photos.itemCount) photos[tile.index] else null
                            key(tile.item.id) {
                                val shown = current?.takeIf { it.id == tile.item.id } ?: tile.item
                                MediaTile(shown, thumbnails,
                                    onClick = { if (selection.isNotEmpty() && onLongPress != null) onLongPress(shown) else onOpen(shown) },
                                    modifier = Modifier.width(with(density) { tile.width.toDp() }).fillMaxHeight(),
                                    selected = if (selection.isEmpty()) null else shown.id in selection,
                                    onLongClick = onLongPress?.let { { it(shown) } })
                            }
                        }
                    }
                }
            }
            if (failed) item(key = "layout-error") { PageRetry { retry++ } }
            if (photos.loadState.append is LoadState.Error || photos.loadState.refresh is LoadState.Error) item(key = "append-error") { PageRetry(photos::retry) }
            if ((photos.loadState.refresh is LoadState.Loading && rows.isEmpty() && shownAlbums.isEmpty()) || photos.loadState.append is LoadState.Loading) item(key = "loading") {
                Box(Modifier.fillMaxWidth().padding(vertical = 20.dp), contentAlignment = Alignment.Center) { ActivityBar(Activity("Loading"), Modifier.width(96.dp)) }
            }
        }
    }
}

fun albumCaption(album: FolderAlbum): String = buildList {
    if (album.directItems > 0uL || album.childAlbums == 0uL) add(if (album.directItems == 1uL) "1 item" else "${album.directItems} items")
    if (album.childAlbums > 0uL) add(if (album.childAlbums == 1uL) "1 album" else "${album.childAlbums} albums")
}.joinToString(" · ")

@Composable
private fun AlbumCard(album: FolderAlbum, loader: ThumbnailLoader, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val cover = album.cover
    val bitmap by produceState<android.graphics.Bitmap?>(null, cover?.id, cover?.revision, loader) { value = cover?.let { loader.load(it) } }
    Column(modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick)
        .semantics(mergeDescendants = true) { contentDescription = "Album ${album.name}, ${albumCaption(album)}" }) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
            if (bitmap != null) Image(bitmap!!.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Icon(Icons.Outlined.FolderOpen, null, Modifier.align(Alignment.Center).size(40.dp), tint = MaterialTheme.colorScheme.primary)
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

@Composable
private fun PageRetry(retry: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Some photos couldn’t be loaded", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        FilledTonalButton(onClick = retry) { Text("Retry") }
    }
}

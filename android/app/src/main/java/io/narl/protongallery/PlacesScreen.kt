package io.narl.protongallery

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.narl.protongallery.bridge.GalleryItem
import io.narl.protongallery.bridge.MapCamera
import io.narl.protongallery.bridge.PlaceMarker
import io.narl.protongallery.bridge.PlacePhoto
import io.narl.protongallery.bridge.PlacesLayout
import io.narl.protongallery.bridge.PlacesMap
import io.narl.protongallery.bridge.mapAttribution
import io.narl.protongallery.bridge.mapScaleBar
import io.narl.protongallery.bridge.placesGeometry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlin.math.min

data class PlacesProgress(
    val newPhotos: List<PlacePhoto> = emptyList(),
    val checked: Int = 0,
    val total: Int = 0,
    val failed: Int = 0,
    val scanning: Boolean = true,
)

data class PlacesActions(
    val scan: suspend ((PlacesProgress) -> Unit) -> Unit = { _ -> },
    val open: suspend (String) -> Pair<GalleryOutline, Int>? = { null },
    /** The timeline record of a located photo, for its thumbnail. */
    val item: suspend (String) -> GalleryItem? = { null },
    /** The offline basemap; null draws coarse outlines. The caller closes it. */
    val basemap: suspend () -> PlacesMap? = { null },
)

/** A minus sign, matching the outlined plus. */
private val ZoomOutIcon = materialIcon("Outlined.ZoomOut") {
    materialPath { moveTo(19f, 13f); horizontalLineTo(5f); verticalLineToRelative(-2f); horizontalLineToRelative(14f); close() }
}

/** Four corners closing in: fit the photos into view. */
private val FitIcon = materialIcon("Outlined.FitPhotos") {
    materialPath {
        moveTo(3f, 3f); horizontalLineToRelative(6f); verticalLineToRelative(2f); horizontalLineTo(5f); verticalLineToRelative(4f); horizontalLineTo(3f); close()
        moveTo(21f, 3f); verticalLineToRelative(6f); horizontalLineToRelative(-2f); verticalLineTo(5f); horizontalLineToRelative(-4f); verticalLineTo(3f); close()
        moveTo(3f, 21f); verticalLineToRelative(-6f); horizontalLineToRelative(2f); verticalLineToRelative(4f); horizontalLineToRelative(4f); verticalLineToRelative(2f); close()
        moveTo(21f, 21f); horizontalLineToRelative(-6f); verticalLineToRelative(-2f); horizontalLineToRelative(4f); verticalLineToRelative(-4f); horizontalLineToRelative(2f); close()
        moveTo(12f, 9f); arcToRelative(3f, 3f, 0f, true, true, 0f, 6f); arcToRelative(3f, 3f, 0f, true, true, 0f, -6f); close()
    }
}

/** Deepest zoom a fit goes to, so one place doesn't zoom to street level. */
private const val FIT_ZOOM = 64.0
private const val MAX_ZOOM = 8192.0
/** A marker picture's side as a share of its bubble's diameter, and its corner. */
private const val MARKER_SIDE = .9f
private const val CORNER = .24f
/** Marker pictures loaded at once; the thumbnail loader limits the network. */
private const val COVERS_AT_ONCE = 8

private fun MapCamera.zoomed(factor: Double) = MapCamera(x, y, (zoom * factor).coerceIn(1.0, MAX_ZOOM))

/** `camera` zoomed by `factor` keeping the point under `focus` (px) in place. */
private fun MapCamera.zoomedAt(factor: Double, focus: Offset, pan: Offset, size: IntSize): MapCamera {
    val scaled = zoomed(factor)
    val oldScale = min(size.width, size.height) * zoom
    val newScale = min(size.width, size.height) * scaled.zoom
    val dx = focus.x - size.width / 2.0
    val dy = focus.y - size.height / 2.0
    return MapCamera(
        (x + dx / oldScale - (dx + pan.x) / newScale).coerceIn(0.0, 1.0),
        (y + dy / oldScale - (dy + pan.y) / newScale).coerceIn(0.0, 1.0),
        scaled.zoom,
    )
}

private fun fit(photos: List<PlacePhoto>, size: IntSize, maxZoom: Double): MapCamera {
    if (photos.isEmpty() || size.width == 0 || size.height == 0) return MapCamera(.5, .5, 1.0)
    val west = photos.minOf { it.x }
    val east = photos.maxOf { it.x }
    val north = photos.minOf { it.y }
    val south = photos.maxOf { it.y }
    val edge = min(size.width, size.height).toDouble()
    val span = maxOf((east - west) * edge / size.width, (south - north) * edge / size.height)
    return MapCamera((west + east) / 2, (north + south) / 2, (.72 / span).coerceIn(1.0, maxZoom))
}

/** Markers as laid out for `camera`; drawn against a newer camera until the next ones are ready. */
private class MarkerView(val camera: MapCamera, val size: IntSize, val markers: List<PlaceMarker>) {
    /** Where a marker's centre is now, in px, under `current`. */
    fun center(marker: PlaceMarker, current: MapCamera, density: Float): Offset {
        val then = min(size.width, size.height) * camera.zoom
        val x = (marker.x * density - size.width * .5) / then + camera.x
        val y = (marker.y * density - size.height * .5) / then + camera.y
        return current.toScreen(x, y, size)
    }
}

private data class MapOutline(val land: List<Path>, val borders: List<Path>)

private fun outlinePaths(rings: List<List<Float>>, close: Boolean): List<Path> = rings.map { ring ->
    Path().apply {
        if (ring.size >= 2) {
            moveTo(ring[0], ring[1])
            for (i in 2 until ring.size step 2) lineTo(ring[i], ring[i + 1])
            if (close) close()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlacesScreen(actions: PlacesActions, revision: Int, padding: PaddingValues, dark: Boolean,
    thumbnail: suspend (PlacePhoto) -> ImageBitmap?, onOpen: (PlacePhoto) -> Unit) {
    val photos = remember { mutableStateListOf<PlacePhoto>() }
    val layout = remember { PlacesLayout() }
    DisposableEffect(layout) { onDispose { layout.close() } }
    var progress by remember { mutableStateOf(PlacesProgress()) }
    var retry by remember { mutableIntStateOf(0) }
    var failed by remember { mutableStateOf(false) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var camera by remember { mutableStateOf<MapCamera?>(null) }
    var selected by remember { mutableStateOf<List<PlacePhoto>?>(null) }
    var markers by remember { mutableStateOf<MarkerView?>(null) }
    var layoutVersion by remember { mutableIntStateOf(0) }
    val covers = remember(revision, retry) { mutableStateMapOf<String, ImageBitmap>() }

    // The basemap, or the coarse outlines when there is none.
    var tiles by remember { mutableStateOf<MapTiles?>(null) }
    var outline by remember { mutableStateOf<MapOutline?>(null) }
    var mapReady by remember { mutableStateOf(false) }
    var tilesVersion by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        val map = try { actions.basemap() } catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { null }
        if (map != null) {
            tiles = MapTiles(map)
            try { mapReady = true; kotlinx.coroutines.awaitCancellation() } finally { tiles?.clear(); map.close() }
        } else {
            outline = withContext(Dispatchers.Default) {
                val geometry = placesGeometry()
                MapOutline(outlinePaths(geometry.land, true), outlinePaths(geometry.borders, false))
            }
            mapReady = true
        }
    }
    LaunchedEffect(revision, retry) {
        photos.clear()
        layout.clear()
        layoutVersion++
        markers = null
        selected = null
        progress = PlacesProgress()
        failed = false
        try {
            actions.scan { batch ->
                if (batch.newPhotos.isNotEmpty()) {
                    layout.add(batch.newPhotos)
                    photos += batch.newPhotos
                    layoutVersion++
                }
                progress = batch
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            failed = true
            progress = progress.copy(scanning = false)
        }
    }

    val palette = MaterialTheme.colorScheme
    val density = LocalDensity.current.density
    val current = camera ?: remember(photos.size, canvasSize) { fit(photos.toList(), canvasSize, FIT_ZOOM) }
    val latestCamera by rememberUpdatedState(current)
    // Markers are laid out in the background on the newest camera once the
    // previous layout is done; the last markers stay on the map meanwhile.
    LaunchedEffect(density) {
        snapshotFlow { Triple(layoutVersion, latestCamera, canvasSize) }.conflate().collect { (_, view, size) ->
            if (size.width == 0 || size.height == 0) return@collect
            markers = withContext(Dispatchers.Default) {
                MarkerView(view, size, layout.layout(view, size.width / density, size.height / density))
            }
        }
    }
    // Tiles render nearest the centre first, two at a time; a camera change
    // starts over from the new centre, keeping what is done.
    LaunchedEffect(tiles, dark, density) {
        val layer = tiles ?: return@LaunchedEffect
        val renderer = Dispatchers.Default.limitedParallelism(2)
        snapshotFlow { latestCamera to canvasSize }.collectLatest { (view, size) ->
            val wanted = mapTiles(view, size, density).filterNot { layer.has(it.key(dark)) }
            val slots = Semaphore(2)
            coroutineScope {
                for (tile in wanted) {
                    slots.acquire()
                    launch(renderer) {
                        try { layer.render(tile, dark) } finally { slots.release() }
                        tilesVersion++
                    }
                }
            }
        }
    }
    // Marker pictures: the newest photo of each marker, most photos first.
    LaunchedEffect(revision, retry) {
        snapshotFlow { markers?.markers?.sortedByDescending { it.photos }?.mapNotNull { photos.getOrNull(it.cover.toInt()) } ?: emptyList() }
            .distinctUntilChanged().collectLatest { wanted ->
                val slots = Semaphore(COVERS_AT_ONCE)
                coroutineScope {
                    for (photo in wanted) {
                        if (covers.containsKey(photo.id)) continue
                        launch {
                            slots.withPermit {
                                try { thumbnail(photo)?.let { covers[photo.id] = it } }
                                catch (cancel: CancellationException) { throw cancel }
                                catch (_: Exception) {}
                            }
                        }
                    }
                }
            }
    }

    val latestMarkers by rememberUpdatedState(markers)
    val latestOpen by rememberUpdatedState(onOpen)
    val measurer = rememberTextMeasurer()
    val countText = TextStyle(color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    val placeText = TextStyle(color = palette.onSurface, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    val scaleText = TextStyle(color = palette.onSurface, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    // Counts and place names repeat from frame to frame: measure each once.
    val labels = remember(measurer, density, palette) { HashMap<Pair<String, Int>, TextLayoutResult>() }
    fun label(text: String, kind: Int): TextLayoutResult {
        if (labels.size > 512) labels.clear()
        return labels.getOrPut(text to kind) { measurer.measure(text, when (kind) { 0 -> countText; 1 -> placeText; else -> scaleText }) }
    }
    val ring = if (dark) Color(0xFFEFE9FF) else Color.White
    val shadow = Color(0x470D0524)
    val markerPath = remember { Path() }

    val status = when {
        failed -> "Couldn’t load photo locations"
        progress.scanning && progress.total > 0 -> "Checking locations… ${progress.checked} of ${progress.total}"
        progress.scanning -> "Opening Places…"
        progress.failed > 0 -> "${photos.size} on the map. ${progress.failed} couldn’t be checked."
        photos.isEmpty() -> "No photos with location data"
        else -> "${photos.size} ${if (photos.size == 1) "photo" else "photos"} on the map"
    }
    Box(Modifier.fillMaxSize().clipToBounds()) {
        Canvas(Modifier.fillMaxSize().onSizeChanged { canvasSize = it }
            .semantics {
                contentDescription = if (mapReady) "Photo map" else "Loading photo map"
                val count = markers?.markers?.size ?: 0
                stateDescription = if (count == 1) "1 place" else "$count places"
            }
            .pointerInput(Unit) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    camera = latestCamera.zoomedAt(zoom.toDouble(), centroid, pan, size)
                }
            }.pointerInput(Unit) {
                detectTapGestures(onDoubleTap = { at -> camera = latestCamera.zoomedAt(2.0, at, Offset.Zero, size) }) { tap ->
                    val shown = latestMarkers ?: return@detectTapGestures
                    val marker = shown.markers.asReversed().firstOrNull {
                        (shown.center(it, latestCamera, density) - tap).getDistance() <= it.radius * density
                    } ?: return@detectTapGestures
                    val members = marker.members.mapNotNull { photos.getOrNull(it.toInt()) }
                    if (members.size == 1) latestOpen(members.first())
                    else if (members.isNotEmpty()) {
                        val first = members.first()
                        val spread = members.any { it.x != first.x || it.y != first.y }
                        val closer = fit(members, size, MAX_ZOOM)
                        if (spread && closer.zoom > latestCamera.zoom * 1.25) camera = closer
                        else selected = members.sortedBy { it.index }
                    }
                }
            }) {
            tilesVersion
            val layer = tiles
            if (layer != null) layer.draw(this, mapTiles(current, canvasSize, density), dark)
            else {
                drawRect(palette.surfaceContainerLow)
                outline?.let { drawOutline(it, current, canvasSize, palette.surfaceContainerHighest, palette.outlineVariant) }
            }
            val shown = markers ?: return@Canvas
            // Shadows, rings and pictures first, then counts and names over all of them.
            for (marker in shown.markers) {
                val center = shown.center(marker, current, density)
                val side = marker.radius * 2 * MARKER_SIDE * density
                val corner = side * CORNER
                fun rounded(grow: Float, dy: Float = 0f) = RoundRect(center.x - side / 2 - grow, center.y - side / 2 - grow + dy,
                    center.x + side / 2 + grow, center.y + side / 2 + grow + dy, CornerRadius(corner + grow))
                markerPath.reset(); markerPath.addRoundRect(rounded(2.5f * density, 2f * density))
                drawPath(markerPath, shadow)
                markerPath.reset(); markerPath.addRoundRect(rounded(2.5f * density))
                drawPath(markerPath, ring)
                markerPath.reset(); markerPath.addRoundRect(rounded(0f))
                val cover = photos.getOrNull(marker.cover.toInt())?.let { covers[it.id] }
                if (cover != null) clipPath(markerPath) {
                    val crop = min(cover.width, cover.height)
                    drawImage(cover, srcOffset = IntOffset((cover.width - crop) / 2, (cover.height - crop) / 2), srcSize = IntSize(crop, crop),
                        dstOffset = IntOffset((center.x - side / 2).toInt(), (center.y - side / 2).toInt()), dstSize = IntSize(side.toInt(), side.toInt()))
                } else {
                    drawPath(markerPath, palette.primary)
                    drawCircle(Color.White.copy(alpha = .85f), 3.5f * density, center)
                }
            }
            for (marker in shown.markers) {
                val center = shown.center(marker, current, density)
                val side = marker.radius * 2 * MARKER_SIDE * density
                if (marker.photos > 1u) {
                    val count = label(marker.count, 0)
                    val width = maxOf(count.size.width + 12 * density, 22 * density)
                    val height = 22 * density
                    val badge = Offset(center.x + side / 2 - width / 2 + 6 * density, center.y - side / 2 - 4 * density)
                    val pill = RoundRect(badge.x - width / 2, badge.y - height / 2, badge.x + width / 2, badge.y + height / 2, CornerRadius(height / 2))
                    markerPath.reset(); markerPath.addRoundRect(pill)
                    drawPath(markerPath, palette.primary)
                    drawPath(markerPath, ring, style = Stroke(1.5f * density))
                    drawText(count, topLeft = badge - Offset(count.size.width / 2f, count.size.height / 2f))
                }
                val place = marker.place
                if (marker.labelled && place != null) {
                    val name = label(place, 1)
                    val width = name.size.width + 14 * density
                    val height = 20 * density
                    val top = center.y + marker.radius * density + 4 * density
                    markerPath.reset()
                    markerPath.addRoundRect(RoundRect(center.x - width / 2, top, center.x + width / 2, top + height, CornerRadius(height / 2)))
                    drawPath(markerPath, palette.surface.copy(alpha = .92f))
                    drawText(name, topLeft = Offset(center.x - name.size.width / 2f, top + (height - name.size.height) / 2))
                }
            }
        }

        // Progress, and a retry after failures, as a chip over the map.
        Surface(Modifier.align(Alignment.TopCenter).padding(padding).padding(top = 8.dp, start = 16.dp, end = 16.dp),
            shape = RoundedCornerShape(16.dp), color = palette.surface.copy(alpha = .94f), shadowElevation = 2.dp) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(status, style = MaterialTheme.typography.labelLarge)
                    if (failed || (!progress.scanning && progress.failed > 0)) TextButton(onClick = { retry++ }) { Text("Retry") }
                }
                if (progress.scanning && progress.total > 0) LinearProgressIndicator(
                    progress = { progress.checked.toFloat() / progress.total },
                    modifier = Modifier.width(180.dp).padding(top = 6.dp))
            }
        }

        // Zoom and fit in a floating pill.
        Surface(Modifier.align(Alignment.BottomEnd).padding(padding).padding(16.dp),
            shape = RoundedCornerShape(24.dp), color = palette.surface.copy(alpha = .94f), shadowElevation = 3.dp) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                IconButton(onClick = { camera = current.zoomed(2.0) }) { Icon(Icons.Outlined.Add, "Zoom in") }
                HorizontalDivider(Modifier.width(24.dp))
                IconButton(onClick = { camera = current.zoomed(.5) }) { Icon(ZoomOutIcon, "Zoom out") }
                HorizontalDivider(Modifier.width(24.dp))
                IconButton(onClick = { camera = null; selected = null }) { Icon(FitIcon, "Fit photos") }
            }
        }

        // Scale bar and the credits the map data asks for.
        Column(Modifier.align(Alignment.BottomStart).padding(padding).padding(start = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val bar = if (canvasSize.width > 0) mapScaleBar(current, canvasSize.width / density, canvasSize.height / density, 110f) else null
            if (bar != null) Surface(shape = RoundedCornerShape(6.dp), color = palette.surface.copy(alpha = .8f)) {
                Column(Modifier.padding(horizontal = 6.dp, vertical = 3.dp)) {
                    Text(bar.label, style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.semantics { contentDescription = "Scale ${bar.label}" })
                    Canvas(Modifier.width(bar.length.dp).height(6.dp)) {
                        val stroke = 1.5.dp.toPx()
                        val line = Path().apply { moveTo(0f, 0f); lineTo(0f, size.height); lineTo(size.width, size.height); lineTo(size.width, 0f) }
                        drawPath(line, palette.onSurface, style = Stroke(stroke))
                    }
                }
            }
            Surface(shape = RoundedCornerShape(6.dp), color = palette.surface.copy(alpha = .8f)) {
                Text(if (tiles != null) mapAttribution() else "Map data from Natural Earth and GeoNames (CC BY 4.0)",
                    Modifier.padding(horizontal = 6.dp, vertical = 2.dp).fillMaxWidth(.62f),
                    style = MaterialTheme.typography.labelSmall, color = palette.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (photos.isNotEmpty() && selected == null) Surface(shape = CircleShape, color = palette.surface.copy(alpha = .94f), shadowElevation = 2.dp) {
                TextButton(onClick = { selected = photos.sortedBy { it.index } }) { Text("List photos") }
            }
        }
    }

    selected?.let { entries ->
        ModalBottomSheet(onDismissRequest = { selected = null }, sheetState = rememberModalBottomSheetState()) {
            val place = entries.firstOrNull()?.place?.takeIf { name -> entries.all { it.place == name } && entries.size < photos.size }
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(place ?: "Photos on the map", style = MaterialTheme.typography.titleMedium)
                    Text("${entries.size} ${if (entries.size == 1) "photo" else "photos"}", style = MaterialTheme.typography.bodySmall,
                        color = palette.onSurfaceVariant)
                }
                TextButton(onClick = { selected = null }) { Text("Close") }
            }
            LazyVerticalGrid(GridCells.Adaptive(104.dp), Modifier.fillMaxWidth().padding(top = 8.dp),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(entries, key = { it.id }) { photo -> PlaceTile(photo, covers[photo.id], thumbnail) { onOpen(photo) } }
            }
        }
    }
}

@Composable
private fun PlaceTile(photo: PlacePhoto, known: ImageBitmap?, thumbnail: suspend (PlacePhoto) -> ImageBitmap?, onOpen: () -> Unit) {
    val picture by produceState(known, photo.id) {
        if (value == null) value = try { thumbnail(photo) } catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { null }
    }
    Box(Modifier.aspectRatio(1f).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)
        .clickable(onClickLabel = "Open") { onOpen() }.semantics { contentDescription = "Photo: ${photo.name}" }) {
        picture?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
    }
}

private fun DrawScope.drawOutline(outline: MapOutline, camera: MapCamera, viewport: IntSize, land: Color, border: Color) {
    val scale = (min(viewport.width, viewport.height) * camera.zoom).toFloat()
    withTransform({
        translate(size.width * .5f - camera.x.toFloat() * scale, size.height * .5f - camera.y.toFloat() * scale)
        scale(scale, scale, Offset.Zero)
    }) {
        outline.land.forEach { drawPath(it, land) }
        outline.borders.forEach { drawPath(it, border, style = Stroke(1.dp.toPx() / scale)) }
    }
}


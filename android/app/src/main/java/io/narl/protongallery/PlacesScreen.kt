package io.narl.protongallery

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.narl.protongallery.bridge.PlacePhoto
import io.narl.protongallery.bridge.clusterPlaces
import io.narl.protongallery.bridge.placesGeometry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
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
)

private data class PlaceCamera(val x: Float = .5f, val y: Float = .5f, val zoom: Float = 1f) {
    fun scale(size: IntSize) = min(size.width, size.height).coerceAtLeast(1) * zoom
    fun point(photo: PlacePhoto, size: IntSize): Offset {
        val scale = scale(size)
        return Offset(size.width * .5f + (photo.x.toFloat() - x) * scale,
            size.height * .5f + (photo.y.toFloat() - y) * scale)
    }

    fun zoomed(factor: Float) = copy(zoom = (zoom * factor).coerceIn(1f, 8192f))
}

private fun fit(photos: List<PlacePhoto>, size: IntSize, maxZoom: Float): PlaceCamera {
    if (photos.isEmpty() || size.width == 0 || size.height == 0) return PlaceCamera()
    val west = photos.minOf { it.x }
    val east = photos.maxOf { it.x }
    val north = photos.minOf { it.y }
    val south = photos.maxOf { it.y }
    val edge = min(size.width, size.height)
    val span = maxOf((east - west) * edge / size.width, (south - north) * edge / size.height)
    return PlaceCamera(((west + east) / 2).toFloat(), ((north + south) / 2).toFloat(),
        (.72 / span).toFloat().coerceIn(1f, maxZoom))
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

@Composable
fun PlacesScreen(actions: PlacesActions, revision: Int, padding: PaddingValues, onOpen: (PlacePhoto) -> Unit) {
    val photos = remember { mutableStateListOf<PlacePhoto>() }
    var progress by remember { mutableStateOf(PlacesProgress()) }
    var retry by remember { mutableIntStateOf(0) }
    var failed by remember { mutableStateOf(false) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var camera by remember { mutableStateOf<PlaceCamera?>(null) }
    var selected by remember { mutableStateOf<List<PlacePhoto>?>(null) }
    var outline by remember { mutableStateOf<MapOutline?>(null) }

    LaunchedEffect(Unit) {
        outline = withContext(Dispatchers.Default) {
            val geometry = placesGeometry()
            MapOutline(outlinePaths(geometry.land, true), outlinePaths(geometry.borders, false))
        }
    }
    LaunchedEffect(revision, retry) {
        photos.clear()
        selected = null
        progress = PlacesProgress()
        failed = false
        try {
            actions.scan { batch ->
                photos += batch.newPhotos
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
    val current = camera ?: fit(photos, canvasSize, 64f)
    val clusters = remember(current, canvasSize, density, photos.size, revision, retry) {
        val points = photos.flatMap { photo ->
            val point = current.point(photo, canvasSize)
            listOf(point.x / density, point.y / density)
        }
        clusterPlaces(points, canvasSize.width / density, canvasSize.height / density)
    }
    val latestCamera by rememberUpdatedState(current)
    val latestClusters by rememberUpdatedState(clusters)
    val latestOpen by rememberUpdatedState(onOpen)
    val measurer = rememberTextMeasurer()
    val markerText = TextStyle(color = palette.onPrimary, fontSize = 12.sp)
    val placeText = TextStyle(color = palette.onSurface, fontSize = 11.sp)
    val status = when {
        failed -> "Couldn’t load photo locations"
        progress.scanning && progress.total > 0 -> "Checking locations… ${progress.checked} of ${progress.total}"
        progress.scanning -> "Opening Places…"
        progress.failed > 0 -> "${photos.size} on the map. ${progress.failed} couldn’t be checked."
        photos.isEmpty() -> "No photos with location data"
        else -> "${photos.size} ${if (photos.size == 1) "photo" else "photos"} on the map"
    }
    Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(status, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { camera = current.zoomed(.5f) }, modifier = Modifier.semantics { contentDescription = "Zoom out" }) { Text("−") }
            TextButton(onClick = { camera = current.zoomed(2f) }, modifier = Modifier.semantics { contentDescription = "Zoom in" }) { Text("+") }
            TextButton(onClick = { camera = null; selected = null }) { Text("Fit photos") }
            if (failed || (!progress.scanning && progress.failed > 0)) TextButton(onClick = { retry++ }) { Text("Retry") }
        }
        Box(Modifier.weight(1f).fillMaxWidth().clipToBounds().background(palette.surfaceContainerLow)) {
            Canvas(Modifier.fillMaxSize().onSizeChanged { canvasSize = it }
                .semantics { contentDescription = if (outline == null) "Loading photo map" else "Photo map" }
                .pointerInput(Unit) {
                    detectTransformGestures { centroid, pan, zoom, _ ->
                        val old = latestCamera
                        val scaled = old.zoomed(zoom)
                        val oldScale = old.scale(size)
                        val newScale = scaled.scale(size)
                        val delta = centroid - Offset(size.width / 2f, size.height / 2f)
                        camera = scaled.copy(
                            x = (old.x + delta.x / oldScale - (delta.x + pan.x) / newScale).coerceIn(0f, 1f),
                            y = (old.y + delta.y / oldScale - (delta.y + pan.y) / newScale).coerceIn(0f, 1f),
                        )
                        selected = null
                    }
                }.pointerInput(Unit) {
                    detectTapGestures { tap ->
                        val cluster = latestClusters.asReversed().firstOrNull {
                            (Offset(it.x * density, it.y * density) - tap).getDistance() <= it.radius * density
                        } ?: return@detectTapGestures
                        val members = cluster.members.mapNotNull { photos.getOrNull(it.toInt()) }
                        if (members.size == 1) latestOpen(members.first())
                        else if (members.isNotEmpty()) {
                            val first = members.first()
                            val spread = members.any { it.x != first.x || it.y != first.y }
                            val closer = fit(members, size, 8192f)
                            if (spread && closer.zoom > latestCamera.zoom * 1.25f) camera = closer else selected = members
                        }
                    }
                }) {
                outline?.let { drawOutline(it, current, canvasSize, palette.surfaceContainerHighest, palette.outlineVariant) }
                for (cluster in clusters) {
                    val center = Offset(cluster.x * density, cluster.y * density)
                    val radius = cluster.radius * density
                    drawCircle(palette.primary, radius, center)
                    drawCircle(palette.onPrimaryContainer, radius, center, style = Stroke(1.dp.toPx()))
                    val label = measurer.measure(cluster.count, markerText)
                    drawText(label, topLeft = center - Offset(label.size.width / 2f, label.size.height / 2f))
                    val place = cluster.members.firstOrNull()?.let { photos.getOrNull(it.toInt())?.place }
                    if (place != null && cluster.members.all { photos.getOrNull(it.toInt())?.place == place }) {
                        val name = measurer.measure(place, placeText)
                        drawText(name, topLeft = Offset(center.x - name.size.width / 2f, center.y + radius + 3.dp.toPx()))
                    }
                }
            }
            if (outline == null) {
                Text("Loading map…", Modifier.align(Alignment.TopCenter).padding(12.dp),
                    style = MaterialTheme.typography.bodySmall)
            }
        }
        if (photos.isNotEmpty() && selected == null) {
            TextButton(onClick = { selected = photos.toList() }) { Text("List photos") }
        }
        selected?.let { entries ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(entries.firstOrNull()?.place.takeIf { entries.size < photos.size } ?: "Photos on the map",
                    Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                TextButton(onClick = { selected = null }) { Text("Close") }
            }
            LazyColumn(Modifier.heightIn(max = 150.dp)) {
                items(entries, key = { it.id }) { photo ->
                    Text(photo.name, Modifier.fillMaxWidth().clickable { onOpen(photo) }.padding(12.dp),
                        style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        Text("Map data from Natural Earth and GeoNames (CC BY 4.0)",
            Modifier.padding(vertical = 4.dp), style = MaterialTheme.typography.labelSmall, color = palette.onSurfaceVariant)
    }
}

private fun DrawScope.drawOutline(outline: MapOutline, camera: PlaceCamera, viewport: IntSize,
    land: androidx.compose.ui.graphics.Color, border: androidx.compose.ui.graphics.Color) {
    val scale = camera.scale(viewport)
    withTransform({
        translate(size.width * .5f - camera.x * scale, size.height * .5f - camera.y * scale)
        scale(scale, scale, Offset.Zero)
    }) {
        outline.land.forEach { drawPath(it, land) }
        outline.borders.forEach { drawPath(it, border, style = Stroke(1.dp.toPx() / scale)) }
    }
}

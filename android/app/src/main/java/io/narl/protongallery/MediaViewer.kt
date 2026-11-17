package io.narl.protongallery

import android.graphics.Bitmap
import android.net.Uri
import android.text.format.Formatter
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import io.narl.protongallery.icons.*
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import io.narl.protongallery.bridge.GalleryItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Viewer keeps a stable snapshot of the bounded, loaded gallery window. */
@Composable
fun MediaViewer(items: List<GalleryItem>, initial: Int, thumbnails: ThumbnailLoader, onClose: () -> Unit) {
    val pager = rememberPagerState(initialPage = initial) { items.size }
    var zoomed by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf(false) }
    val current = items[pager.currentPage]
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            HorizontalPager(state = pager, key = { items[it].id }, userScrollEnabled = !zoomed, modifier = Modifier.fillMaxSize()) { index ->
                val item = items[index]
                if (item.isVideo && index == pager.currentPage) VideoPlayer(item)
                else ZoomablePhoto(item, thumbnails, index == pager.currentPage) { zoomed = it }
            }
            Row(Modifier.align(Alignment.TopCenter).fillMaxWidth().background(Color.Black.copy(alpha = 0.7f)).statusBarsPadding().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back to gallery", tint = Color.White) }
                Text("${pager.currentPage + 1} / ${items.size}", color = Color.White, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f).padding(start = 8.dp))
                IconButton(onClick = { info = !info }) { Icon(Icons.Outlined.Info, "Photo details", tint = Color.White) }
            }
            if (!current.isVideo || info) Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.Black.copy(alpha = 0.75f)).navigationBarsPadding().padding(24.dp)) {
                Text(current.name, color = Color.White, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.OfflinePin, null, Modifier.size(16.dp), tint = Color(0xFFBDA9FF))
                    Text("Available on this device", color = Color(0xFFE2D9F3), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 7.dp))
                }
                if (info) Text("${Formatter.formatFileSize(LocalContext.current, current.sizeBytes.toLong())}\nAdded ${galleryDate(current.sortTime)}", color = Color(0xFFE2D9F3), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 12.dp))
            }
        }
    }
}

@Composable
private fun ZoomablePhoto(item: GalleryItem, loader: ThumbnailLoader, active: Boolean, onZoom: (Boolean) -> Unit) {
    var scale by remember(item.id) { mutableFloatStateOf(1f) }
    var offset by remember(item.id) { mutableStateOf(Offset.Zero) }
    var failed by remember(item.id) { mutableStateOf(false) }
    val bitmap by produceState<Bitmap?>(null, item.id, active) {
        value = loader.load(item)
        if (active && !item.isVideo) {
            try {
                value = withContext(Dispatchers.IO) { ThumbnailLoader.decodeImage(File(item.localPath), 2560) }
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { failed = true }
        }
    }
    LaunchedEffect(active) { if (!active) { scale = 1f; offset = Offset.Zero } }
    LaunchedEffect(scale, active) { if (active) onZoom(scale > 1f) }
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val maxX = constraints.maxWidth.toFloat()
        val maxY = constraints.maxHeight.toFloat()
        if (bitmap != null) Image(
            bitmap!!.asImageBitmap(), item.name, contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize()
                .pointerInput(item.id) {
                    detectTapGestures(onDoubleTap = {
                        scale = if (scale > 1f) 1f else 2.5f
                        offset = Offset.Zero
                    })
                }
                .transformable(
                    state = rememberTransformableState { zoom, pan, _ ->
                        scale = (scale * zoom).coerceIn(1f, 4f)
                        val boundsX = maxX * (scale - 1) / 2
                        val boundsY = maxY * (scale - 1) / 2
                        offset = Offset((offset.x + pan.x).coerceIn(-boundsX, boundsX), (offset.y + pan.y).coerceIn(-boundsY, boundsY))
                    }, canPan = { scale > 1f },
                )
                .graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y },
        ) else if (!failed) CircularProgressIndicator(color = Color.White)
        if (failed) Text("This photo couldn’t be opened", color = Color.White, modifier = Modifier.align(Alignment.Center).background(Color.Black.copy(alpha = 0.7f)).padding(16.dp))
    }
}

@Composable
private fun VideoPlayer(item: GalleryItem) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var failed by remember(item.id) { mutableStateOf(false) }
    val player = remember(item.id) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.fromFile(File(item.localPath))))
            addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) { failed = true }
            })
            prepare()
            playWhenReady = false
        }
    }
    DisposableEffect(player, lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) player.pause() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); player.release() }
    }
    Box(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.Center) {
        AndroidView(factory = { PlayerView(it).apply { this.player = player } }, modifier = Modifier.fillMaxSize())
        if (failed) Text("This video couldn’t be played on this device", color = Color.White, modifier = Modifier.background(Color.Black).padding(20.dp))
    }
}

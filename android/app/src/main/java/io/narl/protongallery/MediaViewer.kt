package io.narl.protongallery

import android.graphics.Bitmap
import android.net.Uri
import android.text.format.Formatter
import android.view.View
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import io.narl.protongallery.bridge.GalleryItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** What the viewer offers for the photo on screen; `null` hides an action. */
class ViewerActions(
    val delete: ((GalleryItem) -> Unit)? = null,
    val backUp: ((GalleryItem) -> Unit)? = null,
    val addToAlbum: ((GalleryItem) -> Unit)? = null,
    val favorite: ((GalleryItem) -> Unit)? = null,
    /** Put into a hidden album; null where hiding does not apply. */
    val hide: ((GalleryItem) -> Unit)? = null,
)

/**
 * Swipes through the whole view: the outline the photo was opened from, with
 * records loading as pages come up. A tap shows or hides the bars, and with
 * them the system bars, so the photo can fill the screen.
 */
@Composable
fun MediaViewer(outline: GalleryOutline, initial: Int, thumbnails: ThumbnailLoader, media: MediaActions, onClose: () -> Unit,
    actions: ViewerActions = ViewerActions(), private: Boolean = false) {
    val pager = rememberPagerState(initialPage = initial.coerceIn(0, maxOf(0, outline.count - 1))) { outline.count }
    var zoomed by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf(false) }
    var more by remember { mutableStateOf(false) }
    var chrome by rememberSaveable { mutableStateOf(true) }
    var loading by remember { mutableStateOf(false) }
    val current = outline.item(pager.currentPage)
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        SystemBars(chrome)
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            HorizontalPager(state = pager, key = { outline.ids[it] }, userScrollEnabled = !zoomed, modifier = Modifier.fillMaxSize()) { index ->
                val item = outline.item(index) ?: return@HorizontalPager
                val active = index == pager.currentPage
                if (item.isVideo && active) VideoPlayer(item, media, private, onLoading = { loading = it }) { chrome = it }
                else ZoomablePhoto(item, thumbnails, media, active, private, onZoom = { zoomed = it }, onTap = { chrome = !chrome },
                    onLoading = { if (active) loading = it })
            }
            AnimatedVisibility(chrome, Modifier.align(Alignment.TopCenter), enter = fadeIn(), exit = fadeOut()) {
                Column(Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.7f)).statusBarsPadding()) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back to gallery", tint = Color.White) }
                        Text("${pager.currentPage + 1} / ${outline.count}", color = Color.White, style = MaterialTheme.typography.labelLarge,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = 8.dp))
                        if (current != null) {
                            actions.favorite?.let { favorite ->
                                IconButton(onClick = { favorite(current) }) {
                                    Icon(if (current.favorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                                        if (current.favorite) "Remove from favorites" else "Add to favorites", tint = Color.White)
                                }
                            }
                            IconButton(onClick = { media.share(listOf(current)) }) { Icon(Icons.Outlined.Share, "Share", tint = Color.White) }
                            val backUp = actions.backUp
                            val addToAlbum = actions.addToAlbum
                            if (backUp != null && current.phoneId != null && !current.backedUp) IconButton(onClick = { backUp(current) }) {
                                Icon(Icons.Outlined.CloudUpload, "Back up", tint = Color.White)
                            } else if (addToAlbum != null && current.inProton) IconButton(onClick = { addToAlbum(current) }) {
                                Icon(Icons.Outlined.LibraryAdd, "Add to album", tint = Color.White)
                            }
                            Box {
                                IconButton(onClick = { more = true }) { Icon(Icons.Outlined.MoreVert, "More options", tint = Color.White) }
                                DropdownMenu(more, onDismissRequest = { more = false }) {
                                    DropdownMenuItem(text = { Text(if (info) "Hide details" else "Details") }, leadingIcon = { Icon(Icons.Outlined.Info, null) },
                                        onClick = { more = false; info = !info })
                                    val hide = actions.hide
                                    if (hide != null && (current.inProton || current.phoneId != null)) DropdownMenuItem(text = { Text("Hide") },
                                        leadingIcon = { Icon(Icons.Outlined.VisibilityOff, null) }, onClick = { more = false; hide(current) })
                                    val delete = actions.delete
                                    if (delete != null && current.deletable) DropdownMenuItem(text = { Text("Delete") }, leadingIcon = { Icon(Icons.Outlined.Delete, null) },
                                        onClick = { more = false; delete(current) })
                                }
                            }
                        }
                    }
                    // The full-resolution photo replaces the preview when it is ready.
                    ActivityBar(if (loading) Activity("Loading the full photo") else null, color = Color.White)
                }
            }
            AnimatedVisibility(current != null && chrome && (!current.isVideo || info), Modifier.align(Alignment.BottomCenter), enter = fadeIn(), exit = fadeOut()) {
                val shown = current ?: return@AnimatedVisibility
                Column(Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.75f)).navigationBarsPadding().padding(24.dp)) {
                    Text(shown.name, color = Color.White, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (shown.backedUp) Icons.Outlined.OfflinePin else Icons.Outlined.CloudOff, null, Modifier.size(16.dp), tint = Color(0xFFBDA9FF))
                        Text(when {
                            shown.phoneId != null && shown.backedUp -> "On this phone and in Proton Photos"
                            shown.phoneId != null -> "Only on this phone, not backed up yet"
                            shown.localPath.isNotEmpty() -> "Available on this device"
                            else -> "In Proton Photos"
                        }, color = Color(0xFFE2D9F3), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 7.dp))
                    }
                    if (info) Text("${Formatter.formatFileSize(LocalContext.current, shown.sizeBytes.toLong())}\nAdded ${galleryDate(shown.sortTime)}", color = Color(0xFFE2D9F3), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 12.dp))
                }
            }
        }
    }
}

/** Hides the status and navigation bars with the viewer's own; a swipe from the edge shows them briefly. */
@Composable
private fun SystemBars(visible: Boolean) {
    val view = LocalView.current
    val window = (view.parent as? DialogWindowProvider)?.window ?: return
    DisposableEffect(window, visible) {
        val controller = WindowCompat.getInsetsController(window, view)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (visible) controller.show(WindowInsetsCompat.Type.systemBars()) else controller.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { }
    }
}

@Composable
private fun ZoomablePhoto(item: GalleryItem, loader: ThumbnailLoader, media: MediaActions, active: Boolean, private: Boolean, onZoom: (Boolean) -> Unit,
    onTap: () -> Unit, onLoading: (Boolean) -> Unit) {
    var scale by remember(item.id) { mutableFloatStateOf(1f) }
    var offset by remember(item.id) { mutableStateOf(Offset.Zero) }
    var failed by remember(item.id) { mutableStateOf(false) }
    val context = LocalContext.current
    val bitmap by produceState(loader.peek(item), item.id, active) {
        if (value == null) value = loader.load(item, private)
        val phone = PhoneLibrary.uri(item)
        if (active && !item.isVideo) {
            onLoading(true)
            try {
                val large = when {
                    phone != null -> withContext(Dispatchers.IO) { ThumbnailLoader.decodeUri(context, phone, 2560) }
                    item.localPath.isNotEmpty() -> withContext(Dispatchers.IO) { ThumbnailLoader.decodeImage(File(item.localPath), 2560) }
                    else -> media.largePhoto(item)
                }
                if (large != null) value = large
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { if (value == null) failed = true }
            finally { onLoading(false) }
        }
    }
    LaunchedEffect(active) { if (!active) { scale = 1f; offset = Offset.Zero } }
    LaunchedEffect(scale, active) { if (active) onZoom(scale > 1f) }
    BoxWithConstraints(Modifier.fillMaxSize().pointerInput(item.id) { detectTapGestures(onTap = { onTap() }) }, contentAlignment = Alignment.Center) {
        val maxX = constraints.maxWidth.toFloat()
        val maxY = constraints.maxHeight.toFloat()
        if (bitmap != null) Image(
            bitmap!!.asImageBitmap(), item.name, contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize()
                .pointerInput(item.id) {
                    detectTapGestures(onTap = { onTap() }, onDoubleTap = {
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
        )
        if (failed) Text("This photo couldn’t be opened", color = Color.White, modifier = Modifier.align(Alignment.Center).background(Color.Black.copy(alpha = 0.7f)).padding(16.dp))
    }
}

@Composable
@androidx.annotation.OptIn(UnstableApi::class)
private fun VideoPlayer(item: GalleryItem, media: MediaActions, private: Boolean, onLoading: (Boolean) -> Unit, onControls: (Boolean) -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var failed by remember(item.id) { mutableStateOf(false) }
    val player = remember(item.id) {
        ExoPlayer.Builder(context).build().apply {
            addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) { failed = true }
            })
            playWhenReady = false
        }
    }
    // Proton videos are downloaded and decrypted first; phone videos play in place.
    LaunchedEffect(player) {
        val source = PhoneLibrary.uri(item) ?: item.localPath.takeIf { it.isNotEmpty() }?.let { Uri.fromFile(File(it)) } ?: run {
            onLoading(true)
            try { media.original(item, private)?.let(Uri::fromFile) }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { null }
            finally { onLoading(false) }
        }
        if (source == null) { failed = true; return@LaunchedEffect }
        player.setMediaItem(MediaItem.fromUri(source))
        player.prepare()
    }
    DisposableEffect(player, lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) player.pause() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); player.release() }
    }
    Box(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.Center) {
        // The player's own controls and the viewer's bars come and go together.
        AndroidView(factory = { context ->
            PlayerView(context).apply {
                this.player = player
                setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { onControls(it == View.VISIBLE) })
            }
        }, modifier = Modifier.fillMaxSize())
        if (failed) Text("This video couldn’t be played", color = Color.White, modifier = Modifier.background(Color.Black).padding(20.dp))
    }
}

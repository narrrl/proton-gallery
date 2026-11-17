package io.narl.protongallery

import android.content.ClipData
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.paging.compose.LazyPagingItems
import io.narl.protongallery.bridge.AlbumOptions
import io.narl.protongallery.bridge.AlbumTag
import io.narl.protongallery.bridge.DriveFolder
import io.narl.protongallery.bridge.FolderAlbum
import io.narl.protongallery.bridge.GalleryItem
import io.narl.protongallery.icons.*
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.roundToInt

/** Drive folder chooser callbacks. */
data class ChooserActions(
    val open: () -> Unit = {}, val openFolder: (DriveFolder) -> Unit = {}, val back: () -> Unit = {},
    val retry: () -> Unit = {}, val add: (DriveFolder) -> Unit = {}, val close: () -> Unit = {},
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryApp(
    state: LibraryState, photos: LazyPagingItems<GalleryItem>, thumbnails: ThumbnailLoader,
    theme: ThemeMode, zoom: Float, onImport: () -> Unit, onTheme: (ThemeMode) -> Unit,
    onZoom: (Float) -> Unit, onRetry: () -> Unit, onDismissMessage: () -> Unit,
    location: GalleryLocation = GalleryLocation(), albums: List<FolderAlbum> = emptyList(),
    onNavigate: (GalleryLocation) -> Unit = {},
    account: AccountState = AccountState(), onAccount: () -> Unit = {}, onRefresh: () -> Unit = {},
    onCloseAccount: () -> Unit = {}, onSignIn: (String, String) -> Unit = { _, _ -> },
    onCode: (String) -> Unit = {}, onMailbox: (String) -> Unit = {},
    onVerification: (String) -> Unit = {}, onSignOut: () -> Unit = {},
    options: AlbumOptions? = null, onSaveOptions: (AlbumOptions) -> Unit = {}, onCover: (GalleryItem) -> Unit = {},
    tags: List<AlbumTag> = emptyList(), tagFilter: String? = null, onTag: (String?) -> Unit = {},
    chooser: ChooserState = ChooserState(), chooserActions: ChooserActions = ChooserActions(),
    onRemoveGallery: (FolderAlbum) -> Unit = {},
    onTestConnection: () -> Unit = {},
    crashReport: String? = null, onDismissCrash: () -> Unit = {},
) {
    var menu by rememberSaveable { mutableStateOf(false) }
    var editing by rememberSaveable { mutableStateOf(false) }
    // While true, tapping a photo makes it the album cover instead of opening it.
    var pickingCover by rememberSaveable(location.section, location.album?.id) { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Pair<List<GalleryItem>, Int>?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val grid = key(location.section, location.nativeView()) { rememberLazyListState() }
    val device = location.section == GallerySection.Device
    val drive = location.section == GallerySection.Drive
    val driveRoot = drive && location.album == null
    val refreshing = if (drive) account.driveSync != null else account.syncing
    LaunchedEffect(account.generation) { selected = null }
    LaunchedEffect(state.revision, state.ready) { if (state.ready) photos.refresh() }
    LaunchedEffect(state.message) {
        state.message?.let { snackbar.showSnackbar(it); onDismissMessage() }
    }
    BackHandler(location != GalleryLocation()) { onNavigate(location.back()) }

    // The bar slides away while scrolling down the grid and returns on the way up.
    val density = LocalDensity.current
    var barHeight by remember { mutableIntStateOf(0) }
    var barOffset by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(location) { barOffset = 0f }
    val hideOnScroll = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                barOffset = (barOffset + available.y).coerceIn(-barHeight.toFloat(), 0f)
                return Offset.Zero
            }
        }
    }
    val statusBar = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val top = statusBar + with(density) { barHeight.toDp() }
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    // Without a Scaffold nothing sets the content color, and bare Text falls
    // back to black, unreadable in dark mode. The Surface provides onBackground.
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background, contentColor = MaterialTheme.colorScheme.onBackground) {
    Box(Modifier.fillMaxSize().nestedScroll(hideOnScroll)) {
        val content: @Composable () -> Unit = {
            when {
                !state.ready -> EmptyLibrary(onRetry, !state.error, if (state.error) "Your library is unavailable" else "Opening your library",
                    if (state.error) "Your cached library is still stored on this device. Try again." else "", "Try again", top)
                device && state.count == 0L && !state.importing -> EmptyLibrary(onImport, false, "A place for every moment",
                    "Add photos and videos from your device. They stay here and are kept separate from Proton Photos.", "Choose photos", top)
                !device && state.count == 0L && albums.isEmpty() && tagFilter == null -> EmptyLibrary(
                    if (!account.connected) onAccount else if (driveRoot) chooserActions.open else onRefresh,
                    if (drive) account.driveSync != null else account.syncing,
                    if (!drive) "Your Proton Photos" else if (location.album == null) "Your Drive galleries" else "This album is empty",
                    if (!drive) if (account.connected) "Your backed-up photos and videos will appear here." else "Sign in to bring your backed-up photos and videos into one beautiful timeline."
                    else if (location.album == null) "Add a Drive folder as a gallery. Folders inside it become albums, with photos and videos together."
                    else "Photos, videos and albums from this folder appear here.",
                    if (!account.connected) "Sign in to Proton" else if (!drive) "Refresh" else if (driveRoot) "Choose a folder" else "", top)
                else -> SmartMediaGrid(photos, thumbnails, zoom, onZoom, grid, onOpen = { item ->
                    if (pickingCover) { pickingCover = false; onCover(item) }
                    else if (item.localPath.isEmpty()) scope.launch { snackbar.showSnackbar("This item isn’t available on this device yet.") }
                    else {
                        val window = photos.itemSnapshotList.items.toList()
                        selected = window to window.indexOfFirst { it.id == item.id }.coerceAtLeast(0)
                    }
                }, albums = albums, onAlbum = { onNavigate(location.child(it)) },
                    tags = if (driveRoot) tags else emptyList(), selectedTag = tagFilter, onTag = onTag,
                    albumsTitle = if (driveRoot) "Galleries" else "Albums",
                    header = location.album?.let { album -> @Composable { AlbumHeader(album, state.count, albums.size) } },
                    contentPadding = PaddingValues(top = top, bottom = bottom + 24.dp))
            }
        }
        if (account.connected && !device && state.ready) {
            val pull = rememberPullToRefreshState()
            PullToRefreshBox(refreshing, onRefresh, Modifier.fillMaxSize(), state = pull, indicator = {
                PullToRefreshDefaults.Indicator(pull, refreshing, Modifier.align(Alignment.TopCenter).padding(top = top))
            }) { content() }
        } else content()

        // Top bar, then a solid strip behind the status bar so content never shows through it.
        Surface(
            Modifier.fillMaxWidth().padding(top = statusBar).offset { IntOffset(0, barOffset.roundToInt()) }
                .onSizeChanged { barHeight = it.height },
            color = MaterialTheme.colorScheme.background.copy(alpha = 0.96f),
        ) {
            Column {
                when {
                    pickingCover -> PickingBar { pickingCover = false }
                    location.album != null || device -> Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { onNavigate(location.back()) }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
                        Text(location.title, Modifier.weight(1f).padding(start = 4.dp), style = MaterialTheme.typography.titleLarge,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (device && state.ready && !state.importing) IconButton(onClick = onImport) { Icon(Icons.Outlined.AddPhotoAlternate, "Add photos") }
                        if (location.album != null && options != null) IconButton(onClick = { editing = true }) { Icon(Icons.Outlined.Edit, "Edit album") }
                    }
                    else -> Row(Modifier.fillMaxWidth().height(64.dp).padding(start = 12.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        SectionSwitch(location.section) { onNavigate(GalleryLocation(it)) }
                        Spacer(Modifier.weight(1f))
                        if (driveRoot && account.connected) IconButton(onClick = chooserActions.open) { Icon(Icons.Outlined.Add, "Add gallery") }
                        AccountButton(account) { menu = true }
                    }
                }
                val busy = state.importing || (drive && account.driveSync != null) || (!drive && !device && account.syncing)
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
            }
        }
        Box(Modifier.fillMaxWidth().height(statusBar).background(MaterialTheme.colorScheme.background))
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = bottom))
    }
    }

    if (menu) AppMenu(state, account, theme, onTheme, onDismiss = { menu = false },
        onAccount = { menu = false; onAccount() }, onRefresh = { menu = false; onRefresh() },
        onDevice = { menu = false; onNavigate(GalleryLocation(GallerySection.Device)) })
    if (editing && options != null) AlbumOptionsSheet(options, location.album, tags, onSaveOptions,
        onChooseCover = { pickingCover = true }, onRemoveGallery = { location.album?.let(onRemoveGallery) }, onDismiss = { editing = false })
    if (chooser.visible) DriveFolderChooser(chooser, chooserActions.openFolder, chooserActions.back, chooserActions.retry, chooserActions.add, chooserActions.close)
    if (account.visible) AccountScreen(account, onCloseAccount, onSignIn, onCode, onMailbox, onVerification, onSignOut, onRefresh, onTestConnection)
    selected?.let { (items, initial) -> MediaViewer(items, initial, thumbnails, onClose = { selected = null }) }
    crashReport?.let { CrashReportDialog(it, onDismissCrash) }
}

/** Photos and Drive: the screen title and the way between them in one control. */
@Composable
private fun SectionSwitch(current: GallerySection, onSelect: (GallerySection) -> Unit) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(Modifier.padding(4.dp).selectableGroup()) {
            for ((section, label) in listOf(GallerySection.Photos to "Photos", GallerySection.Drive to "Drive")) {
                val chosen = current == section
                Row(
                    Modifier.clip(CircleShape)
                        .background(if (chosen) MaterialTheme.colorScheme.primary else Color.Transparent)
                        .selectable(chosen, role = Role.Tab) { onSelect(section) }
                        .heightIn(min = 40.dp).padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val tint = if (chosen) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                    Icon(if (section == GallerySection.Photos) Icons.Outlined.PhotoLibrary else Icons.Outlined.FolderOpen, null,
                        Modifier.size(18.dp), tint = tint)
                    Text(label, Modifier.padding(start = 8.dp), style = MaterialTheme.typography.labelLarge, color = tint)
                }
            }
        }
    }
}

/** The account initial, ringed while Proton is being read. */
@Composable
private fun AccountButton(account: AccountState, onClick: () -> Unit) {
    val syncing = account.syncing || account.driveSync != null
    Box(Modifier.size(48.dp).clip(CircleShape).clickable(onClickLabel = "Open account and settings", onClick = onClick)
        .semantics { contentDescription = "Account and settings"; if (syncing) stateDescription = "Updating" },
        contentAlignment = Alignment.Center) {
        if (syncing) CircularProgressIndicator(Modifier.size(40.dp), strokeWidth = 2.dp)
        Surface(Modifier.size(34.dp), shape = CircleShape,
            color = if (account.connected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh) {
            Box(contentAlignment = Alignment.Center) {
                val initial = account.status.username.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()
                if (account.connected && initial != null) Text(initial.toString(), style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer)
                else Icon(Icons.Outlined.Person, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun PickingBar(onStop: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(64.dp).background(MaterialTheme.colorScheme.primaryContainer).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onStop) { Icon(Icons.Outlined.Close, "Stop choosing a cover", tint = MaterialTheme.colorScheme.onPrimaryContainer) }
        Text("Tap a photo or video to make it the cover", Modifier.padding(start = 4.dp),
            style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
    }
}

/** Above an album's grid: what is in it and its description. Scrolls with the grid. */
@Composable
private fun AlbumHeader(album: FolderAlbum, items: Long, children: Int) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 4.dp, bottom = 12.dp)) {
        Text(albumCaption(album.copy(directItems = items.toULong(), childAlbums = children.toULong())),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        album.description?.let { Text(it, Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodyLarge) }
        if (album.tags.isNotEmpty()) Text(album.tags.joinToString(" · "), Modifier.padding(top = 6.dp),
            style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    }
}

/** Account, appearance and the device library, in one place. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppMenu(
    state: LibraryState, account: AccountState, theme: ThemeMode, onTheme: (ThemeMode) -> Unit,
    onDismiss: () -> Unit, onAccount: () -> Unit, onRefresh: () -> Unit, onDevice: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            ListItem(
                headlineContent = { Text(if (account.connected) account.status.username else "Not signed in", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingContent = { Text(syncSummary(account)) },
                leadingContent = { AccountButton(account, onAccount) },
                trailingContent = { if (account.connected) TextButton(onClick = onAccount) { Text("Manage") } else Button(onClick = onAccount) { Text("Sign in") } },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
            if (account.connected) MenuRow(Icons.Outlined.Refresh, "Refresh Proton Photos",
                if (account.syncing) "Updating now" else null, enabled = !account.syncing, onClick = onRefresh)
            MenuRow(Icons.Outlined.PhoneAndroid, "On this device",
                "Photos you add here stay on this phone", onClick = onDevice)
            HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
            Text("Theme", Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.titleSmall)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                ThemeMode.entries.forEachIndexed { index, mode ->
                    SegmentedButton(selected = theme == mode, onClick = { onTheme(mode) },
                        shape = SegmentedButtonDefaults.itemShape(index, ThemeMode.entries.size)) {
                        Text(if (mode == ThemeMode.System) "Device" else mode.name)
                    }
                }
            }
            Text("Pinch the grid to make photos larger or smaller.", Modifier.padding(horizontal = 16.dp).padding(top = 16.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun syncSummary(account: AccountState): String {
    val format = NumberFormat.getIntegerInstance()
    return when {
        !account.connected -> "Sign in to see Proton Photos and Drive"
        account.driveSync != null -> "Reading ${account.driveSync}" + if (account.driveIndexed > 0) ": ${format.format(account.driveIndexed)} found" else "…"
        account.syncing -> "Updating Proton Photos" + if (account.indexed > 0) ": ${format.format(account.indexed)} found" else "…"
        else -> "Proton Photos is up to date"
    }
}

@Composable
private fun MenuRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, detail: String?, enabled: Boolean = true, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = detail?.let { { Text(it) } },
        leadingContent = { Icon(icon, null) },
        trailingContent = { Icon(Icons.Outlined.ChevronRight, null) },
        modifier = Modifier.clickable(enabled = enabled, onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

/** Shown once after the app closed unexpectedly; the report can be copied. */
@Composable
private fun CrashReportDialog(report: String, onDismiss: () -> Unit) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Proton Gallery closed unexpectedly") },
        text = {
            Column {
                Text("Copy this report and send it to the developer. It has no photos or passwords.", style = MaterialTheme.typography.bodyMedium)
                Surface(Modifier.padding(top = 12.dp).heightIn(max = 280.dp), color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(12.dp)) {
                    SelectionContainer {
                        Text(report, Modifier.verticalScroll(rememberScrollState()).padding(12.dp),
                            style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("Crash report", report))) } }) { Text("Copy") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
fun MediaTile(item: GalleryItem, loader: ThumbnailLoader, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val bitmap by produceState<android.graphics.Bitmap?>(null, item.id, item.revision, loader) { value = loader.load(item) }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh).clickable(onClick = onClick)
        .semantics {
            contentDescription = "${if (item.isVideo) "Video" else "Photo"}: ${item.name}"
            stateDescription = if (bitmap != null) "Preview ready" else "Preview unavailable"
        }) {
        if (bitmap != null) Image(bitmap!!.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Icon(if (item.isVideo) Icons.Outlined.Videocam else Icons.Outlined.Image, null, Modifier.align(Alignment.Center).size(24.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
        if (item.isVideo) Icon(Icons.Outlined.PlayArrow, null, tint = Color.White,
            modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(20.dp))
    }
}

@Composable
private fun EmptyLibrary(onAction: () -> Unit, loading: Boolean, title: String, description: String, action: String, top: androidx.compose.ui.unit.Dp) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = top).padding(horizontal = 32.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        if (loading) CircularProgressIndicator(Modifier.padding(bottom = 24.dp)) else EmptyIllustration()
        Text(title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = 24.dp).widthIn(max = 480.dp), textAlign = TextAlign.Center)
        if (description.isNotEmpty()) Text(description, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 12.dp, bottom = 24.dp).widthIn(max = 480.dp), textAlign = TextAlign.Center)
        if (!loading && action.isNotEmpty()) Button(onClick = onAction, contentPadding = PaddingValues(horizontal = 24.dp, vertical = 14.dp)) {
            Icon(if (action == "Sign in to Proton") Icons.Outlined.Person else if (action == "Refresh") Icons.Outlined.Refresh else Icons.Outlined.AddPhotoAlternate,
                null, Modifier.size(20.dp)); Spacer(Modifier.width(10.dp)); Text(action)
        }
    }
}

@Composable
private fun EmptyIllustration() {
    val base = MaterialTheme.colorScheme.surfaceVariant
    val frame = MaterialTheme.colorScheme.primaryContainer
    val ink = MaterialTheme.colorScheme.primary
    Canvas(Modifier.size(188.dp, 140.dp)) {
        val w = size.width; val h = size.height
        rotate(-12f, Offset(w * 0.34f, h * 0.5f)) {
            drawRoundRect(base, Offset(w * 0.08f, h * 0.08f), Size(w * 0.53f, h * 0.78f), CornerRadius(14.dp.toPx()))
        }
        rotate(8f, Offset(w * 0.65f, h * 0.5f)) {
            drawRoundRect(frame, Offset(w * 0.35f, h * 0.14f), Size(w * 0.53f, h * 0.78f), CornerRadius(14.dp.toPx()))
            drawCircle(ink.copy(alpha = 0.75f), 12.dp.toPx(), Offset(w * 0.70f, h * 0.35f))
            val path = androidx.compose.ui.graphics.Path().apply {
                moveTo(w * 0.40f, h * 0.79f); lineTo(w * 0.55f, h * 0.48f); lineTo(w * 0.67f, h * 0.67f)
                lineTo(w * 0.75f, h * 0.55f); lineTo(w * 0.83f, h * 0.79f); close()
            }
            drawPath(path, ink.copy(alpha = 0.45f))
        }
    }
}

fun galleryDate(time: Long): String = Instant.ofEpochSecond(time).atZone(ZoneId.systemDefault()).toLocalDate()
    .format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))

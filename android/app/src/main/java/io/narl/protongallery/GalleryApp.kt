package io.narl.protongallery

import android.content.ClipData
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
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

/** Another app asked for media (GET_CONTENT or PICK). `mime` is the type it accepts, such as images only. */
data class PickRequest(val multiple: Boolean, val mime: String?) {
    fun accepts(item: GalleryItem): Boolean {
        val wanted = mime?.takeIf { it.isNotBlank() && it != "*/*" } ?: return true
        val type = Originals.mime(item)
        return if (wanted.endsWith("/*")) type.startsWith(wanted.removeSuffix("*")) else type == wanted
    }
}

/** What the viewer and selection can do with media beyond showing it. */
class MediaActions(
    val share: (List<GalleryItem>) -> Unit = {},
    val largePhoto: suspend (GalleryItem) -> android.graphics.Bitmap? = { null },
    /** The full file; `private` ones are deleted again when hidden albums lock. */
    val original: suspend (GalleryItem, Boolean) -> java.io.File? = { _, _ -> null },
    /** A Proton video to play while it downloads. */
    val stream: suspend (GalleryItem) -> io.narl.protongallery.bridge.VideoStream? = { null },
    /** Move to the trash, after the user confirmed. */
    val delete: (List<GalleryItem>) -> Unit = {},
    val favorite: (List<GalleryItem>, Boolean) -> Unit = { _, _ -> },
    /** Hidden albums locked: delete what was decrypted from them. */
    val forgetPrivate: () -> Unit = {},
)

/** Only Proton and phone media can be deleted; files saved in the app's own library cannot. */
val GalleryItem.deletable: Boolean get() = inProton || phoneId != null

/** The phone's lock, which guards hidden albums. Tests replace it. */
class PrivacyLock(
    val available: () -> Boolean = { false },
    val unlock: (onResult: (Boolean) -> Unit) -> Unit = { it(false) },
    val setUp: () -> Unit = {},
)

/** Drive folder chooser callbacks. */
data class ChooserActions(
    val open: () -> Unit = {}, val openFolder: (DriveFolder) -> Unit = {}, val back: () -> Unit = {},
    val retry: () -> Unit = {}, val add: (DriveFolder) -> Unit = {}, val close: () -> Unit = {},
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryApp(
    state: LibraryState, outline: GalleryOutline?, thumbnails: ThumbnailLoader,
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
    backup: BackupState = BackupState(), backupActions: BackupActions = BackupActions(),
    media: MediaActions = MediaActions(),
    pick: PickRequest? = null, onPick: (List<GalleryItem>) -> Unit = {}, onCancelPick: () -> Unit = {},
    albumActions: AlbumActions = AlbumActions(),
    privacy: PrivacyLock = PrivacyLock(),
) {
    var backupSheet by rememberSaveable { mutableStateOf(false) }
    // Selected timeline items by id, in the order they were chosen.
    var selection by remember(location) { mutableStateOf<Map<String, GalleryItem>>(emptyMap()) }
    val photosRoot = location == GalleryLocation()
    var menu by rememberSaveable { mutableStateOf(false) }
    var editing by rememberSaveable { mutableStateOf(false) }
    // While true, tapping a photo makes it the album cover instead of opening it.
    var pickingCover by rememberSaveable(location.section, location.album?.id) { mutableStateOf(false) }
    // The open viewer: the outline it swipes through and where it opened.
    var selected by remember { mutableStateOf<Pair<GalleryOutline, Int>?>(null) }
    // Items waiting for the user to confirm their deletion.
    var confirmDelete by remember { mutableStateOf<List<GalleryItem>?>(null) }
    // Items waiting for an album to go into, and the "New album" dialog.
    var addingToAlbum by remember { mutableStateOf<List<GalleryItem>?>(null) }
    // Items waiting for a hidden album to go into.
    var hiding by remember { mutableStateOf<List<GalleryItem>?>(null) }
    var naming by rememberSaveable { mutableStateOf(false) }
    // Hidden albums are shown only after the phone's lock confirms the user,
    // and lock again whenever the app leaves the screen. Never saved.
    var hiddenUnlocked by remember { mutableStateOf(false) }
    var lockMissing by remember { mutableStateOf(false) }
    val activityContext = LocalActivity.current
    // Locking also forgets what hidden albums left in memory and the cache.
    val lock = {
        hiddenUnlocked = false
        thumbnails.forgetPrivate()
        media.forgetPrivate()
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        // Turning the phone is not leaving the app.
        if (activityContext?.isChangingConfigurations != true) {
            lock()
            // Leaving the app inside a hidden album closes it too.
            if (location.private) { selected = null; onNavigate(GalleryLocation(location.section)) }
        }
    }
    // While hidden albums are open, nothing of the screen may leave the phone:
    // no screenshots, recordings, casting or recent-apps thumbnail.
    DisposableEffect(hiddenUnlocked, activityContext) {
        val window = activityContext?.window
        if (hiddenUnlocked) window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        if (android.os.Build.VERSION.SDK_INT >= 33) activityContext?.setRecentsScreenshotEnabled(!hiddenUnlocked)
        onDispose {
            window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
            if (android.os.Build.VERSION.SDK_INT >= 33) activityContext?.setRecentsScreenshotEnabled(true)
        }
    }
    val toggleHidden = {
        when {
            hiddenUnlocked -> lock()
            !privacy.available() -> lockMissing = true
            else -> privacy.unlock { hiddenUnlocked = it }
        }
    }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val grid = key(location.section, location.nativeView()) { rememberLazyListState() }
    val device = location.section == GallerySection.Device
    val drive = location.section == GallerySection.Drive
    val driveRoot = drive && location.album == null
    val albumsRoot = location.section == GallerySection.Albums && location.album == null
    val canAddToAlbum = (location.section == GallerySection.Photos || location.section == GallerySection.Albums) && account.connected
    val refreshing = if (drive) account.driveSync != null else account.syncing
    val activity = currentActivity(state, account, backup)
    LaunchedEffect(account.generation) { selected = null }
    LaunchedEffect(state.message) {
        state.message?.let { snackbar.showSnackbar(it); onDismissMessage() }
    }
    BackHandler(selection.isNotEmpty()) { selection = emptyMap() }
    BackHandler(location != GalleryLocation() && selection.isEmpty()) { onNavigate(location.back()) }

    // The bar slides away while scrolling down the grid and returns on the way up.
    val density = LocalDensity.current
    var barHeight by remember { mutableIntStateOf(0) }
    var barOffset by remember { mutableFloatStateOf(0f) }
    // While choosing, the bar holds the actions, so it stays in view.
    val barPinned = selection.isNotEmpty() || pick != null || pickingCover
    val currentPinned by rememberUpdatedState(barPinned)
    LaunchedEffect(location, barPinned) { if (barPinned || barOffset != 0f) barOffset = 0f }
    val hideOnScroll = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                barOffset = if (currentPinned) 0f else (barOffset + available.y).coerceIn(-barHeight.toFloat(), 0f)
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
        val album = location.album
        val gridHeader: (@Composable () -> Unit)? = when {
            album != null -> { { AlbumHeader(album, state.count, albums.size) } }
            photosRoot && (!backup.access || backup.partial) ->
                { { PhotosHeader(backup, backupActions.allowAccess) } }
            else -> null
        }
        val content: @Composable () -> Unit = {
            when {
                !state.ready -> EmptyLibrary(onRetry, !state.error, if (state.error) "Your library is unavailable" else "Opening your library",
                    if (state.error) "Your cached library is still stored on this device. Try again." else "", "Try again", top)
                device && state.count == 0L && !state.importing -> EmptyLibrary(onImport, false, "A place for every moment",
                    "Add photos and videos from your device. They stay here and are kept separate from Proton Photos.", "Choose photos", top)
                photosRoot && state.count == 0L && !backup.access -> EmptyLibrary(backupActions.allowAccess, false,
                    "Your photos, one timeline", "See the photos on this phone next to Proton Photos, and back them up end-to-end encrypted.",
                    "Show this phone’s photos", top)
                !device && !albumsRoot && state.count == 0L && albums.isEmpty() && tagFilter == null -> EmptyLibrary(
                    if (!account.connected) onAccount else if (driveRoot) chooserActions.open else onRefresh,
                    if (drive) account.driveSync != null else account.syncing,
                    if (!drive) "Your Proton Photos" else if (location.album == null) "Your Drive galleries" else "This album is empty",
                    if (!drive) if (account.connected) "Your backed-up photos and videos will appear here." else "Sign in to bring your backed-up photos and videos into one beautiful timeline."
                    else if (location.album == null) "Add a Drive folder as a gallery. Folders inside it become albums, with photos and videos together."
                    else "Photos, videos and albums from this folder appear here.",
                    if (!account.connected) "Sign in to Proton" else if (!drive) "Refresh" else if (driveRoot) "Choose a folder" else "", top)
                else -> SmartMediaGrid(outline, thumbnails, zoom, onZoom, grid, onOpen = { index, item ->
                    if (pickingCover) { pickingCover = false; onCover(item) }
                    else if (pick != null && !pick.accepts(item)) scope.launch { snackbar.showSnackbar("This app can’t take this kind of file") }
                    else if (pick != null && pick.multiple) selection = if (item.id in selection) selection - item.id else selection + (item.id to item)
                    else if (pick != null) onPick(listOf(item))
                    else outline?.let { selected = it to index }
                }, albums = albums, onAlbum = { onNavigate(location.child(it)) },
                    tags = if (driveRoot) tags else emptyList(), selectedTag = tagFilter, onTag = onTag,
                    albumsTitle = if (driveRoot) "Galleries" else "Albums",
                    hiddenUnlocked = hiddenUnlocked, onToggleHidden = toggleHidden,
                    onNewAlbum = if (albumsRoot && account.connected && pick == null) { { naming = true } } else null,
                    header = gridHeader,
                    contentPadding = PaddingValues(top = top, bottom = bottom + 24.dp),
                    selection = selection.keys,
                    onLongPress = if (!pickingCover && (pick == null || pick.multiple)) { item ->
                        if (pick == null || pick.accepts(item))
                            selection = if (item.id in selection) selection - item.id else selection + (item.id to item)
                    } else null,
                    private = location.private)
            }
        }
        if (account.connected && !device && state.ready) {
            val pull = rememberPullToRefreshState()
            // Pulling fills a bar under the top bar; the refresh then shows like all other work.
            PullToRefreshBox(refreshing, onRefresh, Modifier.fillMaxSize(), state = pull, indicator = {
                if (!refreshing && pull.distanceFraction > 0f) ActivityBar(Activity("Release to refresh", pull.distanceFraction.coerceAtMost(1f)),
                    Modifier.align(Alignment.TopCenter).padding(top = top))
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
                    pick != null -> PickBar(pick.multiple, selection.size, onCancelPick) { onPick(selection.values.toList()) }
                    selection.isNotEmpty() && pick == null -> SelectionBar(selection.values.toList(), onClose = { selection = emptyMap() },
                        onBackUp = { backupActions.upload(selection.values.toList()); selection = emptyMap() },
                        onShare = { media.share(selection.values.toList()); selection = emptyMap() },
                        onDelete = if (selection.values.any { it.deletable }) { { confirmDelete = selection.values.filter { it.deletable } } } else null,
                        onAddToAlbum = if (canAddToAlbum) {
                            { addingToAlbum = selection.values.toList(); albumActions.loadChoices() }
                        } else null,
                        onRemoveFromAlbum = if (location.protonAlbum) { { albumActions.remove(selection.values.toList()); selection = emptyMap() } } else null,
                        onHide = if (canAddToAlbum && !location.private) { { hiding = selection.values.toList(); albumActions.loadChoices() } } else null)
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
                        Text(activity?.label ?: "", Modifier.weight(1f).padding(horizontal = 8.dp), style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (driveRoot && account.connected) IconButton(onClick = chooserActions.open) { Icon(Icons.Outlined.Add, "Add gallery") }
                        AccountButton(account, activity) { menu = true }
                    }
                }
                ActivityBar(activity)
            }
        }
        Box(Modifier.fillMaxWidth().height(statusBar).background(MaterialTheme.colorScheme.background))
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = bottom))
    }
    }

    if (menu) AppMenu(state, account, activity, theme, onTheme, onDismiss = { menu = false },
        onAccount = { menu = false; onAccount() }, onRefresh = { menu = false; onRefresh() },
        backup = backup, onBackup = { menu = false; backupSheet = true })
    if (backupSheet) BackupSheet(backup, account.connected, backupActions, onSignIn = { backupSheet = false; onAccount() },
        onDismiss = { backupSheet = false }, thumbnails = thumbnails, hiddenUnlocked = hiddenUnlocked, albumChoices = albumActions.choices)
    if (editing && options != null) AlbumOptionsSheet(
        if (location.protonAlbum) options.copy(title = options.title ?: location.album?.name) else options,
        location.album, tags, onSaveOptions,
        onChooseCover = { pickingCover = true }, onRemoveGallery = { location.album?.let(onRemoveGallery) }, onDismiss = { editing = false },
        proton = location.protonAlbum, onDeleteAlbum = { location.album?.let(albumActions.delete) })
    // Hiding always asks which hidden album. Existing hidden albums are
    // listed only after the phone's lock confirms the user; with none yet,
    // the sheet only offers to make one.
    val hiddenChoices = albumActions.choices?.filter { it.hidden }
    LaunchedEffect(hiding, hiddenChoices) {
        if (hiding == null || hiddenChoices.isNullOrEmpty() || hiddenUnlocked) return@LaunchedEffect
        if (!privacy.available()) { lockMissing = true; hiding = null }
        else privacy.unlock { unlocked -> hiddenUnlocked = unlocked; if (!unlocked) hiding = null }
    }
    hiding?.let { items ->
        if (hiddenChoices != null && (hiddenUnlocked || hiddenChoices.isEmpty())) AlbumChooserSheet("Hide in",
            "Hidden on this phone: these leave the timeline and the photo picker. Other Proton apps still show them.",
            hiddenChoices, thumbnails, suggestion = if (hiddenChoices.isEmpty()) "Hidden" else "",
            onChoose = { album -> albumActions.hide(items, album, album.name); hiding = null; selection = emptyMap(); selected = null },
            onCreate = { name -> albumActions.hide(items, null, name); hiding = null; selection = emptyMap(); selected = null },
            onDismiss = { hiding = null })
    }
    addingToAlbum?.let { items ->
        // Hidden albums keep their names to themselves while locked.
        AlbumChooserSheet(items, albumActions.choices?.filter { hiddenUnlocked || !it.hidden }, thumbnails,
            onChoose = { album -> albumActions.add(album, items); addingToAlbum = null; selection = emptyMap() },
            onCreate = { name -> albumActions.create(name, items); addingToAlbum = null; selection = emptyMap() },
            onDismiss = { addingToAlbum = null })
    }
    if (lockMissing) AlertDialog(
        onDismissRequest = { lockMissing = false },
        icon = { Icon(Icons.Outlined.Lock, null) },
        title = { Text("Set up a screen lock") },
        text = { Text("Hidden albums open with your fingerprint, face or screen lock. This phone has none yet.") },
        confirmButton = { TextButton(onClick = { lockMissing = false; privacy.setUp() }) { Text("Set up") } },
        dismissButton = { TextButton(onClick = { lockMissing = false }) { Text("Not now") } },
    )
    if (naming) NewAlbumDialog(onCreate = { albumActions.create(it, emptyList()); naming = false }, onDismiss = { naming = false })
    if (chooser.visible) DriveFolderChooser(chooser, chooserActions.openFolder, chooserActions.back, chooserActions.retry, chooserActions.add, chooserActions.close)
    if (account.visible) AccountScreen(account, onCloseAccount, onSignIn, onCode, onMailbox, onVerification, onSignOut, onRefresh, onTestConnection)
    selected?.let { (items, initial) -> MediaViewer(items, initial, thumbnails, media, onClose = { selected = null },
        actions = ViewerActions(
            delete = { confirmDelete = listOf(it) },
            backUp = { backupActions.upload(listOf(it)) },
            addToAlbum = if (canAddToAlbum) { { addingToAlbum = listOf(it); albumActions.loadChoices() } } else null,
            favorite = { item -> media.favorite(listOf(item), !item.favorite) },
            hide = if (canAddToAlbum && !location.private) { { hiding = listOf(it); albumActions.loadChoices() } } else null,
        ), private = location.private) }
    confirmDelete?.let { items ->
        DeleteDialog(items, onConfirm = { media.delete(items); confirmDelete = null; selection = emptyMap(); selected = null },
            onDismiss = { confirmDelete = null })
    }
    crashReport?.let { CrashReportDialog(it, onDismissCrash) }
}

/** Deleting moves to the trash; say which trash, and warn about anything not backed up. */
@Composable
fun DeleteDialog(items: List<GalleryItem>, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val phone = items.count { it.phoneId != null }
    val proton = items.count { it.inProton }
    val unsaved = items.count { it.phoneId != null && !it.inProton && !it.backedUp }
    val what = if (items.size == 1) (if (items[0].isVideo) "this video" else "this photo") else "${items.size} items"
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.Delete, null) },
        title = { Text("Delete $what?") },
        text = {
            val subject = if (items.size == 1) "It moves" else "They move"
            Text(buildString {
                append(when {
                    phone > 0 && proton > 0 -> "$subject to the trash on this phone and in Proton Photos."
                    proton > 0 -> "$subject to the Proton trash, where you can restore ${if (items.size == 1) "it" else "them"} in Proton Drive."
                    else -> "$subject to this phone’s trash."
                })
                if (unsaved > 0) append(if (unsaved == 1) " 1 of them isn’t backed up." else " $unsaved of them aren’t backed up.")
            })
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Move to trash") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Photos, Albums and Drive: the screen title and the way between them in one
 * control. The open one is named; the others show their icon. */
@Composable
private fun SectionSwitch(current: GallerySection, onSelect: (GallerySection) -> Unit) {
    val sections = listOf(
        Triple(GallerySection.Photos, "Photos", Icons.Outlined.Image),
        Triple(GallerySection.Albums, "Albums", Icons.Outlined.PhotoLibrary),
        Triple(GallerySection.Drive, "Drive", Icons.Outlined.FolderOpen),
    )
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(Modifier.padding(4.dp).selectableGroup()) {
            for ((section, label, icon) in sections) {
                val chosen = current == section
                Row(
                    Modifier.clip(CircleShape)
                        .background(if (chosen) MaterialTheme.colorScheme.primary else Color.Transparent)
                        .selectable(chosen, role = Role.Tab) { onSelect(section) }
                        .semantics { contentDescription = label }
                        .heightIn(min = 40.dp).padding(horizontal = if (chosen) 16.dp else 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val tint = if (chosen) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                    Icon(icon, null, Modifier.size(18.dp), tint = tint)
                    if (chosen) Text(label, Modifier.padding(start = 8.dp), style = MaterialTheme.typography.labelLarge, color = tint)
                }
            }
        }
    }
}

/** The account initial, ringed while the app works; the ring fills when the share done is known. */
@Composable
private fun AccountButton(account: AccountState, activity: Activity?, onClick: () -> Unit) {
    Box(Modifier.size(48.dp).clip(CircleShape).clickable(onClickLabel = "Open account and settings", onClick = onClick)
        .semantics { contentDescription = "Account and settings"; if (activity != null) stateDescription = activity.label },
        contentAlignment = Alignment.Center) {
        val fraction = activity?.fraction
        if (fraction != null) CircularProgressIndicator({ fraction }, Modifier.size(40.dp), strokeWidth = 2.dp,
            trackColor = Color.Transparent, gapSize = 0.dp)
        else if (activity != null) CircularProgressIndicator(Modifier.size(40.dp), strokeWidth = 2.dp)
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

/** Account, backup and appearance, in one place. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppMenu(
    state: LibraryState, account: AccountState, activity: Activity?, theme: ThemeMode, onTheme: (ThemeMode) -> Unit,
    onDismiss: () -> Unit, onAccount: () -> Unit, onRefresh: () -> Unit,
    backup: BackupState, onBackup: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            ListItem(
                headlineContent = { Text(if (account.connected) account.status.username else "Not signed in", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingContent = { Text(activity?.label ?: syncSummary(account)) },
                leadingContent = { AccountButton(account, activity, onAccount) },
                trailingContent = { if (account.connected) TextButton(onClick = onAccount) { Text("Manage") } else Button(onClick = onAccount) { Text("Sign in") } },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
            if (account.connected) MenuRow(Icons.Outlined.Refresh, "Refresh Proton Photos",
                if (account.syncing) "Updating now" else null, enabled = !account.syncing, onClick = onRefresh)
            MenuRow(Icons.Outlined.CloudUpload, "Backup", backupSummary(backup), onClick = onBackup)
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

/** `selected` is null outside selection mode. */
@Composable
fun MediaTile(item: GalleryItem, loader: ThumbnailLoader, onClick: () -> Unit, modifier: Modifier = Modifier,
    selected: Boolean? = null, onLongClick: (() -> Unit)? = null, private: Boolean = false) {
    // A thumbnail already in memory shows in the first frame, without a placeholder.
    val bitmap by produceState(loader.peek(item), item.id, item.revision, private, loader) { value = loader.peek(item) ?: loader.load(item, private) }
    val phoneOnly = item.phoneId != null && !item.backedUp
    Box(modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh)
        .combinedClickable(onLongClickLabel = "Select", onLongClick = onLongClick, onClick = onClick)
        .semantics {
            contentDescription = "${if (item.isVideo) "Video" else "Photo"}: ${item.name}" + (if (item.favorite) ", favorite" else "") + if (phoneOnly) ", not backed up" else ""
            stateDescription = when (selected) { true -> "Selected"; false -> "Not selected"; null -> if (bitmap != null) "Preview ready" else "Preview unavailable" }
        }) {
        val inset = if (selected == true) 10.dp else 0.dp
        if (bitmap != null) Image(bitmap!!.asImageBitmap(), null, Modifier.fillMaxSize().padding(inset), contentScale = ContentScale.Crop)
        else Icon(if (item.isVideo) Icons.Outlined.Videocam else Icons.Outlined.Image, null, Modifier.align(Alignment.Center).size(24.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
        if (item.isVideo) Icon(Icons.Outlined.PlayArrow, null, tint = Color.White,
            modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(20.dp))
        if (item.favorite) Icon(Icons.Filled.Favorite, null, tint = Color.White,
            modifier = Modifier.align(Alignment.BottomStart).padding(5.dp).size(16.dp))
        // Like other phone galleries: a crossed-out cloud marks what isn't backed up.
        if (phoneOnly) Icon(Icons.Outlined.CloudOff, null, tint = Color.White,
            modifier = Modifier.align(Alignment.BottomEnd).padding(5.dp).background(Color.Black.copy(alpha = 0.35f), CircleShape).padding(3.dp).size(14.dp))
        if (selected != null) Box(Modifier.align(Alignment.TopStart).padding(6.dp).size(22.dp).clip(CircleShape)
            .background(if (selected) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = 0.25f))
            .border(2.dp, if (selected) MaterialTheme.colorScheme.primary else Color.White, CircleShape),
            contentAlignment = Alignment.Center) {
            if (selected) Icon(Icons.Filled.Check, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onPrimary)
        }
    }
}

@Composable
private fun EmptyLibrary(onAction: () -> Unit, loading: Boolean, title: String, description: String, action: String, top: androidx.compose.ui.unit.Dp) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = top).padding(horizontal = 32.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        EmptyIllustration()
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

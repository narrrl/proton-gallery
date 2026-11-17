package io.narl.protongallery

import android.Manifest
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Size
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.core.view.WindowCompat
import androidx.core.content.edit
import java.io.File
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashLog.install(this)
        enableEdgeToEdge()
        // Opened by another app to choose photos (GET_CONTENT or PICK).
        val pick = intent?.takeIf { it.action == Intent.ACTION_GET_CONTENT || it.action == Intent.ACTION_PICK }?.let {
            PickRequest(it.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false), it.type)
        }
        lifecycleScope.launch { Originals.trim(applicationContext) }
        setContent {
            val model: GalleryViewModel = viewModel()
            val state by model.state.collectAsStateWithLifecycle()
            val account by model.account.collectAsStateWithLifecycle()
            val location by model.location.collectAsStateWithLifecycle()
            val albums by model.albums.collectAsStateWithLifecycle()
            val options by model.options.collectAsStateWithLifecycle()
            val tags by model.tags.collectAsStateWithLifecycle()
            val tagFilter by model.tagFilter.collectAsStateWithLifecycle()
            val chooser by model.chooser.collectAsStateWithLifecycle()
            val order by model.order.collectAsStateWithLifecycle()
            val view = location.section to location.nativeView()
            val photos = remember(view, account.generation, order) { model.photosAt(location, account.generation, order) }.collectAsLazyPagingItems()
            val preferences = remember { getSharedPreferences("appearance", MODE_PRIVATE) }
            var theme by remember { mutableStateOf(ThemeMode.entries.firstOrNull { it.name == preferences.getString("theme", null) } ?: ThemeMode.System) }
            var zoom by remember { mutableFloatStateOf(preferences.getFloat("zoom", ZOOM_DEFAULT).coerceIn(ZOOM_MIN, ZOOM_MAX)) }
            // Shown once after an unexpected exit, then forgotten.
            var crashReport by remember { mutableStateOf<String?>(null) }
            LaunchedEffect(Unit) { crashReport = withContext(Dispatchers.IO) { CrashLog.takeReport(applicationContext) } }
            val dark = theme == ThemeMode.Dark || (theme == ThemeMode.System && isSystemInDarkTheme())
            SideEffect {
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
            val thumbnails = remember(account.generation) {
                ThumbnailLoader(File(cacheDir, "thumbnails"), model::remoteThumbnail, model::rememberShape) { item ->
                    PhoneLibrary.uri(item)?.let { contentResolver.loadThumbnail(it, Size(384, 384), null) }
                }
            }
            val backup by model.backup.collectAsStateWithLifecycle()
            val access = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { model.phoneAccessChanged() }
            // Backup progress is shown as a notification; asked for when backup is first used.
            val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
            val askNotifications = {
                if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                    notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { model.phoneAccessChanged() }
            val backupActions = remember {
                BackupActions(
                    allowAccess = {
                        // Once refused for good, only system settings can grant access.
                        if (PhoneLibrary.hasAccess(this) || PhoneLibrary.permissions.none { shouldShowRequestPermissionRationale(it) } &&
                            getSharedPreferences("backup", MODE_PRIVATE).getBoolean("asked-access", false))
                            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
                        else access.launch(PhoneLibrary.permissions)
                        getSharedPreferences("backup", MODE_PRIVATE).edit { putBoolean("asked-access", true) }
                    },
                    setFolder = { folder, on -> if (on) askNotifications(); model.setBackupFolder(folder, on) },
                    setWifiOnly = model::setWifiOnly, setChargingOnly = model::setChargingOnly,
                    backUpNow = { askNotifications(); model.backUpNow() },
                    upload = { askNotifications(); model.upload(it) },
                    setKeepDays = model::setKeepDays, freeUp = model::freeUp,
                )
            }
            val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(100)) { model.import(it) }
            // Deleting phone media needs the user's consent in a system dialog.
            var deleting by remember { mutableStateOf<UiRequest.Delete?>(null) }
            val delete = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
                deleting?.let { model.freedUp(result.resultCode == RESULT_OK, it.count, it.bytes) }
                deleting = null
            }
            var trashing by remember { mutableStateOf<UiRequest.Trash?>(null) }
            val trash = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
                trashing?.let { model.phoneTrashed(result.resultCode == RESULT_OK, it) }
                trashing = null
            }
            LaunchedEffect(model) {
                model.requests.collect { request ->
                    when (request) {
                        is UiRequest.Delete -> { deleting = request; delete.launch(IntentSenderRequest.Builder(request.sender).build()) }
                        is UiRequest.Trash -> { trashing = request; trash.launch(IntentSenderRequest.Builder(request.sender).build()) }
                        is UiRequest.Share -> runCatching { startActivity(request.intent) }
                        is UiRequest.Picked -> {
                            val result = Intent().addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            result.data = request.uris.first()
                            result.clipData = ClipData.newUri(contentResolver, "Photos", request.uris.first()).apply {
                                request.uris.drop(1).forEach { addItem(ClipData.Item(it)) }
                            }
                            setResult(RESULT_OK, result)
                            finish()
                        }
                    }
                }
            }
            val albumChoices by model.albumChoices.collectAsStateWithLifecycle()
            val media = remember { MediaActions(share = model::share, largePhoto = model::largePhoto, original = model::original, delete = model::delete) }
            GalleryTheme(theme) {
                GalleryApp(
                    state, photos, thumbnails, theme, zoom,
                    onImport = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) },
                    onTheme = { theme = it; preferences.edit { putString("theme", it.name) } },
                    onZoom = { zoom = it; preferences.edit { putFloat("zoom", it) } },
                    onRetry = model::open, onDismissMessage = model::dismissMessage,
                    location = location, albums = albums, onNavigate = model::navigate,
                    account = account, onAccount = model::showAccount, onRefresh = model::refresh,
                    onCloseAccount = model::closeAccount, onSignIn = model::signIn,
                    onCode = model::secondFactor, onMailbox = model::mailboxPassword,
                    onVerification = model::verification, onSignOut = model::signOut,
                    options = options, onSaveOptions = { model.saveOptions(it) }, onCover = model::setCover,
                    tags = tags, tagFilter = tagFilter, onTag = model::filterTag,
                    chooser = chooser, chooserActions = ChooserActions(model::openChooser, model::chooserOpen, model::chooserBack,
                        model::chooserRetry, model::addGallery, model::closeChooser),
                    onRemoveGallery = model::removeGallery, onTestConnection = model::testConnection,
                    crashReport = crashReport, onDismissCrash = { crashReport = null },
                    backup = backup, backupActions = backupActions, media = media,
                    pick = pick, onPick = model::pick, onCancelPick = { setResult(RESULT_CANCELED); finish() },
                    albumActions = AlbumActions(albumChoices, model::loadAlbumChoices, model::addToAlbum, model::createAlbum,
                        model::removeFromAlbum, model::deleteAlbum),
                )
            }
        }
    }
}

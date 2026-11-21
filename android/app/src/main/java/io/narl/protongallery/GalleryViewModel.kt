package io.narl.protongallery

import android.app.Application
import android.content.ClipData
import android.content.Intent
import android.content.IntentSender
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.provider.MediaStore
import android.text.format.Formatter
import io.narl.protongallery.bridge.FreeUpSummary
import io.narl.protongallery.bridge.VideoStream
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.io.File
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.narl.protongallery.bridge.GalleryItem
import io.narl.protongallery.bridge.BackupAlbumTarget
import io.narl.protongallery.bridge.FolderAlbum
import io.narl.protongallery.bridge.AlbumOptions
import io.narl.protongallery.bridge.AlbumTag
import io.narl.protongallery.bridge.DriveFolder
import io.narl.protongallery.bridge.PhoneFolder
import io.narl.protongallery.bridge.PhoneRef
import io.narl.protongallery.bridge.PublicDriveLink
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import io.narl.protongallery.bridge.LibraryView
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import io.narl.protongallery.bridge.GalleryException
import io.narl.protongallery.bridge.SignInStage
import kotlinx.coroutines.withContext

data class LibraryState(
    val ready: Boolean = false,
    val bytes: Long = 0,
    val importing: Boolean = false,
    val progress: String? = null,
    val message: String? = null,
    val error: Boolean = false,
    val revision: Int = 0,
    /** Work the user just asked for, such as getting photos ready to share. */
    val preparing: String? = null,
)

enum class GalleryLoad { Loading, Ready, Failed }

data class GalleryPage<T>(val location: GalleryLocation, val generation: Int, val value: T) {
    fun matches(location: GalleryLocation, generation: Int) =
        this.location == location && this.generation == generation
}

/** Something only the activity can do: system dialogs and other apps. */
sealed interface UiRequest {
    /** Ask the user to let the app delete these phone items. */
    data class Delete(val sender: IntentSender, val count: Int, val bytes: Long) : UiRequest
    /** Ask the user to let the app move phone items to the system trash; then trash `proton` too. */
    data class Trash(val sender: IntentSender, val proton: List<GalleryItem>, val count: Int) : UiRequest
    data class Share(val intent: Intent) : UiRequest
    /** Return chosen items to the app that asked for them. */
    data class Picked(val uris: List<Uri>, val mimes: List<String>) : UiRequest
    /** Ask the user to let the app change favorites in the phone's gallery. */
    data class Favorite(val sender: IntentSender) : UiRequest
}

/** The Drive folder chooser: the open folder, its subfolders, and the way back. */
data class ChooserState(
    val visible: Boolean = false,
    val trail: List<DriveFolder> = emptyList(),
    val folder: DriveFolder? = null,
    val children: List<DriveFolder> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
)

/** The phone library and its backup, as the screens show them. */
data class BackupState(
    /** The app may read the phone's photos. */
    val access: Boolean = true,
    /** Only photos the user picked are shared with the app. */
    val partial: Boolean = false,
    val folders: List<PhoneFolder> = emptyList(),
    val wifiOnly: Boolean = true,
    val chargingOnly: Boolean = false,
    val progress: BackupProgress = BackupProgress(),
    val request: BackupRequest? = null,
    /** Free up space keeps media taken in this many recent days. */
    val keepDays: Int = 90,
    /** What freeing up space would remove; null before it is known. */
    val freeable: FreeUpSummary? = null,
    /** Proton albums that backups go into, by folder (null: every backup). */
    val albums: List<BackupAlbumTarget> = emptyList(),
) {
    val automatic get() = folders.any { it.backup }
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class GalleryViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = MutableStateFlow<GalleryRepository?>(null)
    private val mutableState = MutableStateFlow(LibraryState())
    private var opening = false
    val state = mutableState.asStateFlow()
    // Last line of defence: an unexpected failure in a background task shows a
    // message instead of closing the app.
    private val guard = CoroutineExceptionHandler { _, error ->
        android.util.Log.e("ProtonGallery", "Background task failed: ${error.javaClass.simpleName}")
        CrashLog.note(getApplication(), error)
        mutableState.update { it.copy(message = "Something went wrong. Try again.", error = true) }
    }
    private val mutableAccount = MutableStateFlow(AccountState())
    val account = mutableAccount.asStateFlow()
    private val mutableSyncProgress = MutableStateFlow(SyncProgress())
    val syncProgress = mutableSyncProgress.asStateFlow()
    private var closingAccount = false
    private var authJob: Job? = null
    private var photosJob: Job? = null
    private var driveJob: Job? = null
    private var settingsJob: Job? = null
    private val mutableDriveLink = MutableStateFlow<DriveLinkState?>(null)
    val driveLink = mutableDriveLink.asStateFlow()
    private var driveLinkRequest = 0

    fun openDriveLink() = changeDriveLink { repo, album -> repo.publicDriveLink(album) }
    fun createDriveLink(password: String, expires: Long?) = changeDriveLink { repo, album ->
        repo.createDriveLink(album, password, expires)
    }
    fun revokeDriveLink() = changeDriveLink { repo, album -> repo.revokeDriveLink(album) }
    fun closeDriveLink() {
        driveLinkRequest++
        mutableDriveLink.value = null
    }

    private fun changeDriveLink(action: suspend (GalleryRepository, FolderAlbum) -> PublicDriveLink?) {
        val repo = repository.value ?: return
        val album = location.value.takeIf { it.section == GallerySection.Drive }?.album ?: return
        val current = mutableDriveLink.value
        if (!account.value.connected || current?.busy == true) return
        val request = ++driveLinkRequest
        mutableDriveLink.value = DriveLinkState(busy = true, link = current?.link, loaded = current?.loaded == true)
        viewModelScope.launch(guard) {
            try {
                val link = action(repo, album)
                if (request == driveLinkRequest) mutableDriveLink.value = DriveLinkState(link = link, loaded = true)
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) {
                val message = (error as? GalleryException.Operation)?.detail ?: "Couldn’t update the public link. Try again."
                if (request == driveLinkRequest) mutableDriveLink.value = DriveLinkState(link = current?.link,
                    loaded = current?.loaded == true, error = message)
            }
        }
    }

    fun showAccount() {
        mutableAccount.update { it.copy(visible = true, error = null) }
        val repo = repository.value ?: return
        viewModelScope.launch(guard) { mutableAccount.update { it.copy(status = repo.accountStatus()) } }
    }
    fun closeAccount() {
        if (closingAccount) return
        if (mutableAccount.value.connected) { mutableAccount.update { it.copy(visible = false) }; return }
        val repo = repository.value ?: return
        closingAccount = true
        // Native cancellation is explicit: cancelling a Kotlin coroutine cannot stop sync FFI.
        mutableAccount.update { it.copy(busy = true) }
        viewModelScope.launch(guard) {
            try {
                repo.cancelSignIn()
                authJob?.join()
                mutableAccount.update { it.copy(status = repo.accountStatus(), visible = false, error = null) }
            } finally { closingAccount = false; mutableAccount.update { it.copy(busy = false) } }
        }
    }
    fun signIn(username: String, password: String) = authenticate { it.signIn(username, password) }
    fun secondFactor(code: String) = authenticate { it.secondFactor(code) }
    fun mailboxPassword(password: String) = authenticate { it.mailboxPassword(password) }
    fun verification(token: String) = authenticate { it.verification(token) }
    private fun authenticate(action: suspend (GalleryRepository) -> Unit) {
        val repo = repository.value ?: return
        if (mutableAccount.value.busy) return
        mutableAccount.update { it.copy(busy = true, error = null) }
        authJob = viewModelScope.launch(guard) {
            try {
                action(repo)
                val status = repo.accountStatus()
                val visibility = status.stage == SignInStage.CONNECTED && repo.galleryVisibilityReady()
                mutableAccount.update { it.copy(status = status, visible = status.stage != SignInStage.CONNECTED,
                    visibilityReady = visibility, visibilityStale = false,
                    generation = it.generation + if (status.stage == SignInStage.CONNECTED) 1 else 0) }
                if (status.stage == SignInStage.CONNECTED) {
                    navigate(GalleryLocation())
                    mutableState.update { it.copy(revision = it.revision + 1) }
                    syncPhotos()
                    refreshPhone(scan = false)
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) {
                mutableAccount.update { it.copy(status = repo.accountStatus(), error = authMessage(error)) }
            } finally { mutableAccount.update { it.copy(busy = false) } }
        }
    }
    fun testConnection() {
        val repo = repository.value ?: return
        if (mutableAccount.value.checking) return
        mutableAccount.update { it.copy(checking = true, report = null) }
        viewModelScope.launch(guard) {
            val report = try { repo.connectionReport() }
            catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) { "The check couldn’t run: ${error.message}" }
            mutableAccount.update { it.copy(checking = false, report = report) }
        }
    }
    fun signOut() {
        val expired = account.value.sessionExpired
        val repo = repository.value ?: return
        if (mutableAccount.value.busy) return
        mutableAccount.update { it.copy(busy = true, error = null) }
        authJob = viewModelScope.launch(guard) {
            try {
                PhotosSync.stop(getApplication())
                Backup.stop(getApplication())
                repo.signOut()
                photosJob?.join(); driveJob?.join(); settingsJob?.join()
                // Previews and originals of this account must not outlive it.
                withContext(Dispatchers.IO) {
                    File(getApplication<Application>().cacheDir, "thumbnails").deleteRecursively()
                    File(getApplication<Application>().cacheDir, "picker").deleteRecursively()
                    Originals.directory(getApplication()).deleteRecursively()
                }
                mutableSyncProgress.value = SyncProgress()
                mutableAccount.update { AccountState(generation = it.generation + 1, visible = expired,
                    error = if (expired) "Your Proton session expired. Sign in again to sync your library." else null) }
                PhotoPicker.changed(getApplication())
                backupRequestJob?.cancel()
                mutableBackup.update { it.copy(request = null) }
                navigate(GalleryLocation())
                mutableState.update { it.copy(revision = it.revision + 1) }
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) { mutableAccount.update { it.copy(error = authMessage(error)) } }
            finally { mutableAccount.update { it.copy(busy = false) } }
        }
    }
    fun syncPhotos() = syncPhotos(repair = false)
    fun repairPhotos() = syncPhotos(repair = true)

    private fun syncPhotos(repair: Boolean) {
        if (!account.value.connected || account.value.sessionExpired || photosJob?.isActive == true) return
        val app = getApplication<Application>()
        refreshGallerySettings()
        photosJob = viewModelScope.launch(guard) {
            PhotosSync.schedule(app)
            var requestRepair = repair
            do {
                val result = PhotosSync.check(app, requestRepair)
                requestRepair = false
                if (result == PhotosSync.Outcome.Retry) PhotosSync.retry(app)
            } while (result == PhotosSync.Outcome.More && account.value.connected && !account.value.busy)
        }
    }

    fun refreshGallerySettings() {
        val repo = repository.value ?: return
        if (!account.value.connected || account.value.sessionExpired || settingsJob?.isActive == true) return
        settingsJob = viewModelScope.launch(guard) {
            try { syncGallerySettings(repo, poll = account.value.visibilityReady) }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { /* Keep the last checked view while offline. */ }
        }
    }

    private suspend fun syncGallerySettings(repo: GalleryRepository, poll: Boolean = false) {
        val generation = account.value.generation
        val changed = try { if (poll) repo.pollGallerySettings() else repo.syncGallerySettings() }
        catch (cancel: CancellationException) { throw cancel }
        catch (error: Exception) {
            if (account.value.generation == generation) mutableAccount.update { it.copy(visibilityStale = true) }
            throw error
        }
        if (account.value.generation != generation) return
        if (changed == null) {
            mutableAccount.update { it.copy(visibilityStale = false) }
            return
        }
        mutableAccount.update { it.copy(visibilityReady = true, visibilityStale = false,
            visibilityRevision = it.visibilityRevision + if (changed) 1 else 0) }
        if (changed) {
            navigate(GalleryLocation(location.value.section))
            withContext(Dispatchers.IO) {
                val app = getApplication<Application>()
                File(app.cacheDir, "thumbnails").deleteRecursively()
                File(app.cacheDir, "picker").deleteRecursively()
                Originals.directory(app).deleteRecursively()
            }
        }
        PhotoPicker.changed(getApplication())
        refreshDetails(repo)
        mutableState.update { it.copy(revision = it.revision + 1) }
    }

    /** Refresh what is on screen: a Drive gallery, every gallery, or Photos. */
    fun refresh() {
        val place = location.value
        if (place.section != GallerySection.Drive || !account.value.visibilityReady) return syncPhotos()
        val album = place.album
        if (album != null) runDrive(album.name, "Couldn’t refresh this gallery. Your cached albums are available.") {
            syncGallerySettings(it)
            it.refreshDriveGallery(album.gallery)
        }
        else runDrive("your galleries", "Couldn’t refresh every gallery. Your cached albums are available.") { repo ->
            syncGallerySettings(repo)
            for (gallery in repo.albums(GalleryLocation(GallerySection.Drive))) repo.refreshDriveGallery(gallery.gallery)
        }
    }

    /** Add or refresh Drive galleries; runs alongside a Photos sync. */
    private fun runDrive(label: String, failure: String, after: suspend (GalleryRepository) -> Unit = {}, action: suspend (GalleryRepository) -> Unit) {
        val repo = repository.value ?: return
        if (!mutableAccount.value.connected || driveJob?.isActive == true) return
        mutableSyncProgress.update { it.copy(driveIndexed = 0) }
        mutableAccount.update { it.copy(driveSync = label) }
        driveJob = viewModelScope.launch(guard) {
            val progress = watchProgress(repo, { it.driveProgress().toLong() }) { indexed -> mutableSyncProgress.update { it.copy(driveIndexed = indexed) } }
            try {
                action(repo)
                refreshDetails(repo)
                after(repo)
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) { report(error, failure) }
            finally {
                progress.cancel()
                mutableAccount.update { it.copy(driveSync = null) }
                settle(repo)
            }
        }
    }

    /** Polls progress and reveals new media while a sync runs. Never throws:
     * a failed poll must not take the sync, or the app, down with it. */
    private fun CoroutineScope.watchProgress(repo: GalleryRepository, read: suspend (GalleryRepository) -> Long, show: (Long) -> Unit) = launch {
        var previous = 0L
        while (isActive) {
            delay(750)
            try {
                val indexed = read(repo)
                show(indexed)
                if (indexed != previous) {
                    previous = indexed
                    refreshDetails(repo)
                    mutableState.update { it.copy(revision = it.revision + 1) }
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) {}
        }
    }

    private fun report(error: Exception, failure: String) {
        if (mutableAccount.value.connected && !mutableAccount.value.busy) mutableState.update { it.copy(
            message = (error as? GalleryException.Operation)?.detail?.takeIf { it.endsWith("try again.") } ?: failure, error = true) }
    }

    private suspend fun settle(repo: GalleryRepository) {
        try {
            val status = repo.accountStatus()
            mutableAccount.update { it.copy(status = status) }
            refreshDetails(repo)
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) {}
        mutableState.update { it.copy(revision = it.revision + 1) }
    }

    // Drive folder chooser: pick a folder; every folder below it becomes an album.
    private val mutableChooser = MutableStateFlow(ChooserState())
    val chooser = mutableChooser.asStateFlow()
    private var chooserJob: Job? = null
    fun openChooser() {
        if (!mutableAccount.value.connected) return showAccount()
        mutableChooser.value = ChooserState(visible = true)
        loadChooser(null, emptyList())
    }
    fun chooserOpen(folder: DriveFolder) {
        val current = mutableChooser.value.folder ?: return
        loadChooser(folder, mutableChooser.value.trail + current)
    }
    fun chooserBack() {
        val trail = mutableChooser.value.trail
        if (trail.isEmpty()) return closeChooser()
        loadChooser(trail.last(), trail.dropLast(1))
    }
    fun chooserRetry() = loadChooser(mutableChooser.value.folder?.takeIf { mutableChooser.value.trail.isNotEmpty() }, mutableChooser.value.trail)
    fun closeChooser() { chooserJob?.cancel(); mutableChooser.value = ChooserState() }
    private fun loadChooser(folder: DriveFolder?, trail: List<DriveFolder>) {
        val repo = repository.value ?: return
        chooserJob?.cancel()
        mutableChooser.update { it.copy(trail = trail, folder = folder, children = emptyList(), loading = true, error = null) }
        chooserJob = viewModelScope.launch(guard) {
            try {
                val listing = repo.driveFolders(folder?.id)
                mutableChooser.update { if (it.visible) it.copy(folder = listing.folder, children = listing.children, loading = false) else it }
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) {
                mutableChooser.update { it.copy(loading = false, error = (error as? GalleryException.Operation)?.detail ?: "This folder couldn’t be opened. Try again.") }
            }
        }
    }
    fun addGallery(folder: DriveFolder) {
        closeChooser()
        runDrive(folder.name, "“${folder.name}” couldn’t be added. Check your connection and try again.", after = {
            if (location.value.section == GallerySection.Drive && location.value.album == null) navigate(location.value)
            refreshGallerySettings()
            mutableState.update { it.copy(message = "“${folder.name}” added. Its folders are now albums.", error = false) }
        }) { it.addDriveGallery(folder.id) }
    }
    fun removeGallery(album: FolderAlbum) {
        val repo = repository.value ?: return
        viewModelScope.launch(guard) {
            try {
                repo.removeDriveGallery(album.gallery)
                navigate(GalleryLocation(GallerySection.Drive))
                refreshGallerySettings()
                mutableState.update { it.copy(message = "Gallery removed. Nothing was deleted from Drive.") }
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { mutableState.update { it.copy(message = "The gallery couldn’t be removed. Try again.", error = true) } }
        }
    }

    // Proton Photos albums to choose from when adding photos; loaded when asked.
    private val mutableAlbumChoices = MutableStateFlow<List<FolderAlbum>?>(null)
    val albumChoices = mutableAlbumChoices.asStateFlow()

    fun loadAlbumChoices() {
        val repo = repository.value ?: return
        viewModelScope.launch(guard) {
            mutableAlbumChoices.value = runCatching { repo.photoAlbums() }.getOrDefault(emptyList())
        }
    }

    /** Proton photos can go into albums; phone photos once they are backed up. */
    private fun albumReady(items: List<GalleryItem>) = items.filter { it.inProton }

    private fun albumWork(busy: String, failure: String, work: suspend (GalleryRepository) -> String?) {
        val repo = repository.value ?: return
        if (!mutableAccount.value.connected) return showAccount()
        viewModelScope.launch(guard) {
            mutableState.update { it.copy(preparing = busy) }
            try {
                val message = work(repo)
                refreshDetails(repo)
                refreshGallerySettings()
                mutableAlbumChoices.value = null
                mutableState.update { it.copy(revision = it.revision + 1, message = message ?: it.message, error = false) }
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) {
                android.util.Log.w("ProtonGallery", "$failure ${error.javaClass.simpleName}")
                mutableState.update { it.copy(message = (error as? GalleryException.Operation)?.detail ?: failure, error = true) }
            } finally { mutableState.update { it.copy(preparing = null) } }
        }
    }

    private fun addedMessage(added: Int, failed: Int, skipped: Int, album: String): String = buildString {
        append(if (added == 1) "Added 1 to “$album”" else "Added $added to “$album”")
        if (failed > 0) append(". $failed couldn’t be added")
        if (skipped > 0) append(if (skipped == 1) ". 1 isn’t backed up yet" else ". $skipped aren’t backed up yet")
    }

    fun addToAlbum(album: FolderAlbum, items: List<GalleryItem>) {
        val ready = albumReady(items)
        if (ready.isEmpty()) {
            mutableState.update { it.copy(message = "Back these up first, then add them to an album.", error = false) }
            return
        }
        albumWork("Adding to “${album.name}”", "Couldn’t add to the album. Try again.") { repo ->
            val change = repo.addToAlbum(album, ready)
            addedMessage(change.changed.toInt(), change.failed.toInt(), items.size - ready.size, album.name)
        }
    }

    /** Create an album, with `items` in it when any are given. */
    fun createAlbum(name: String, items: List<GalleryItem>) {
        val ready = albumReady(items)
        albumWork("Creating “${name.trim()}”", "Couldn’t create the album. Try again.") { repo ->
            val album = repo.createAlbum(name)
            if (ready.isEmpty()) "Created “${album.name}”" else {
                val change = repo.addToAlbum(album, ready)
                addedMessage(change.changed.toInt(), change.failed.toInt(), items.size - ready.size, album.name)
            }
        }
    }

    fun removeFromAlbum(items: List<GalleryItem>) {
        val album = location.value.album?.takeIf { location.value.protonAlbum } ?: return
        albumWork("Removing from “${album.name}”", "Couldn’t remove from the album. Try again.") { repo ->
            val change = repo.removeFromAlbum(album, items)
            buildString {
                append(if (change.changed == 1uL) "Removed 1 from the album" else "Removed ${change.changed} from the album")
                if (change.failed > 0uL) append(". ${change.failed} couldn’t be removed")
            }
        }
    }

    fun deleteAlbum(album: FolderAlbum) {
        albumWork("Deleting “${album.name}”", "Couldn’t delete the album. Try again.") { repo ->
            repo.deleteAlbum(album)
            navigate(GalleryLocation(GallerySection.Albums))
            "Deleted “${album.name}”. Its photos are still in your timeline."
        }
    }

    // Presentation options of the open album or timeline.
    private val mutableOptions = MutableStateFlow<AlbumOptions?>(null)
    val options = mutableOptions.asStateFlow()
    private val mutableTags = MutableStateFlow<List<AlbumTag>>(emptyList())
    val tags = mutableTags.asStateFlow()
    private val mutableTagFilter = MutableStateFlow<String?>(null)
    val tagFilter = mutableTagFilter.asStateFlow()
    // Changes when the open view's order flips; starts a new paging session.
    private val mutableOrder = MutableStateFlow(0)
    val order = mutableOrder.asStateFlow()

    fun filterTag(tag: String?) {
        mutableTagFilter.value = tag
        val repo = repository.value ?: return
        viewModelScope.launch(guard) { try { refreshDetails(repo) } catch (cancel: CancellationException) { throw cancel } catch (_: Exception) {} }
    }

    fun saveOptions(options: AlbumOptions, confirmation: String? = null) {
        val repo = repository.value ?: return
        val place = location.value
        val before = mutableOptions.value
        viewModelScope.launch(guard) {
            try {
                // A Proton album's name lives in Proton; only presentation stays here.
                val album = place.album
                val local = if (place.protonAlbum && album != null) {
                    val name = options.title?.trim()
                    if (!name.isNullOrEmpty() && name != album.folderName) repo.renameAlbum(album, name)
                    options.copy(title = null)
                } else options
                val saved = repo.setOptions(place, local)?.let { saved ->
                    if (place.protonAlbum) saved.copy(title = options.title?.trim()?.ifEmpty { null } ?: album?.folderName) else saved
                } ?: return@launch
                if (location.value != place) return@launch
                mutableOptions.value = saved
                if (before?.order != saved.order) mutableOrder.update { it + 1 }
                place.album?.let { album ->
                    val name = saved.title ?: album.folderName.ifEmpty { album.name }
                    mutableLocation.value = place.renamed(album.copy(name = name, folderName = if (place.protonAlbum) name else album.folderName,
                        description = saved.description, tags = saved.tags, pinned = saved.pinned, hidden = saved.hidden))
                }
                mutableTags.value = repo.albumTags()
                refreshGallerySettings()
                if (confirmation != null) mutableState.update { it.copy(message = confirmation, error = false) }
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) {
                mutableState.update { it.copy(message = (error as? GalleryException.Operation)?.detail ?: "Album settings couldn’t be saved. Try again.", error = true) }
            }
        }
    }
    fun setCover(target: GalleryLocation, item: GalleryItem) {
        val repo = repository.value ?: return
        val generation = account.value.generation
        if (item.assetKey.isEmpty()) return
        viewModelScope.launch(guard) {
            try {
                val current = repo.options(target) ?: return@launch
                val saved = repo.setOptions(target, current.copy(coverKey = item.assetKey)) ?: return@launch
                if (account.value.generation != generation) return@launch
                if (location.value == target) mutableOptions.value = saved
                refreshDetails(repo)
                refreshGallerySettings()
                mutableState.update { it.copy(revision = it.revision + 1, message = "Cover updated", error = false) }
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) {
                mutableState.update { it.copy(message = (error as? GalleryException.Operation)?.detail ?: "The cover couldn’t be saved. Try again.", error = true) }
            }
        }
    }

    suspend fun remoteThumbnail(item: GalleryItem) = repository.value?.thumbnail(item)
    suspend fun rememberShape(item: GalleryItem, width: Int, height: Int) { repository.value?.rememberShape(item, width, height) }
    private fun authMessage(error: Exception): String = (error as? GalleryException.Operation)?.detail
        ?: "The account request couldn’t be completed. Try again."
    private val mutableLocation = MutableStateFlow(GalleryLocation())
    val location = mutableLocation.asStateFlow()
    private val mutableAlbums = MutableStateFlow<List<FolderAlbum>>(emptyList())
    val albums = mutableAlbums.asStateFlow()
    private val mutableDetails = MutableStateFlow<GalleryPage<GalleryLoad>?>(null)
    val details = mutableDetails.asStateFlow()
    private val mutableOutline = MutableStateFlow<GalleryPage<GalleryOutline?>?>(null)
    /** A null page is loading. A page with a null outline failed its first read. */
    val outline = mutableOutline.asStateFlow()

    /** Keep the outline in step with the open view. A different view, account
     * or order starts over; within the same view a new outline replaces the
     * old one only when items were added or removed, so the layout holds still
     * otherwise and only the records are read again. */
    private fun followOutline() = viewModelScope.launch(guard) {
        var photos: GalleryPage<GalleryOutline?>? = null
        var photosRevision: Int? = null
        var shownOrder = 0
        combine(repository, location, account, order, state) { repo, place, account, order, state ->
            Triple(repo, GalleryPage(place, account.generation, order), state.revision)
        }.distinctUntilChanged().collectLatest { (repo, page, revision) ->
            val (place, generation, order) = page
            val view = place.nativeView()
            if (photos?.generation != generation || shownOrder != order || photosRevision != revision) photos = null
            val current = mutableOutline.value?.takeIf {
                it.matches(place, generation) && shownOrder == order
            }?.value ?: photos?.takeIf { it.matches(place, generation) }?.value
            shownOrder = order
            mutableOutline.value = current?.let { GalleryPage(place, generation, it) }
            if (repo == null || view == null) return@collectLatest
            val next = try { repo.outline(view, viewModelScope, place.private) }
                catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) {
                    if (current == null) mutableOutline.value = GalleryPage(place, generation, null)
                    return@collectLatest
                }
            if (current != null && current.ids == next.ids) {
                current.reload()
                if (place.section == GallerySection.Photos) {
                    photos = mutableOutline.value
                    photosRevision = revision
                }
                return@collectLatest
            }
            val loaded = GalleryPage<GalleryOutline?>(place, generation, next)
            mutableOutline.value = loaded
            // Publish the new IDs immediately; loading thumbnail records must
            // not keep removed media on the screen or in the viewer.
            val anchor = if (current == null) 0 else {
                current.ids.getOrNull(current.lastIndex)?.let(next::indexOf)?.takeIf { it >= 0 }
                    ?: current.lastIndex.coerceAtMost(next.count - 1)
            }
            if (anchor in next.ids.indices) next.prefetch(anchor..anchor)
            if (place.section == GallerySection.Photos) {
                photos = loaded
                photosRevision = revision
            }
        }
    }

    suspend fun scanPlaces(update: (PlacesProgress) -> Unit) {
        val repo = repository.value ?: return
        placesOutlineLock.withLock { placesOutline = null }
        repo.placesScan().use { scan ->
            val total = scan.count().toInt()
            var next = 0u
            var failed = 0
            update(PlacesProgress(total = total, scanning = total > 0))
            while (next.toInt() < total) {
                val batch = repo.placesBatch(scan, next)
                next = batch.next
                failed += batch.failed.toInt()
                update(PlacesProgress(batch.photos, next.toInt(), total, failed, next.toInt() < total))
            }
        }
    }

    // The timeline Places reads located photos from; made once per scan.
    private val placesOutlineLock = kotlinx.coroutines.sync.Mutex()
    private var placesOutline: GalleryOutline? = null

    /** The timeline record of a located photo, for its thumbnail. */
    suspend fun placeItem(id: String): GalleryItem? {
        val repo = repository.value ?: return null
        val outline = placesOutlineLock.withLock {
            placesOutline ?: repo.outline(io.narl.protongallery.bridge.LibraryView.Photos, viewModelScope, false).also { placesOutline = it }
        }
        val index = outline.indexOf(id)
        return if (index >= 0) outline.fetchItem(index) else null
    }

    suspend fun placePhoto(id: String): Pair<GalleryOutline, Int>? {
        val repo = repository.value ?: return null
        val fresh = repo.outline(io.narl.protongallery.bridge.LibraryView.Photos, viewModelScope, false)
        val index = fresh.indexOf(id)
        return if (index >= 0) fresh to index else null
    }

    private var navigationJob: Job? = null

    fun navigate(place: GalleryLocation) {
        closeDriveLink()
        navigationJob?.cancel()
        val generation = account.value.generation
        val samePlace = location.value == place
        mutableLocation.value = place
        if (!samePlace) mutableAlbums.value = emptyList()
        mutableOptions.value = null
        if (place.section != GallerySection.Drive || place.album != null) mutableTagFilter.value = null
        if (!samePlace || mutableDetails.value?.value == GalleryLoad.Failed) mutableDetails.value = null
        val repo = repository.value ?: return
        if (samePlace) mutableState.update { it.copy(revision = it.revision + 1) }
        navigationJob = viewModelScope.launch(guard) {
            try { refreshDetails(repo, place) }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) {
                if (location.value == place && account.value.generation == generation) {
                    mutableDetails.value = GalleryPage(place, generation, GalleryLoad.Failed)
                }
            }
        }
    }

    private suspend fun refreshDetails(repo: GalleryRepository, place: GalleryLocation = location.value) {
        val generation = account.value.generation
        val driveRoot = place.section == GallerySection.Drive && place.album == null
        val tag = mutableTagFilter.value.takeIf { driveRoot }
        val children = if (tag != null) repo.taggedAlbums(tag) else repo.albums(place)
        val options = repo.options(place)
        val tags = if (place.section == GallerySection.Drive) repo.albumTags() else emptyList()
        if (location.value == place && account.value.generation == generation &&
            (!driveRoot || mutableTagFilter.value == tag)) {
            mutableAlbums.value = children
            mutableOptions.value = options
            mutableTags.value = tags
            mutableDetails.value = GalleryPage(place, generation, GalleryLoad.Ready)
            if (driveRoot && tag != null && tags.none { it.name.equals(tag, ignoreCase = true) }) mutableTagFilter.value = null
        }
    }

    fun open() {
        if (repository.value != null || opening) return
        opening = true
        mutableState.update { it.copy(error = false, message = null) }
        viewModelScope.launch(guard) {
            try {
                val repo = GalleryRepository.get(getApplication())
                val totals = repo.totals()
                repository.value = repo
                mutableState.update { it.copy(ready = true, bytes = totals.bytes.toLong(), message = null, error = false) }
                try {
                    repo.ensureSession()
                    val status = repo.accountStatus()
                    val ready = status.stage == SignInStage.CONNECTED && repo.galleryVisibilityReady()
                    mutableAccount.update { it.copy(status = status, visibilityReady = ready, generation = it.generation + 1) }
                } catch (error: Exception) {
                    mutableAccount.update { it.copy(error = authMessage(error), visible = true) }
                }
                if (mutableAccount.value.connected) syncPhotos()
                refreshPhone()
                Backup.schedule(getApplication())
                if (!mutableAccount.value.connected || mutableAccount.value.visibilityReady) refreshDetails(repo)
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) {
                mutableDetails.value = GalleryPage(location.value, account.value.generation, GalleryLoad.Failed)
                mutableState.update { it.copy(message = "Your library couldn’t be opened. Try again.", error = true) }
            } finally { opening = false }
        }
    }

    fun import(uris: List<Uri>) {
        val repo = repository.value ?: return
        if (mutableState.value.importing || uris.isEmpty()) return
        mutableState.update { it.copy(importing = true, message = null, error = false) }
        viewModelScope.launch(guard) {
            var failed = 0
            var completed = 0
            try {
                for ((index, uri) in uris.withIndex()) {
                    mutableState.update { it.copy(progress = "Saving on this device: ${index + 1} of ${uris.size}") }
                    try { repo.import(uri); completed++ }
                    catch (cancel: CancellationException) { throw cancel }
                    catch (_: Exception) { failed++ }
                    // Reveal committed imports in small batches, not after the whole selection.
                    if ((index + 1) % 10 == 0) {
                        val totals = repo.totals()
                        refreshDetails(repo)
                        mutableState.update { it.copy(bytes = totals.bytes.toLong(), revision = it.revision + 1) }
                    }
                }
                val totals = repo.totals()
                refreshDetails(repo)
                mutableState.update { it.copy(
                    bytes = totals.bytes.toLong(),
                    message = if (failed > 0) "$completed added. $failed couldn’t be copied; select them again to retry." else "Added to your device library",
                    error = failed > 0,
                ) }
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) {
                mutableState.update { it.copy(message = "Your last photo couldn’t be added. Check device storage and try again.", error = true) }
            } finally {
                mutableState.update { it.copy(importing = false, progress = null, revision = it.revision + 1) }
            }
        }
    }

    fun dismissMessage() { mutableState.update { it.copy(message = null) } }

    // The phone's own library: scanned on start, on change and on return to
    // the app; compared with Proton Photos while signed in.
    private val settings = BackupSettings(application)
    private val mutableBackup = MutableStateFlow(BackupState(access = PhoneLibrary.hasAccess(application),
        partial = PhoneLibrary.partialAccess(application), wifiOnly = settings.wifiOnly, chargingOnly = settings.chargingOnly,
        keepDays = settings.keepDays))
    val backup = mutableBackup.asStateFlow()
    private var phoneJob: Job? = null
    private var phoneAgain = false
    private val mediaChanged = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) { refreshPhone(delayMillis = 2_000) }
    }

    init {
        runCatching { application.contentResolver.registerContentObserver(PhoneLibrary.observed, true, mediaChanged) }
        viewModelScope.launch(guard) {
            var revision: ULong? = null
            var message: String? = null
            var checkBackup = false
            PhotosSync.state.collect { sync ->
                if (!account.value.connected || account.value.busy) return@collect
                mutableAccount.update {
                    it.copy(syncing = sync.running, lastPhotosSync = sync.lastSuccess,
                        sessionExpired = sync.expired, visible = it.visible || sync.expired)
                }
                mutableSyncProgress.update { it.copy(indexed = sync.indexed) }
                if (message != sync.message && sync.message != null && !sync.expired) {
                    mutableState.update { it.copy(message = sync.message, error = true) }
                }
                message = sync.message
                if (revision != sync.revision) {
                    revision = sync.revision
                    checkBackup = true
                    val repo = repository.value ?: return@collect
                    mutableState.update { it.copy(revision = it.revision + 1) }
                    try {
                        val place = location.value
                        if (place.protonAlbum && !repo.photoAlbumExists(requireNotNull(place.album))) {
                            navigate(place.back())
                        } else if (account.value.visibilityReady) {
                            refreshDetails(repo)
                        }
                    } catch (cancel: CancellationException) { throw cancel }
                    catch (_: Exception) {}
                }
                if (!sync.running && checkBackup) {
                    checkBackup = false
                    refreshPhone(scan = false)
                }
            }
        }
        viewModelScope.launch(guard) {
            var uploaded = 0
            PhoneBackup.progress.collect { progress ->
                mutableBackup.update { it.copy(progress = progress) }
                // Uploaded photos move from "only on this phone" to Proton.
                if (progress.done != uploaded || !progress.running) {
                    uploaded = progress.done
                    repository.value?.let { repo -> runCatching { refreshDetails(repo); refreshFolders(repo) } }
                    mutableState.update { it.copy(revision = it.revision + 1) }
                }
            }
        }
    }

    override fun onCleared() {
        runCatching { getApplication<Application>().contentResolver.unregisterContentObserver(mediaChanged) }
    }

    /** Called on return to the app: access may have changed in system settings. */
    fun phoneAccessChanged() {
        val app = getApplication<Application>()
        val access = PhoneLibrary.hasAccess(app)
        val before = mutableBackup.value
        mutableBackup.update { it.copy(access = access, partial = PhoneLibrary.partialAccess(app)) }
        if (access != before.access || access) refreshPhone()
    }

    /** Re-read the phone library, then compare it with Proton Photos. */
    fun refreshPhone(scan: Boolean = true, delayMillis: Long = 0) {
        val repo = repository.value ?: return
        if (phoneJob?.isActive == true) { phoneAgain = true; return }
        phoneJob = viewModelScope.launch(guard) {
            delay(delayMillis)
            do {
                phoneAgain = false
                try {
                    val app = getApplication<Application>()
                    if (scan && PhoneLibrary.hasAccess(app) && PhoneLibrary.scan(app, repo) >= 0) {
                        refreshDetails(repo)
                        mutableState.update { it.copy(revision = it.revision + 1) }
                    }
                    if (mutableAccount.value.connected && PhoneLibrary.hasAccess(app)) {
                        PhoneBackup.check(app, repo)
                        refreshDetails(repo)
                        mutableState.update { it.copy(revision = it.revision + 1) }
                    }
                    refreshFolders(repo)
                } catch (cancel: CancellationException) { throw cancel }
                catch (error: Exception) { android.util.Log.w("ProtonGallery", "Phone library update failed: ${error.javaClass.simpleName}") }
            } while (phoneAgain)
        }
    }

    private suspend fun refreshFolders(repo: GalleryRepository) {
        val folders = repo.phoneFolders()
        val freeable = runCatching { repo.freeUpSummary(settings.keepDays) }.getOrNull()
        val albums = runCatching { repo.backupAlbums() }.getOrDefault(mutableBackup.value.albums)
        mutableBackup.update { it.copy(folders = folders, freeable = freeable, albums = albums) }
        settings.automatic = folders.any { it.backup }
    }

    private val mutableRequests = MutableSharedFlow<UiRequest>(extraBufferCapacity = 4)
    val requests = mutableRequests.asSharedFlow()

    fun setKeepDays(days: Int) {
        settings.keepDays = days
        mutableBackup.update { it.copy(keepDays = days) }
        repository.value?.let { repo -> viewModelScope.launch(guard) { runCatching { refreshFolders(repo) } } }
    }

    /**
     * Free up space: confirm every backed-up item with Proton once more, then
     * let the system ask the user before anything is deleted.
     */
    fun freeUp() {
        val repo = repository.value ?: return
        if (mutableState.value.preparing != null) return
        if (!mutableAccount.value.connected) return showAccount()
        viewModelScope.launch(guard) {
            mutableState.update { it.copy(preparing = "Confirming your backups with Proton") }
            try {
                val confirmed = repo.freeUpVerified(settings.keepDays).take(FREE_UP_BATCH)
                if (confirmed.isEmpty()) {
                    mutableState.update { it.copy(message = "Nothing to remove. Older photos aren’t backed up yet.", error = false) }
                    return@launch
                }
                val app = getApplication<Application>()
                val uris = confirmed.map { PhoneLibrary.uri(it.id, it.isVideo) }
                val bytes = withContext(Dispatchers.IO) {
                    uris.sumOf { uri -> runCatching { app.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: 0L }.getOrDefault(0L) }
                }
                val request = MediaStore.createDeleteRequest(app.contentResolver, uris)
                mutableRequests.emit(UiRequest.Delete(request.intentSender, uris.size, bytes))
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) {
                mutableState.update { it.copy(message = (error as? GalleryException.Operation)?.detail ?: "Couldn’t confirm your backups. Nothing was deleted.", error = true) }
            } finally { mutableState.update { it.copy(preparing = null) } }
        }
    }

    /** The system dialog closed; `bytes` were freed when `deleted`. */
    fun freedUp(deleted: Boolean, count: Int, bytes: Long) {
        if (!deleted) return
        val app = getApplication<Application>()
        mutableState.update { it.copy(message = "Removed $count from this phone and freed ${Formatter.formatShortFileSize(app, bytes)}. They’re safe in Proton Photos.", error = false) }
        val repo = repository.value ?: return
        viewModelScope.launch(guard) {
            runCatching { PhoneLibrary.scan(app, repo, force = true); refreshDetails(repo); refreshFolders(repo) }
            mutableState.update { it.copy(revision = it.revision + 1) }
        }
    }

    /**
     * Move items to the trash: phone files to the system trash (the system
     * asks first), Proton copies to the Proton trash. An item on both goes
     * from both, so it never comes back through backup.
     */
    fun delete(items: List<GalleryItem>) {
        val deletable = items.filter { it.inProton || it.phoneId != null }
        if (deletable.isEmpty()) return
        val proton = deletable.filter { it.inProton }
        val phone = deletable.mapNotNull { PhoneLibrary.uri(it) }
        if (phone.isEmpty()) return trashProton(proton, deletable.size)
        viewModelScope.launch(guard) {
            try {
                val request = MediaStore.createTrashRequest(getApplication<Application>().contentResolver, phone, true)
                mutableRequests.emit(UiRequest.Trash(request.intentSender, proton, deletable.size))
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) {
                android.util.Log.w("ProtonGallery", "Couldn't ask to delete: ${error.javaClass.simpleName}")
                mutableState.update { it.copy(message = "Couldn’t delete these. Try again.", error = true) }
            }
        }
    }

    /**
     * Mark items as favorites, or not. Proton photos change in Proton, which
     * every Proton app shows; media only on this phone changes in the phone's
     * gallery, after the system asks.
     */
    fun favorite(items: List<GalleryItem>, favorite: Boolean) {
        val repo = repository.value ?: return
        val proton = items.filter { it.inProton }
        val phone = items.filter { !it.inProton }.mapNotNull { PhoneLibrary.uri(it) }
        viewModelScope.launch(guard) {
            if (phone.isNotEmpty()) try {
                val request = MediaStore.createFavoriteRequest(getApplication<Application>().contentResolver, phone, favorite)
                mutableRequests.emit(UiRequest.Favorite(request.intentSender))
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) { android.util.Log.w("ProtonGallery", "Couldn't ask to change favorites: ${error.javaClass.simpleName}") }
            if (proton.isEmpty()) return@launch
            if (!mutableAccount.value.connected) return@launch showAccount()
            try {
                val outcome = repo.setFavorite(proton, favorite)
                mutableState.update { it.copy(revision = it.revision + 1, error = outcome.failed > 0uL, message = when {
                    outcome.failed > 0uL -> "${outcome.failed} couldn’t be changed. Try again."
                    favorite -> if (proton.size == 1) "Added to favorites" else "Added ${proton.size} to favorites"
                    else -> if (proton.size == 1) "Removed from favorites" else "Removed ${proton.size} from favorites"
                }) }
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) {
                mutableState.update { it.copy(message = (error as? GalleryException.Operation)?.detail ?: "Couldn’t change the favorites. Try again.", error = true) }
            }
        }
    }

    /** The system favorites dialog closed: read the phone's gallery again. */
    fun phoneFavorited(confirmed: Boolean) { if (confirmed) refreshPhone() }

    // Originals of hidden albums decrypted for viewing; deleted when they lock.
    private val privateFiles = java.util.Collections.synchronizedSet(HashSet<File>())
    // Copies of hidden media handed to another app. Sharing itself leaves the
    // app and locks, so these go at the lock after that, once the other app
    // has had them.
    private val privateHandedOut = java.util.Collections.synchronizedSet(HashSet<File>())

    /** Hidden albums locked: delete what was decrypted from them. */
    fun forgetPrivate() {
        val files = synchronized(privateFiles) { privateFiles.toList().also { privateFiles.clear() } }
        synchronized(privateHandedOut) { privateFiles += privateHandedOut; privateHandedOut.clear() }
        if (files.isNotEmpty()) viewModelScope.launch(Dispatchers.IO) { files.forEach { it.delete() } }
    }

    /** The system trash dialog closed. */
    fun phoneTrashed(confirmed: Boolean, request: UiRequest.Trash) {
        if (!confirmed) return
        if (request.proton.isNotEmpty()) trashProton(request.proton, request.count)
        else afterDelete(request.count, 0)
    }

    private fun trashProton(items: List<GalleryItem>, count: Int) {
        val repo = repository.value ?: return
        if (items.isNotEmpty() && !mutableAccount.value.connected) return showAccount()
        viewModelScope.launch(guard) {
            mutableState.update { it.copy(preparing = if (items.size == 1) "Moving to the Proton trash" else "Moving ${items.size} items to the Proton trash") }
            try {
                val outcome = repo.trash(items)
                afterDelete(count - outcome.failed.toInt(), outcome.failed.toInt())
                PhotoPicker.changed(getApplication())
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) {
                mutableState.update { it.copy(message = (error as? GalleryException.Operation)?.detail ?: "Couldn’t move to the Proton trash. Try again.", error = true) }
            } finally { mutableState.update { it.copy(preparing = null) } }
        }
    }

    private fun afterDelete(deleted: Int, failed: Int) {
        mutableState.update { it.copy(revision = it.revision + 1, error = failed > 0, message = when {
            failed > 0 -> "$failed couldn’t be moved to the trash. Try again."
            deleted == 1 -> "Moved to the trash"
            else -> "Moved $deleted items to the trash"
        }) }
        val repo = repository.value ?: return
        viewModelScope.launch(guard) {
            runCatching { refreshDetails(repo); refreshFolders(repo) }
            refreshPhone()
        }
    }

    /** Hand items to another app: shared with a chooser, or returned to the app that asked. */
    fun share(items: List<GalleryItem>) = deliver(items, "share") { uris, mimes ->
        val mime = mimes.distinct().singleOrNull() ?: mimes.map { it.substringBefore('/') }.distinct().singleOrNull()?.let { "$it/*" } ?: "*/*"
        val intent = if (uris.size == 1) Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
            else Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        intent.setType(mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        intent.clipData = ClipData.newUri(getApplication<Application>().contentResolver, "Photos", uris[0]).apply {
            uris.drop(1).forEach { addItem(ClipData.Item(it)) }
        }
        UiRequest.Share(Intent.createChooser(intent, null))
    }

    fun pick(items: List<GalleryItem>) = deliver(items, "pick") { uris, mimes -> UiRequest.Picked(uris, mimes) }

    private fun deliver(items: List<GalleryItem>, verb: String, request: (List<Uri>, List<String>) -> UiRequest) {
        val repo = repository.value ?: return
        if (items.isEmpty() || mutableState.value.preparing != null) return
        viewModelScope.launch(guard) {
            val app = getApplication<Application>()
            val downloads = items.count { PhoneLibrary.uri(it) == null && it.remoteKey != null }
            mutableState.update { it.copy(preparing = when {
                downloads == 0 -> null
                downloads == 1 -> "Downloading 1 original from Proton"
                else -> "Downloading $downloads originals from Proton"
            }) }
            try {
                val private = location.value.private
                val uris = items.map { item ->
                    Originals.uri(app, repo, item) { file -> if (private) privateHandedOut += file }
                }
                mutableRequests.emit(request(uris, items.map(Originals::mime)))
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) {
                android.util.Log.w("ProtonGallery", "Couldn't $verb: ${error.javaClass.simpleName}")
                mutableState.update { it.copy(message = (error as? GalleryException.Operation)?.detail ?: "Couldn’t get these ready. Try again.", error = true) }
            } finally { mutableState.update { it.copy(preparing = null) } }
        }
    }

    /** A screen-sized preview of a Proton photo, or its original when there is none. */
    suspend fun largePhoto(item: GalleryItem): Bitmap? {
        val repo = repository.value ?: return null
        if (item.remoteKey == null) return null
        // Shown only, at most as large as the screen.
        val edge = ThumbnailLoader.screenEdge(getApplication())
        repo.preview(item)?.let { bytes -> withContext(Dispatchers.Default) { ThumbnailLoader.decodeBytes(bytes, edge, ImageDecoder.ALLOCATOR_DEFAULT) } }?.let { return it }
        val file = repo.downloadOriginal(item, Originals.directory(getApplication()))
        if (location.value.private) privateFiles += file
        return withContext(Dispatchers.IO) { ThumbnailLoader.decodeImage(file, edge, ImageDecoder.ALLOCATOR_DEFAULT) }
    }

    /** A Proton video to stream; nothing of it is written to disk. */
    suspend fun videoStream(item: GalleryItem): VideoStream? =
        if (item.remoteKey == null) null else repository.value?.openVideo(item)

    /** A playable file for a Proton video. */
    suspend fun original(item: GalleryItem, private: Boolean = false): File? =
        repository.value?.let { Originals.file(getApplication(), it, item) }?.also { if (private && item.localPath.isEmpty()) privateFiles += it }

    fun setBackupFolder(folder: PhoneFolder, enabled: Boolean) {
        val repo = repository.value ?: return
        viewModelScope.launch(guard) {
            repo.setBackupFolder(folder.id, enabled)
            refreshFolders(repo)
            Backup.schedule(getApplication())
        }
    }

    fun setWifiOnly(value: Boolean) {
        settings.wifiOnly = value
        mutableBackup.update { it.copy(wifiOnly = value) }
        Backup.schedule(getApplication())
    }

    fun setChargingOnly(value: Boolean) {
        settings.chargingOnly = value
        mutableBackup.update { it.copy(chargingOnly = value) }
        Backup.schedule(getApplication())
    }

    fun backUpNow() {
        if (!mutableAccount.value.connected) return showAccount()
        if (!mutableBackup.value.automatic || mutableBackup.value.request?.active == true) return
        trackBackup()
    }

    private var backupRequestJob: Job? = null

    private fun trackBackup(items: List<PhoneRef>? = null) {
        val generation = account.value.generation
        val request = Backup.request(getApplication(), items)
        backupRequestJob?.cancel()
        mutableBackup.update { it.copy(request = BackupRequest("Backup queued", true)) }
        mutableState.update { it.copy(message = "Backup queued", error = false) }
        backupRequestJob = viewModelScope.launch(guard) {
            request.collect { status ->
                if (account.value.generation == generation) {
                    mutableBackup.update { it.copy(request = status) }
                    if (!status.active) mutableState.update { it.copy(message = status.message) }
                }
            }
        }
    }

    /** Upload chosen phone items now, on any network. */
    /**
     * Back up `folder` (every backup, for null) into `album` too, or stop.
     * With `includeBackedUp`, what is already backed up goes in as well.
     */
    fun setBackupAlbum(folder: String?, album: FolderAlbum, add: Boolean, includeBackedUp: Boolean) =
        backupAlbumWork(folder, add) { repo -> album.also { repo.setBackupAlbum(folder, it, add, includeBackedUp && add) } }

    /** Create an album and back `folder` (every backup, for null) up into it. */
    fun createBackupAlbum(folder: String?, name: String, includeBackedUp: Boolean) =
        backupAlbumWork(folder, true) { repo -> repo.createAlbum(name).also { repo.setBackupAlbum(folder, it, true, includeBackedUp) } }

    private fun backupAlbumWork(folder: String?, add: Boolean, work: suspend (GalleryRepository) -> FolderAlbum) {
        val repo = repository.value ?: return
        if (!mutableAccount.value.connected) return showAccount()
        val from = folder?.let { id -> mutableBackup.value.folders.firstOrNull { it.id == id }?.name } ?: "this phone"
        albumWork(if (add) "Choosing the album" else "Updating backup albums", "Couldn’t change the backup album. Try again.") { _ ->
            val album = work(repo)
            refreshFolders(repo)
            // Queued photos that are backed up already go in now.
            if (add) Backup.now(getApplication())
            if (add) "Backups from $from also go into “${album.name}”" else "Backups from $from no longer go into “${album.name}”"
        }
    }

    /**
     * Hide photos in Gallery by putting them into a hidden album, `album` or a
     * new one named Hidden. Phone photos not backed up yet are backed up and
     * then go in.
     */
    fun hide(items: List<GalleryItem>, album: FolderAlbum?, name: String = "Hidden") {
        val proton = items.filter { it.inProton }
        val waiting = items.filter { !it.inProton && it.phoneId != null }
        if (proton.isEmpty() && waiting.isEmpty()) {
            mutableState.update { it.copy(message = "Only Proton Photos and this phone’s photos can be hidden", error = false) }
            return
        }
        albumWork("Hiding", "Couldn’t hide. Try again.") { repo ->
            val target = album ?: repo.createAlbum(name).also { repo.setAlbumHidden(it, true) }
            if (!target.hidden && album != null) repo.setAlbumHidden(target, true)
            val added = if (proton.isEmpty()) 0 else repo.addToAlbum(target, proton).changed.toInt()
            if (waiting.isNotEmpty()) {
                repo.queueAlbumAdds(target, waiting.mapNotNull { it.phoneId })
                Backup.upload(getApplication(), waiting.map { PhoneRef(it.phoneId!!, it.isVideo) })
            }
            buildString {
                if (added > 0) append(if (added == 1) "1 item hidden in Gallery" else "$added items hidden in Gallery")
                if (waiting.isNotEmpty()) {
                    if (isNotEmpty()) append(". ")
                    append(if (waiting.size == 1) "1 item will be hidden after backup" else "${waiting.size} items will be hidden after backup")
                }
                if (proton.size > added) {
                    if (isNotEmpty()) append(". ")
                    append("${proton.size - added} couldn’t be added to the hidden album")
                }
                if (items.any { it.phoneId != null }) append(". Other gallery apps still show the phone’s copy")
            }
        }
    }

    fun upload(items: List<GalleryItem>) {
        if (!mutableAccount.value.connected) return showAccount()
        val refs = items.filter { it.phoneId != null && !it.backedUp }.map { PhoneRef(it.phoneId!!, it.isVideo) }
        if (refs.isEmpty()) {
            mutableState.update { it.copy(message = "These are already in Proton Photos", error = false) }
            return
        }
        trackBackup(refs)
    }

    private companion object {
        /** The system asks once per request; keep each one a reasonable size. */
        const val FREE_UP_BATCH = 2_000
    }

    // Last, so every property above is initialized before the library opens.
    init { open(); followOutline() }
}

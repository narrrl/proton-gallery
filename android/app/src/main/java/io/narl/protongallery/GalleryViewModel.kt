package io.narl.protongallery

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import io.narl.protongallery.bridge.GalleryItem
import io.narl.protongallery.bridge.FolderAlbum
import io.narl.protongallery.bridge.AlbumOptions
import io.narl.protongallery.bridge.AlbumTag
import io.narl.protongallery.bridge.DriveFolder
import io.narl.protongallery.bridge.PhoneFolder
import io.narl.protongallery.bridge.PhoneRef
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
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import io.narl.protongallery.bridge.GalleryException
import io.narl.protongallery.bridge.SignInStage
import kotlinx.coroutines.withContext

data class LibraryState(
    val ready: Boolean = false,
    val count: Long = 0,
    val bytes: Long = 0,
    val importing: Boolean = false,
    val progress: String? = null,
    val message: String? = null,
    val error: Boolean = false,
    val revision: Int = 0,
)

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
    /** Comparing the phone with Proton Photos in the foreground. */
    val checking: Boolean = false,
    val progress: BackupProgress = BackupProgress(),
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
        android.util.Log.e("ProtonGallery", "Background task failed", error)
        CrashLog.note(getApplication(), error)
        mutableState.update { it.copy(message = "Something went wrong. Try again.", error = true) }
    }
    private val mutableAccount = MutableStateFlow(AccountState())
    val account = mutableAccount.asStateFlow()
    private var closingAccount = false
    private var authJob: Job? = null
    private var photosJob: Job? = null
    private var driveJob: Job? = null

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
                mutableAccount.update { it.copy(status = status, visible = status.stage != SignInStage.CONNECTED,
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
        val repo = repository.value ?: return
        if (mutableAccount.value.busy) return
        mutableAccount.update { it.copy(busy = true, error = null) }
        authJob = viewModelScope.launch(guard) {
            try {
                repo.signOut()
                photosJob?.join(); driveJob?.join()
                mutableAccount.update { AccountState(generation = it.generation + 1) }
                navigate(GalleryLocation())
                mutableState.update { it.copy(revision = it.revision + 1) }
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) { mutableAccount.update { it.copy(error = authMessage(error)) } }
            finally { mutableAccount.update { it.copy(busy = false) } }
        }
    }
    fun syncPhotos() {
        val repo = repository.value ?: return
        if (!mutableAccount.value.connected || photosJob?.isActive == true) return
        mutableAccount.update { it.copy(syncing = true, indexed = 0) }
        photosJob = viewModelScope.launch(guard) {
            val progress = watchProgress(repo, { it.syncProgress().toLong() }) { indexed -> mutableAccount.update { it.copy(indexed = indexed) } }
            try {
                repo.syncPhotos()
                refreshDetails(repo)
                refreshPhone(scan = false)
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) {
                report(error, "Couldn’t refresh Proton Photos. Your cached library is available. Check your connection or sign in again.")
            } finally {
                progress.cancel()
                mutableAccount.update { it.copy(syncing = false) }
                settle(repo)
            }
        }
    }

    /** Refresh what is on screen: a Drive gallery, every gallery, or Photos. */
    fun refresh() {
        val place = location.value
        if (place.section != GallerySection.Drive) return syncPhotos()
        val album = place.album
        if (album != null) runDrive(album.name, "Couldn’t refresh this gallery. Your cached albums are available.") { it.refreshDriveGallery(album.gallery) }
        else runDrive("your galleries", "Couldn’t refresh every gallery. Your cached albums are available.") { repo ->
            for (gallery in repo.albums(GalleryLocation(GallerySection.Drive))) repo.refreshDriveGallery(gallery.gallery)
        }
    }

    /** Add or refresh Drive galleries; runs alongside a Photos sync. */
    private fun runDrive(label: String, failure: String, after: suspend (GalleryRepository) -> Unit = {}, action: suspend (GalleryRepository) -> Unit) {
        val repo = repository.value ?: return
        if (!mutableAccount.value.connected || driveJob?.isActive == true) return
        mutableAccount.update { it.copy(driveSync = label, driveIndexed = 0) }
        driveJob = viewModelScope.launch(guard) {
            val progress = watchProgress(repo, { it.driveProgress().toLong() }) { indexed -> mutableAccount.update { it.copy(driveIndexed = indexed) } }
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
            mutableState.update { it.copy(message = "“${folder.name}” added. Its folders are now albums.", error = false) }
        }) { it.addDriveGallery(folder.id) }
    }
    fun removeGallery(album: FolderAlbum) {
        val repo = repository.value ?: return
        viewModelScope.launch(guard) {
            try {
                repo.removeDriveGallery(album.gallery)
                navigate(GalleryLocation(GallerySection.Drive))
                mutableState.update { it.copy(message = "Gallery removed. Nothing was deleted from Drive.") }
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { mutableState.update { it.copy(message = "The gallery couldn’t be removed. Try again.", error = true) } }
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
                val saved = repo.setOptions(place, options) ?: return@launch
                if (location.value != place) return@launch
                mutableOptions.value = saved
                if (before?.order != saved.order) mutableOrder.update { it + 1 }
                place.album?.let { album ->
                    mutableLocation.value = place.renamed(album.copy(name = saved.title ?: album.folderName.ifEmpty { album.name },
                        description = saved.description, tags = saved.tags, pinned = saved.pinned, hidden = saved.hidden))
                }
                mutableTags.value = repo.albumTags()
                if (confirmation != null) mutableState.update { it.copy(message = confirmation, error = false) }
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) {
                mutableState.update { it.copy(message = (error as? GalleryException.Operation)?.detail ?: "Album settings couldn’t be saved. Try again.", error = true) }
            }
        }
    }
    fun setCover(item: GalleryItem) {
        val current = mutableOptions.value ?: return
        if (item.assetKey.isEmpty()) return
        saveOptions(current.copy(coverKey = item.assetKey), "Cover updated")
    }

    suspend fun remoteThumbnail(item: GalleryItem) = repository.value?.thumbnail(item)
    suspend fun rememberShape(item: GalleryItem, width: Int, height: Int) { repository.value?.rememberShape(item, width, height) }
    private fun authMessage(error: Exception): String = (error as? GalleryException.Operation)?.detail
        ?: "The account request couldn’t be completed. Try again."
    private val mutableLocation = MutableStateFlow(GalleryLocation())
    val location = mutableLocation.asStateFlow()
    private val mutableAlbums = MutableStateFlow<List<FolderAlbum>>(emptyList())
    val albums = mutableAlbums.asStateFlow()
    private var pagingGeneration: Int = -1
    private var pagingOrder: Int = 0
    private var pagingPlace: GalleryLocation? = null
    private var pagingFlow: Flow<PagingData<GalleryItem>>? = null
    private var pagingScope: CoroutineScope? = null

    // One retained paging session: rotation keeps it, navigation cancels it.
    // Separate flows prevent old device/album items flashing under a Photos header.
    // Keyed by the native view, so renaming an album in the trail keeps the session.
    fun photosAt(place: GalleryLocation, generation: Int = 0, order: Int = 0): Flow<PagingData<GalleryItem>> {
        if (pagingPlace?.section == place.section && pagingPlace?.nativeView() == place.nativeView() && pagingGeneration == generation && pagingOrder == order) pagingFlow?.let { return it }
        pagingScope?.cancel()
        val scope = CoroutineScope(SupervisorJob(viewModelScope.coroutineContext[Job]) + Dispatchers.Main.immediate)
        pagingScope = scope
        val flow = repository.flatMapLatest { repo ->
            val view = place.nativeView()
            if (repo == null || view == null) flowOf(PagingData.empty<GalleryItem>())
            else Pager(
                PagingConfig(pageSize = 90, initialLoadSize = 90, prefetchDistance = 18, maxSize = 360, enablePlaceholders = false),
                pagingSourceFactory = { repo.pagingSource(view) },
            ).flow
        }.cachedIn(scope)
        pagingGeneration = generation
        pagingOrder = order
        pagingPlace = place
        pagingFlow = flow
        return flow
    }

    fun navigate(place: GalleryLocation) {
        mutableLocation.value = place
        mutableAlbums.value = emptyList()
        mutableOptions.value = null
        if (place.section != GallerySection.Drive || place.album != null) mutableTagFilter.value = null
        mutableState.update { it.copy(count = 0) }
        val repo = repository.value ?: return
        viewModelScope.launch(guard) {
            try { refreshDetails(repo, place) }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { mutableState.update { it.copy(message = "This album couldn’t be opened. Try again.", error = true) } }
        }
    }

    private suspend fun refreshDetails(repo: GalleryRepository, place: GalleryLocation = location.value) {
        val count = repo.count(place)
        val driveRoot = place.section == GallerySection.Drive && place.album == null
        val tag = mutableTagFilter.value.takeIf { driveRoot }
        val children = if (tag != null) repo.taggedAlbums(tag) else repo.albums(place)
        val options = repo.options(place)
        val tags = if (place.section == GallerySection.Drive) repo.albumTags() else emptyList()
        if (location.value == place) {
            mutableState.update { it.copy(count = count) }
            mutableAlbums.value = children
            mutableOptions.value = options
            mutableTags.value = tags
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
                    mutableAccount.update { it.copy(status = repo.accountStatus(), generation = it.generation + 1) }
                } catch (error: Exception) {
                    mutableAccount.update { it.copy(error = authMessage(error), visible = true) }
                }
                refreshDetails(repo)
                refreshPhone()
                Backup.schedule(getApplication())
                if (mutableAccount.value.connected) syncPhotos()
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) {
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
        partial = PhoneLibrary.partialAccess(application), wifiOnly = settings.wifiOnly, chargingOnly = settings.chargingOnly))
    val backup = mutableBackup.asStateFlow()
    private var phoneJob: Job? = null
    private var phoneAgain = false
    private val mediaChanged = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) { refreshPhone(delayMillis = 2_000) }
    }

    init {
        runCatching { application.contentResolver.registerContentObserver(PhoneLibrary.observed, true, mediaChanged) }
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
                    if (scan && PhoneLibrary.hasAccess(app)) {
                        PhoneLibrary.scan(app, repo)
                        refreshDetails(repo)
                        mutableState.update { it.copy(revision = it.revision + 1) }
                    }
                    if (mutableAccount.value.connected && PhoneLibrary.hasAccess(app)) {
                        mutableBackup.update { it.copy(checking = true) }
                        try { PhoneBackup.check(app, repo) } finally { mutableBackup.update { it.copy(checking = false) } }
                        refreshDetails(repo)
                        mutableState.update { it.copy(revision = it.revision + 1) }
                    }
                    refreshFolders(repo)
                } catch (cancel: CancellationException) { throw cancel }
                catch (error: Exception) { android.util.Log.w("ProtonGallery", "Phone library update failed", error) }
            } while (phoneAgain)
        }
    }

    private suspend fun refreshFolders(repo: GalleryRepository) {
        val folders = repo.phoneFolders()
        mutableBackup.update { it.copy(folders = folders) }
        settings.automatic = folders.any { it.backup }
    }

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
        Backup.now(getApplication())
        mutableState.update { it.copy(message = if (settings.wifiOnly) "Backup starts when you’re on Wi‑Fi" else "Backup started", error = false) }
    }

    /** Upload chosen phone items now, on any network. */
    fun upload(items: List<GalleryItem>) {
        if (!mutableAccount.value.connected) return showAccount()
        val refs = items.filter { it.phoneId != null && !it.backedUp }.map { PhoneRef(it.phoneId!!, it.isVideo) }
        if (refs.isEmpty()) {
            mutableState.update { it.copy(message = "These are already in Proton Photos", error = false) }
            return
        }
        Backup.upload(getApplication(), refs)
        mutableState.update { it.copy(message = if (refs.size == 1) "Backing up 1 item" else "Backing up ${refs.size} items", error = false) }
    }

    // Last, so every property above is initialized before the library opens.
    init { open() }
}

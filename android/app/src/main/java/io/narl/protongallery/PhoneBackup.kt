package io.narl.protongallery

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkInfo
import androidx.work.WorkerParameters
import androidx.work.await
import androidx.work.workDataOf
import io.narl.protongallery.bridge.GalleryException
import io.narl.protongallery.bridge.MediaReader
import io.narl.protongallery.bridge.PhoneHash
import io.narl.protongallery.bridge.PhoneRef
import io.narl.protongallery.bridge.SignInStage
import io.narl.protongallery.bridge.UploadOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.io.InputStream
import java.text.NumberFormat
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.TimeUnit

/** What the phone backup is doing, for the screen and the notification. */
data class BackupProgress(
    /** Reading the phone's media index. */
    val scanning: Boolean = false,
    /** Comparing the phone with Proton Photos. */
    val checking: Boolean = false,
    /** Items still to compare, and how many there were when the check began. */
    val toCheck: Long = 0,
    val checkTotal: Long = 0,
    val uploading: Boolean = false,
    /** Uploads finished and planned in the current run, and failures among them. */
    val done: Int = 0,
    val total: Int = 0,
    val failed: Int = 0,
    /** Bytes of the current file sent so far, and its size. */
    val bytes: Long = 0,
    val size: Long = 0,
    /** The outcome of the last run, or why it stopped. */
    val message: String? = null,
) {
    val running: Boolean get() = scanning || checking || uploading

    /** Share of the current step that is done, when it is known. */
    val fraction: Float? get() = when {
        uploading && total > 0 -> {
            val current = if (size > 0) (bytes.toFloat() / size).coerceIn(0f, 1f) else 0f
            ((done + current) / total).coerceIn(0f, 1f)
        }
        checking && checkTotal > 0 -> (1f - toCheck.toFloat() / checkTotal).coerceIn(0f, 1f)
        else -> null
    }
}

data class BackupRequest(val message: String, val active: Boolean, val running: Boolean = false)

data class BackupOutcome(val considered: Int = 0, val failed: Int = 0) {
    operator fun plus(other: BackupOutcome) = BackupOutcome(considered + other.considered, failed + other.failed)
}

/** User choices for automatic backup. Folders live in the catalog. */
class BackupSettings(context: Context) {
    private val preferences = context.getSharedPreferences("backup", Context.MODE_PRIVATE)
    var wifiOnly: Boolean
        get() = preferences.getBoolean("wifi-only", true)
        set(value) = preferences.edit { putBoolean("wifi-only", value) }
    var chargingOnly: Boolean
        get() = preferences.getBoolean("charging-only", false)
        set(value) = preferences.edit { putBoolean("charging-only", value) }
    /** Free up space keeps media taken in this many recent days. */
    var keepDays: Int
        get() = preferences.getInt("keep-days", 90)
        set(value) = preferences.edit { putInt("keep-days", value) }
    /** Whether any folder backs up automatically. */
    var automatic: Boolean
        get() = preferences.getBoolean("automatic", false)
        set(value) = preferences.edit { putBoolean("automatic", value) }
}

/**
 * Compares the phone with Proton Photos and uploads what is missing. The
 * screens and background work share the engine and these locks: one check at
 * a time, and one upload at a time. Chosen items never wait for a check.
 */
object PhoneBackup {
    private val mutableProgress = MutableStateFlow(BackupProgress())
    val progress = mutableProgress.asStateFlow()
    private val checking = Mutex()
    private val uploading = Mutex()
    private val runs = AtomicInteger()

    internal fun scanning(active: Boolean) = mutableProgress.update { it.copy(scanning = active) }

    /** Files that couldn’t be read; the check passes over them until the app restarts. */
    private val unreadable = java.util.Collections.synchronizedSet(HashSet<String>())

    /**
     * Compare phone media with Proton. Names are compared first; only
     * same-named files are read and hashed. Results are kept, so a check
     * that was interrupted continues where it stopped, and later checks only
     * look at what is new.
     */
    suspend fun check(context: Context, repository: GalleryRepository): Unit = checking.withLock {
        var start = repository.backupRemaining() - unreadable.size
        if (start <= 0L) {
            // Names that appeared in Proton since the last check are compared again.
            if (repository.checkBackup(unreadable.toList()).isEmpty()) return@withLock
            start = repository.backupRemaining()
        }
        mutableProgress.update { it.copy(checking = true, toCheck = start, checkTotal = start) }
        try {
            while (true) {
                val pending = repository.checkBackup(unreadable.toList())
                val left = (repository.backupRemaining() - unreadable.size).coerceAtLeast(0)
                mutableProgress.update { it.copy(toCheck = left, checkTotal = maxOf(it.checkTotal, left)) }
                if (pending.isEmpty()) break
                val hashes = hashAll(context, pending) { mutableProgress.update { it.copy(toCheck = (it.toCheck - 1).coerceAtLeast(0)) } }
                val read = hashes.mapTo(HashSet()) { it.id }
                pending.filter { it.id !in read }.forEach { unreadable += it.id }
                if (hashes.isNotEmpty()) repository.setPhoneHashes(hashes)
            }
        } finally {
            mutableProgress.update { it.copy(checking = false, toCheck = 0, checkTotal = 0) }
        }
    }

    /** Reading is the slow part, so a few files are hashed at once. */
    private suspend fun hashAll(context: Context, items: List<PhoneRef>, onHashed: () -> Unit): List<PhoneHash> = coroutineScope {
        val gate = Semaphore(3)
        items.map { item ->
            async(Dispatchers.IO) {
                gate.withPermit {
                    try { PhoneHash(item.id, PhoneLibrary.sha1(context, PhoneLibrary.uri(item.id, item.isVideo))) }
                    catch (cancel: CancellationException) { throw cancel }
                    catch (_: Exception) { null }
                    finally { onHashed() }
                }
            }
        }.awaitAll().filterNotNull()
    }

    /**
     * Upload `items` right away, or, when `items` is null, scan, compare and
     * upload everything missing from the backup folders. Uploads start as
     * soon as the check finds something missing; they never wait for the
     * whole library to be compared.
     */
    suspend fun run(context: Context, items: List<PhoneRef>?): BackupOutcome {
        val repository = GalleryRepository.get(context)
        try {
            repository.ensureSession()
            if (repository.accountStatus().stage != SignInStage.CONNECTED) {
                throw IllegalStateException("Sign in to Proton to back up")
            }
            // Albums of earlier backups that failed to fill go first.
            flushAlbums(repository)
            if (items != null) return uploadSession { upload(context, repository, items) }.also { flushAlbums(repository) }
            PhoneLibrary.scan(context, repository)
            return coroutineScope {
                val checker = launch { check(context, repository) }
                val tried = HashSet<String>()
                var result = BackupOutcome()
                uploadSession {
                    while (true) {
                        val finished = !checker.isActive
                        val queue = repository.backupQueue(500).filter { tried.add(it.id) }
                        if (queue.isNotEmpty()) result += upload(context, repository, queue)
                        else if (finished) break
                        else delay(1_500)
                    }
                }
                checker.join()
                flushAlbums(repository)
                result
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            android.util.Log.w("ProtonGallery", "Backup stopped: ${error.javaClass.simpleName}")
            val message = (error as? GalleryException.Operation)?.detail ?: "Backup stopped. It will try again later."
            mutableProgress.update { it.copy(message = message) }
            throw error
        }
    }

    /** Put backed-up photos into the albums chosen for them. What fails stays
     * queued for the next backup. */
    private suspend fun flushAlbums(repository: GalleryRepository) {
        try {
            val change = repository.flushAlbumAdds()
            if (change.failed > 0uL) mutableProgress.update {
                it.copy(message = "Some backups couldn’t go into their albums yet. They will with the next backup.")
            }
        } catch (cancel: CancellationException) { throw cancel }
        catch (error: Exception) { android.util.Log.w("ProtonGallery", "Adding backups to albums failed: ${error.javaClass.simpleName}") }
    }

    /** One upload session: its progress adds up, and its outcome shows once at the end. */
    private suspend fun <T> uploadSession(block: suspend () -> T): T {
        runs.incrementAndGet()
        try { return block() }
        finally {
            if (runs.decrementAndGet() == 0) mutableProgress.update {
                val sent = it.done - it.failed
                if (it.total == 0) it.copy(uploading = false, message = "Everything is backed up")
                else it.copy(uploading = false, done = 0, total = 0, failed = 0, bytes = 0, size = 0, message = when {
                    it.failed > 0 -> "${if (it.failed == 1) "1 item" else "${it.failed} items"} couldn’t be backed up. ${it.message ?: ""}".trim()
                    sent == 1 -> "1 item backed up"
                    sent > 1 -> "$sent items backed up"
                    else -> it.message
                })
            }
        }
    }

    private suspend fun upload(context: Context, repository: GalleryRepository, queue: List<PhoneRef>): BackupOutcome {
        if (queue.isEmpty()) return BackupOutcome()
        mutableProgress.update { it.copy(uploading = true, total = it.total + queue.size, message = if (it.total == 0) null else it.message) }
        var failed = 0
        for (item in queue) {
            val error = uploading.withLock {
                try {
                    uploadOne(context, repository, item) { sent, size -> mutableProgress.update { it.copy(bytes = sent, size = size) } }
                    null
                } catch (cancel: CancellationException) { throw cancel }
                catch (error: Exception) {
                    android.util.Log.w("ProtonGallery", "Backup of one item failed: ${error.javaClass.simpleName}")
                    error
                }
            }
            if (error != null) failed++
            mutableProgress.update {
                it.copy(done = it.done + 1, failed = it.failed + if (error != null) 1 else 0, bytes = 0, size = 0,
                    message = if (error != null) describe(error) else it.message)
            }
        }
        return BackupOutcome(queue.size, failed)
    }

    private fun describe(error: Exception): String = when (error) {
        is GalleryException.Operation -> error.detail
        is GalleryException -> error.message ?: "Proton Photos couldn’t be reached."
        is IOException -> "The file couldn’t be read from the phone."
        else -> "Something went wrong."
    }

    private suspend fun uploadOne(context: Context, repository: GalleryRepository, item: PhoneRef, onBytes: (Long, Long) -> Unit): UploadOutcome {
        val uri = PhoneLibrary.uri(item.id, item.isVideo)
        val sha1 = withContext(Dispatchers.IO) { PhoneLibrary.sha1(context, uri) }
        val thumbnail = withContext(Dispatchers.IO) { PhoneLibrary.uploadThumbnail(context, uri) }
        val size = withContext(Dispatchers.IO) { context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1 }
        val input = withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri) } ?: throw IOException("The file couldn’t be opened.")
        return input.use { repository.uploadPhone(item.id, sha1, StreamReader(it) { sent -> onBytes(sent, size) }, thumbnail) }
    }

    /** Hands the file to Rust in chunks; never more than one chunk in memory. */
    private class StreamReader(private val input: InputStream, private val onSent: (Long) -> Unit) : MediaReader {
        private var sent = 0L
        override fun read(max: UInt): ByteArray {
            val buffer = ByteArray(max.toInt().coerceIn(1, 1 shl 20))
            var filled = 0
            try {
                while (filled < buffer.size) {
                    val read = input.read(buffer, filled, buffer.size - filled)
                    if (read < 0) break
                    filled += read
                }
            } catch (error: IOException) {
                throw GalleryException.Operation("The file couldn’t be read from the phone.")
            }
            sent += filled
            onSent(sent)
            return if (filled == buffer.size) buffer else buffer.copyOf(filled)
        }
    }
}

/** Runs backups in the background, with a notification while uploading. */
class BackupWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = coroutineScope {
        val items = inputData.getNullableStringArray(ITEMS)?.mapNotNull { it?.toRef() }
        val notification = launch {
            PhoneBackup.progress.collect { progress ->
                if (progress.running) runCatching { setForeground(foreground(progress)) }
                delay(1_000)
            }
        }
        val outcome = try {
            PhoneBackup.run(applicationContext, items)
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { return@coroutineScope if (runAttemptCount < 3) Result.retry() else Result.failure() }
        finally { notification.cancel() }
        PhotoPicker.changed(applicationContext)
        if (items == null) Backup.watchNewMedia(applicationContext)
        if (outcome.failed > 0 && runAttemptCount < 3) Result.retry()
        else Result.success(workDataOf(CONSIDERED to outcome.considered, FAILED to outcome.failed))
    }

    override suspend fun getForegroundInfo() = foreground(PhoneBackup.progress.value.copy(checking = true))

    private fun foreground(progress: BackupProgress): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Photo backup", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(applicationContext, 0,
            Intent(applicationContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(when {
                progress.uploading -> "Backing up to Proton Photos"
                progress.scanning -> "Reading this phone’s photos"
                else -> "Checking your photos"
            })
            .setContentText(when {
                progress.uploading -> "${minOf(progress.done + 1, progress.total)} of ${progress.total}"
                progress.toCheck > 0 -> "${NumberFormat.getIntegerInstance().format(progress.toCheck)} left to compare with Proton Photos"
                else -> "Comparing this phone with Proton Photos"
            })
            .setProgress(1000, ((progress.fraction ?: 0f) * 1000).toInt(), progress.fraction == null)
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .addAction(0, "Pause", WorkManager.getInstance(applicationContext).createCancelPendingIntent(id))
            .build()
        return ForegroundInfo(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    companion object {
        const val ITEMS = "items"
        const val CONSIDERED = "considered"
        const val FAILED = "failed"
        private const val CHANNEL = "backup"
        private const val NOTIFICATION = 7301
    }
}

private fun PhoneRef.encode() = "$id:${if (isVideo) 1 else 0}"
private fun String.toRef(): PhoneRef? = split(':').takeIf { it.size == 2 }?.let { PhoneRef(it[0], it[1] == "1") }

/** Schedules backups: periodic, on new media, and on request. */
object Backup {
    private const val PERIODIC = "backup-periodic"
    private const val NEW_MEDIA = "backup-new-media"
    private const val NOW = "backup-now"
    private const val SELECTED = "backup-selected"
    private const val REQUESTED = "backup-requested"

    private fun constraints(context: Context, manual: Boolean = false): Constraints {
        val settings = BackupSettings(context)
        return Constraints.Builder()
            .setRequiredNetworkType(if (settings.wifiOnly && !manual) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .setRequiresCharging(settings.chargingOnly && !manual)
            .build()
    }

    /** Apply the current settings: start or stop automatic backup. */
    fun schedule(context: Context) {
        val work = WorkManager.getInstance(context)
        if (!BackupSettings(context).automatic) {
            work.cancelUniqueWork(PERIODIC); work.cancelUniqueWork(NEW_MEDIA); work.cancelUniqueWork(NOW)
            work.cancelUniqueWork(REQUESTED)
            return
        }
        work.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<BackupWorker>(6, TimeUnit.HOURS).setConstraints(constraints(context)).build())
        watchNewMedia(context)
        now(context)
    }

    /** Back up everything missing from the backup folders, soon. */
    fun now(context: Context) {
        if (!BackupSettings(context).automatic) return
        WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<BackupWorker>().setConstraints(constraints(context)).build())
    }

    suspend fun stop(context: Context) {
        WorkManager.getInstance(context).cancelAllWorkByTag(BackupWorker::class.java.name).await()
    }

    fun request(context: Context, items: List<PhoneRef>? = null): Flow<BackupRequest> {
        val manager = WorkManager.getInstance(context)
        val settings = BackupSettings(context)
        val tag = "backup-request-${UUID.randomUUID()}"
        val chunks = items?.chunked(300) ?: listOf(null)
        val requests = chunks.map { part ->
            OneTimeWorkRequestBuilder<BackupWorker>().setConstraints(constraints(context, manual = items != null))
                .addTag(tag).apply {
                    if (part != null) setInputData(workDataOf(BackupWorker.ITEMS to part.map { it.encode() }.toTypedArray()))
                }.build()
        }
        var work = manager.beginUniqueWork(if (items == null) REQUESTED else SELECTED,
            ExistingWorkPolicy.APPEND_OR_REPLACE, requests.first())
        for (request in requests.drop(1)) work = work.then(request)
        val enqueue = work.enqueue()
        val wifi = items == null && settings.wifiOnly
        val charging = items == null && settings.chargingOnly
        return manager.getWorkInfosByTagFlow(tag).map { infos ->
            if (infos.any { it.state == WorkInfo.State.RUNNING }) BackupRequest("Backing up to Proton Photos…", true, true)
            else if (infos.size != requests.size || infos.any { !it.state.isFinished }) {
                BackupRequest(waitingForBackup(context, wifi, charging, infos.any { it.state == WorkInfo.State.ENQUEUED && it.runAttemptCount > 0 }), true)
            }
            else if (infos.any { it.state == WorkInfo.State.CANCELLED }) BackupRequest("Backup cancelled", false)
            else {
                val done = infos.sumOf {
                    it.outputData.getInt(BackupWorker.CONSIDERED, 0) - it.outputData.getInt(BackupWorker.FAILED, 0)
                }
                val failed = infos.sumOf { it.outputData.getInt(BackupWorker.FAILED, 0) }
                val message = when {
                    infos.any { it.state == WorkInfo.State.FAILED } -> "Backup stopped. $done completed. Try again."
                    failed > 0 -> "$done backed up. $failed couldn’t be backed up."
                    done == 0 -> "Everything is backed up"
                    done == 1 -> "1 item backed up"
                    else -> "$done items backed up"
                }
                BackupRequest(message, false)
            }
        }.onStart { enqueue.await() }
            .catch { emit(BackupRequest("Couldn’t queue backup. Try again.", false)) }
            .distinctUntilChanged()
    }

    private fun waitingForBackup(context: Context, wifi: Boolean, charging: Boolean, retrying: Boolean): String {
        val connection = context.getSystemService(ConnectivityManager::class.java)
        val network = connection.getNetworkCapabilities(connection.activeNetwork)
        val battery = if (charging) context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) else null
        val power = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
        return when {
            wifi && network?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) != true -> "Backup waiting for Wi‑Fi"
            network == null -> "Backup waiting for a connection"
            charging && power != BatteryManager.BATTERY_STATUS_CHARGING && power != BatteryManager.BATTERY_STATUS_FULL -> "Backup waiting for charging"
            retrying -> "Backup waiting to retry"
            else -> "Backup queued"
        }
    }

    /** Run again shortly after new photos or videos appear. */
    fun watchNewMedia(context: Context) {
        if (!BackupSettings(context).automatic) return
        val trigger = Constraints.Builder()
            .setRequiredNetworkType(if (BackupSettings(context).wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .setRequiresCharging(BackupSettings(context).chargingOnly)
            .addContentUriTrigger(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true)
            .addContentUriTrigger(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true)
            .setTriggerContentUpdateDelay(30, TimeUnit.SECONDS)
            .setTriggerContentMaxDelay(5, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(NEW_MEDIA, ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<BackupWorker>().setConstraints(trigger).build())
    }

    /** Upload chosen items now, on any network. */
    fun upload(context: Context, items: List<PhoneRef>) {
        val work = WorkManager.getInstance(context)
        // Work input is limited to 10 KB, so long selections go in parts.
        for (part in items.chunked(300)) {
            work.enqueueUniqueWork(SELECTED, ExistingWorkPolicy.APPEND_OR_REPLACE,
                OneTimeWorkRequestBuilder<BackupWorker>()
                    .setConstraints(constraints(context, manual = true))
                    .setInputData(workDataOf(BackupWorker.ITEMS to part.map { it.encode() }.toTypedArray()))
                    .build())
        }
    }
}

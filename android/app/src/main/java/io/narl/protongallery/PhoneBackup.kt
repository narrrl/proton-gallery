package io.narl.protongallery

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
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
import androidx.work.WorkerParameters
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
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.io.InputStream
import java.text.NumberFormat
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

    /**
     * Compare phone media with Proton. Names are compared first; only
     * same-named files are read and hashed. Results are kept, so a check
     * that was interrupted continues where it stopped.
     */
    suspend fun check(context: Context, repository: GalleryRepository): Unit = checking.withLock {
        var start = repository.backupRemaining()
        if (start == 0L) {
            // Names that appeared in Proton since the last check are compared again.
            repository.checkBackup()
            start = repository.backupRemaining()
            if (start == 0L) return@withLock
        }
        mutableProgress.update { it.copy(checking = true, toCheck = start, checkTotal = start) }
        try {
            var more = true
            while (more) {
                val pending = repository.checkBackup()
                val left = repository.backupRemaining()
                mutableProgress.update { it.copy(toCheck = left, checkTotal = maxOf(it.checkTotal, left)) }
                val hashes = if (pending.isEmpty()) emptyList()
                    else hashAll(context, pending) { mutableProgress.update { it.copy(toCheck = (it.toCheck - 1).coerceAtLeast(0)) } }
                // Unreadable files stay unchecked until the next scan drops them.
                more = hashes.isNotEmpty()
                if (more) repository.setPhoneHashes(hashes)
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
     * upload everything missing from the backup folders. Returns how many
     * uploads failed.
     */
    suspend fun run(context: Context, items: List<PhoneRef>?): Int {
        val repository = GalleryRepository.get(context)
        try {
            repository.ensureSession()
            if (repository.accountStatus().stage != SignInStage.CONNECTED) {
                if (items != null) mutableProgress.update { it.copy(message = "Sign in to Proton to back up") }
                return 0
            }
            val queue = if (items == null) {
                PhoneLibrary.scan(context, repository)
                check(context, repository)
                repository.backupQueue(5_000)
            } else items
            if (queue.isEmpty()) return 0
            return upload(context, repository, queue)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            android.util.Log.w("ProtonGallery", "Backup stopped", error)
            val message = (error as? GalleryException.Operation)?.detail ?: "Backup stopped. It will try again later."
            mutableProgress.update { it.copy(message = message) }
            throw error
        }
    }

    private suspend fun upload(context: Context, repository: GalleryRepository, queue: List<PhoneRef>): Int {
        runs.incrementAndGet()
        mutableProgress.update { it.copy(uploading = true, total = it.total + queue.size, message = null) }
        var failed = 0
        try {
            for (item in queue) {
                val error = uploading.withLock {
                    try {
                        uploadOne(context, repository, item) { sent, size -> mutableProgress.update { it.copy(bytes = sent, size = size) } }
                        null
                    } catch (cancel: CancellationException) { throw cancel }
                    catch (error: Exception) {
                        android.util.Log.w("ProtonGallery", "Backup of one item failed", error)
                        error
                    }
                }
                if (error != null) failed++
                mutableProgress.update {
                    it.copy(done = it.done + 1, failed = it.failed + if (error != null) 1 else 0, bytes = 0, size = 0,
                        message = if (error != null) describe(error) else it.message)
                }
            }
        } finally {
            if (runs.decrementAndGet() == 0) mutableProgress.update {
                val sent = it.done - it.failed
                it.copy(uploading = false, done = 0, total = 0, failed = 0, bytes = 0, size = 0, message = when {
                    it.failed > 0 -> "${if (it.failed == 1) "1 item" else "${it.failed} items"} couldn’t be backed up. ${it.message ?: ""}".trim()
                    sent == 1 -> "1 item backed up"
                    sent > 1 -> "$sent items backed up"
                    else -> it.message
                })
            }
        }
        return failed
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
        val failed = try {
            PhoneBackup.run(applicationContext, items)
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { return@coroutineScope Result.retry() }
        finally { notification.cancel() }
        if (items == null) Backup.watchNewMedia(applicationContext)
        if (failed > 0 && runAttemptCount < 3) Result.retry() else Result.success()
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

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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit

/** What the backup is doing, for the screen and the notification. */
data class BackupProgress(
    val running: Boolean = false,
    val checking: Boolean = false,
    val done: Int = 0,
    val total: Int = 0,
    val failed: Int = 0,
    /** Bytes of the current file sent so far, and its size. */
    val bytes: Long = 0,
    val size: Long = 0,
    val message: String? = null,
)

/** User choices for automatic backup. Folders live in the catalog. */
class BackupSettings(context: Context) {
    private val preferences = context.getSharedPreferences("backup", Context.MODE_PRIVATE)
    var wifiOnly: Boolean
        get() = preferences.getBoolean("wifi-only", true)
        set(value) = preferences.edit { putBoolean("wifi-only", value) }
    var chargingOnly: Boolean
        get() = preferences.getBoolean("charging-only", false)
        set(value) = preferences.edit { putBoolean("charging-only", value) }
    /** Whether any folder backs up automatically. */
    var automatic: Boolean
        get() = preferences.getBoolean("automatic", false)
        set(value) = preferences.edit { putBoolean("automatic", value) }
}

/**
 * Compares the phone with Proton Photos and uploads what is missing. One run at
 * a time per process; the screens and background work share the engine.
 */
object PhoneBackup {
    private val mutableProgress = MutableStateFlow(BackupProgress())
    val progress = mutableProgress.asStateFlow()
    private val running = Mutex()

    /** Compare phone media with Proton. Only same-named files are hashed. */
    suspend fun check(context: Context, repository: GalleryRepository) {
        repeat(10_000) {
            val pending = repository.checkBackup()
            if (pending.isEmpty()) return
            val hashes = pending.mapNotNull { item ->
                try { PhoneHash(item.id, PhoneLibrary.sha1(context, PhoneLibrary.uri(item.id, item.isVideo))) }
                catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { null }
            }
            // Unreadable files stay unchecked until the next scan drops them.
            if (hashes.isEmpty()) return
            repository.setPhoneHashes(hashes)
        }
    }

    /**
     * Scan, compare, then upload `items`, or everything missing from the
     * backup folders when `items` is null. Returns how many uploads failed.
     */
    suspend fun run(context: Context, items: List<PhoneRef>?, onProgress: suspend (BackupProgress) -> Unit = {}): Int = running.withLock {
        val repository = GalleryRepository.get(context)
        try {
            repository.ensureSession()
            if (repository.accountStatus().stage != SignInStage.CONNECTED) return 0
            publish(BackupProgress(running = true, checking = true), onProgress)
            PhoneLibrary.scan(context, repository)
            check(context, repository)
            val queue = items ?: repository.backupQueue(5_000)
            var state = BackupProgress(running = true, total = queue.size)
            publish(state, onProgress)
            for (item in queue) {
                state = try {
                    upload(context, repository, item) { sent, size -> mutableProgress.update { it.copy(bytes = sent, size = size) } }
                    state.copy(done = state.done + 1, bytes = 0, size = 0)
                } catch (cancel: CancellationException) { throw cancel }
                catch (error: Exception) {
                    android.util.Log.w("ProtonGallery", "Backup of one item failed", error)
                    state.copy(done = state.done + 1, failed = state.failed + 1, message = (error as? GalleryException.Operation)?.detail)
                }
                publish(state, onProgress)
            }
            publish(state.copy(running = false), onProgress)
            state.failed
        } catch (cancel: CancellationException) {
            mutableProgress.update { it.copy(running = false, checking = false) }
            throw cancel
        } catch (error: Exception) {
            val message = (error as? GalleryException.Operation)?.detail ?: "Backup stopped. It will try again later."
            mutableProgress.value = BackupProgress(message = message)
            throw error
        }
    }

    private suspend fun publish(state: BackupProgress, onProgress: suspend (BackupProgress) -> Unit) {
        mutableProgress.value = state
        onProgress(state)
    }

    private suspend fun upload(context: Context, repository: GalleryRepository, item: PhoneRef, onBytes: (Long, Long) -> Unit): UploadOutcome {
        val uri = PhoneLibrary.uri(item.id, item.isVideo)
        val sha1 = PhoneLibrary.sha1(context, uri)
        val thumbnail = PhoneLibrary.uploadThumbnail(context, uri)
        val size = context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1
        val input = context.contentResolver.openInputStream(uri) ?: throw IOException("The file couldn’t be opened.")
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
    override suspend fun doWork(): Result {
        val items = inputData.getNullableStringArray(ITEMS)?.mapNotNull { it?.toRef() }
        runCatching { setForeground(foreground(BackupProgress(running = true, checking = true))) }
        val failed = try {
            PhoneBackup.run(applicationContext, items) { progress ->
                runCatching { setForeground(foreground(progress)) }
            }
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { return Result.retry() }
        if (items == null) Backup.watchNewMedia(applicationContext)
        return if (failed > 0 && runAttemptCount < 3) Result.retry() else Result.success()
    }

    override suspend fun getForegroundInfo() = foreground(BackupProgress(running = true, checking = true))

    private fun foreground(progress: BackupProgress): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Photo backup", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(applicationContext, 0,
            Intent(applicationContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(if (progress.checking) "Checking your photos" else "Backing up to Proton Photos")
            .setContentText(if (progress.checking) "Comparing this phone with Proton Photos" else "${progress.done} of ${progress.total}")
            .setProgress(progress.total, progress.done, progress.checking)
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

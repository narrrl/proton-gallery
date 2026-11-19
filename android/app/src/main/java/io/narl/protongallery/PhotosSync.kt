package io.narl.protongallery

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.narl.protongallery.bridge.GalleryException
import io.narl.protongallery.bridge.PhotosSyncStatus
import io.narl.protongallery.bridge.SignInStage
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal object PhotosSync {
    private const val Periodic = "proton-photos-periodic"
    private const val Retry = "proton-photos-retry"
    private const val Tag = "proton-photos-sync"
    private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Mutex()
    private var active: Deferred<Outcome>? = null
    private var visibility: Job? = null
    private var repairPending = false
    private var stopped = false
    private var epoch = 0L
    private val mutableState = MutableStateFlow(State())
    val state = mutableState.asStateFlow()

    data class State(
        val revision: ULong? = null,
        val lastSuccess: Long? = null,
        val running: Boolean = false,
        val indexed: Long = 0,
        val message: String? = null,
        val expired: Boolean = false,
    )

    enum class Outcome { Complete, More, Retry }

    suspend fun schedule(context: Context) {
        lock.withLock { stopped = false }
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(Periodic, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<PhotosSyncWorker>(15, TimeUnit.MINUTES)
                .setInitialDelay(15, TimeUnit.MINUTES).setConstraints(network).addTag(Tag)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
    }

    fun retry(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(Retry, ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<PhotosSyncWorker>()
                .setInitialDelay(30, TimeUnit.SECONDS).setConstraints(network).addTag(Tag)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
    }

    suspend fun stop(context: Context) {
        WorkManager.getInstance(context).cancelAllWorkByTag(Tag)
        lock.withLock {
            epoch++
            stopped = true
            repairPending = false
            active = null
            visibility?.cancel()
            visibility = null
            mutableState.value = State()
        }
    }

    /** One process-wide native slice. A stopped worker does not start another slice. */
    suspend fun check(context: Context, repair: Boolean = false): Outcome {
        val app = context.applicationContext
        val (request, generation) = lock.withLock {
            if (stopped) return Outcome.Complete
            repairPending = repairPending || repair
            (active?.takeUnless { it.isCompleted } ?: startSlice(app)) to epoch
        }
        val outcome = request.await()
        // A repair requested during a running slice still gets queued.
        return lock.withLock {
            when {
                epoch != generation -> Outcome.Complete
                repairPending -> Outcome.More
                else -> outcome
            }
        }
    }

    private fun startSlice(context: Context): Deferred<Outcome> {
        val generation = epoch
        val repair = repairPending
        repairPending = false
        return scope.async {
            val repo = GalleryRepository.get(context)
            repo.ensureSession()
            if (repo.accountStatus().stage != SignInStage.CONNECTED || mutableState.value.expired)
                return@async Outcome.Complete
            if (!repo.galleryVisibilityReady()) ensureVisibility(context, repo, generation)
            update(generation) { it.copy(running = true, indexed = 0) }
            val monitor = launch {
                while (true) {
                    try {
                        publish(context, generation, repo.photosSyncStatus(), repo.syncProgress().toLong())
                    } catch (cancel: CancellationException) { throw cancel }
                    catch (_: Exception) {}
                    delay(750)
                }
            }
            try {
                val status = repo.syncPhotos(repair)
                monitor.cancelAndJoin()
                val indexed = repo.syncProgress().toLong()
                publish(context, generation, status, indexed)
                Log.println(Log.INFO, "ProtonGallery", "Photos sync: indexed=$indexed, more=${status.more}, retry=${status.retry}")
                update(generation) {
                    it.copy(message = when {
                        status.more -> it.message
                        status.retry -> "Some Photos changes are still pending. Your cached library is available."
                        else -> null
                    })
                }
                when {
                    status.more -> Outcome.More
                    status.retry -> Outcome.Retry
                    else -> Outcome.Complete
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (expired: GalleryException.SessionExpired) {
                update(generation) { it.copy(message = expired.message, expired = true) }
                Outcome.Complete
            } catch (error: Exception) {
                Log.w("ProtonGallery", "Photos sync failed: ${error.javaClass.simpleName}")
                update(generation) { it.copy(message = "Couldn’t refresh Proton Photos. Your cached library is available.") }
                Outcome.Retry
            } finally {
                monitor.cancelAndJoin()
                try {
                    publish(context, generation, repo.photosSyncStatus(), repo.syncProgress().toLong())
                } catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) {}
                update(generation) { it.copy(running = false) }
            }
        }.also { active = it }
    }

    private suspend fun ensureVisibility(context: Context, repo: GalleryRepository, generation: Long) {
        lock.withLock {
            if (epoch != generation || visibility?.isActive == true) return
            // A worker can be the first process to open a signed-in account.
            visibility = scope.launch {
                try {
                    repo.syncGallerySettings()
                    PhotoPicker.changed(context)
                } catch (cancel: CancellationException) { throw cancel }
                catch (error: Exception) {
                    Log.d("ProtonGallery", "Photos visibility is pending: ${error.javaClass.simpleName}")
                }
            }
        }
    }

    private suspend fun publish(context: Context, generation: Long, status: PhotosSyncStatus, indexed: Long) {
        val changed = lock.withLock {
            if (epoch != generation) return
            val previous = mutableState.value
            mutableState.value = previous.copy(revision = status.revision, lastSuccess = status.lastSuccess, indexed = indexed)
            previous.revision != status.revision && (previous.revision != null || status.revision != 0uL)
        }
        if (changed) PhotoPicker.changed(context)
    }

    private suspend fun update(generation: Long, change: (State) -> State) = lock.withLock {
        if (generation == epoch) mutableState.value = change(mutableState.value)
    }
}

class PhotosSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        return try {
            // A persisted queue may span several workers on very large libraries.
            repeat(12) {
                when (PhotosSync.check(applicationContext)) {
                    PhotosSync.Outcome.Complete -> return Result.success()
                    PhotosSync.Outcome.Retry -> return Result.retry()
                    PhotosSync.Outcome.More -> Unit
                }
            }
            Result.retry()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            Log.w("ProtonGallery", "Scheduled Photos check failed: ${error.javaClass.simpleName}")
            Result.retry()
        }
    }
}

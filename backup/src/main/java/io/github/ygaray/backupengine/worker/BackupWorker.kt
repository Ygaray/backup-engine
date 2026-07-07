package io.github.ygaray.backupengine.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.github.ygaray.backupengine.BackupRepository
import io.github.ygaray.backupengine.ScheduledOutcome
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * The background scheduled-backup worker (SCH-01/02/05, D-07b, D-08a).
 *
 * A PLAIN [CoroutineWorker] — deliberately NOT `@HiltWorker` (D-08a: the `:backup` engine carries no
 * `androidx.hilt:hilt-work` dependency). It resolves its single dependency, the [BackupRepository],
 * from the app's `SingletonComponent` at run time via [EntryPointAccessors] + the
 * [BackupWorkerEntryPoint] below. The OS wakes it with no foreground context and no user present, so
 * everything it needs must be resolvable from the application graph alone.
 *
 * [doWork] reads the destination tag from [androidx.work.Data], calls
 * [BackupRepository.runScheduledBackup] (which reuses the verified `backup()` / `backupToDrive()`
 * flows + prune + status recording), and maps the typed [ScheduledOutcome] onto a WorkManager
 * `Result` per the D-07b table:
 *  - `Success`    → `success()`
 *  - `NeedsReauth`→ terminal `failure()` (no retry can fix a revoked grant; the persisted `needs_reauth`
 *    flag — set inside `runScheduledBackup` — drives the `:app` reconnect surface)
 *  - `NoNetwork`  → `retry()` (WorkManager's exponential backoff)
 *  - `Transient`  → bounded `retry()` while `runAttemptCount < MAX_ATTEMPTS`, else terminal `failure()`
 *    (no infinite battery-draining loop, T-18-04)
 *
 * A missing / blank destination is a programming error in the enqueuer → terminal `failure()`.
 *
 * NO `NotificationManager` / `POST_NOTIFICATIONS` here — the engine only sets the persisted
 * `needs_reauth` flag; the `:app` side (18-03) observes it and posts the reconnect notification
 * (Open-Q1 resolution, D-07). Keeping notification machinery out of `:backup` preserves LIB-01.
 */
class BackupWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    /**
     * How [doWork] obtains its [BackupRepository]. Production resolves it from the app's
     * `SingletonComponent` via [EntryPointAccessors] (D-08a). Exposed as an overridable seam ONLY so
     * the JVM Result-mapping suite can drive [doWork] end-to-end with a fake repo through
     * `TestListenableWorkerBuilder`, without standing up a full Hilt test graph. Production never sets it.
     */
    @androidx.annotation.VisibleForTesting
    internal var repositoryProvider: () -> BackupRepository = {
        EntryPointAccessors
            .fromApplication(applicationContext, BackupWorkerEntryPoint::class.java)
            .backupRepository()
    }

    override suspend fun doWork(): Result {
        val destination = inputData.getString(KEY_DESTINATION)
        if (destination.isNullOrBlank()) return Result.failure()

        val repository = repositoryProvider()

        return when (repository.runScheduledBackup(destination)) {
            ScheduledOutcome.Success -> Result.success()
            // A revoked grant is terminal — retrying can't re-authorize; the persisted needs_reauth
            // flag (set in runScheduledBackup) drives the :app reconnect surface instead (D-07b).
            ScheduledOutcome.NeedsReauth -> Result.failure()
            // No network → let WorkManager back off and retry when connectivity returns (D-07b).
            ScheduledOutcome.NoNetwork -> Result.retry()
            // Any other retriable failure: bounded retry, then give up (T-18-04, D-07b).
            ScheduledOutcome.Transient ->
                if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
        }
    }

    companion object {
        /** Input-data key carrying the destination tag (BackupRepository.DESTINATION_LOCAL/DRIVE). */
        const val KEY_DESTINATION = "destination"

        /** Bounded transient-retry ceiling (D-07b, Claude's discretion → ≤3). */
        const val MAX_ATTEMPTS = 3
    }
}

/**
 * The Hilt [EntryPoint] the plain [BackupWorker] resolves against (D-08a). Installed into
 * [SingletonComponent] so the OS-spawned worker can reach the application-scoped [BackupRepository]
 * without being Hilt-instantiated itself — the pattern for classes the framework constructs
 * (developer.android.com/training/dependency-injection/hilt-android#not-supported).
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface BackupWorkerEntryPoint {
    fun backupRepository(): BackupRepository
}

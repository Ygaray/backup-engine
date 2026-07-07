package io.github.ygaray.backupengine

import javax.inject.Inject
import javax.inject.Singleton

/**
 * The generalized scheduling seam of the `:backup` engine (SCH-01/02/03, D-04/D-08) — the widening of
 * the Phase-16 `ScheduledBackupCanceller`.
 *
 * It is UI-free and vendor-agnostic: it imports NO `androidx.work` type. The concrete WorkManager
 * implementation lives in `:app` (D-08 — the engine owns the plain `BackupWorker`, the host owns the
 * scheduling), so the engine stays reusable (LIB-01) and the JVM tests / non-scheduling hosts can bind
 * the [NoOpScheduledBackupScheduler].
 *
 * Two operations:
 *  - [reconcileSchedules] — enqueue / update / cancel the two per-destination periodic works to match
 *    the requested [BackupFrequency] pair (SCH-01/02/03). `Off` cancels that destination's work.
 *  - [cancelDriveBackups] — preserved VERBATIM from `ScheduledBackupCanceller` so the disconnect
 *    cascade (`BackupViewModel.disconnect()`) call site stays churn-free (D-04).
 */
interface ScheduledBackupScheduler {

    /**
     * Reconcile the two scheduled backups to the requested per-destination frequencies (SCH-01/02/03).
     * An `Off` frequency cancels that destination's periodic work; `Daily` / `Weekly` enqueue-or-update
     * it. Idempotent — calling it with the current state is a no-op on the WorkManager side.
     */
    suspend fun reconcileSchedules(local: BackupFrequency, drive: BackupFrequency)

    /** Cancel any scheduled Drive backups (the disconnect cascade, ACC-02 / D-04). Idempotent. */
    suspend fun cancelDriveBackups()
}

/**
 * The no-op [ScheduledBackupScheduler] for hosts without WorkManager scheduling (JVM tests, or a host
 * that does not schedule). Carries NO WorkManager dependency — both operations are no-ops. Phase 16's
 * disconnect mechanism test binds this; 18-02 rebinds the production graph to the `:app` WorkManager
 * implementation with zero call-site churn.
 */
@Singleton
class NoOpScheduledBackupScheduler @Inject constructor() : ScheduledBackupScheduler {
    override suspend fun reconcileSchedules(local: BackupFrequency, drive: BackupFrequency) {
        // No scheduling host bound — nothing to reconcile.
    }

    override suspend fun cancelDriveBackups() {
        // No scheduled Drive backups on the no-op host — nothing to cancel (D-04).
    }
}

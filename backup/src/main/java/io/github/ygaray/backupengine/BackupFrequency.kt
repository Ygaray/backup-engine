package io.github.ygaray.backupengine

/**
 * The generic, vendor-agnostic schedule vocabulary for scheduled backups (SCH-03, D-04).
 *
 * Deliberately framework-free — it carries NO `androidx.work` import and NO app knowledge — so it
 * lives cleanly in the reusable engine (LIB-01/LIB-02, destined for JitPack extraction in Phase 19).
 * It is the single frequency type consumed by BOTH the durable settings store
 * ([io.github.ygaray.backupengine.settings.BackupSettingsStore] frequency flows) and the scheduler seam
 * ([ScheduledBackupScheduler.reconcileSchedules]); the WorkManager scheduling impl in `:app` maps
 * these values onto concrete `PeriodicWorkRequest` intervals.
 *
 * Persisted by enum NAME (not ordinal) so reordering the constants can never corrupt a stored value.
 * Defaults to [Off] on an absent/corrupt persisted key (untrusted-input posture, T-15-08).
 */
enum class BackupFrequency {
    /** No scheduled backups — the default when the persisted key is absent or corrupt. */
    Off,

    /** A backup roughly once per day. */
    Daily,

    /** A backup roughly once per week. */
    Weekly,
}

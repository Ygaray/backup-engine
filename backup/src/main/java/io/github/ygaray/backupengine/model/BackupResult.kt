package io.github.ygaray.backupengine.model

/**
 * The fixed outcome of a backup or restore operation (LIB-02, RESEARCH Patterns 3 & 4).
 *
 * A [sealed interface] so callers can branch every variant in an exhaustive `when` with no `else`
 * (compile-time completeness). The engine returns this value instead of throwing across the
 * `:app` <-> `:backup` boundary; the consuming ViewModel `when`-maps [Failure.reason] to a FIXED
 * user-facing string, so raw exception text never reaches the UI (T-02-09 / D-06).
 */
sealed interface BackupResult {

    /** The operation completed successfully. */
    data object Success : BackupResult

    /** The operation was refused or failed; [reason] selects the fixed user-facing copy. */
    data class Failure(val reason: Reason) : BackupResult

    /**
     * The distinct, enumerable failure causes (RESEARCH Security threat table).
     *
     * - [SchemaTooNew]: the backup's schema version exceeds the app's [currentSchemaVersion]
     *   ceiling — restoring would corrupt a newer DB, so it is refused (guard ceiling).
     * - [NotAValidBackup]: the chosen file is not a readable/integrity-valid CalTracker DB.
     * - [WriteFailed]: the destination write (snapshot/copy) did not complete.
     * - [FolderUnavailable]: the persisted SAF destination is missing or no longer writable.
     */
    enum class Reason {
        SchemaTooNew,
        NotAValidBackup,
        WriteFailed,
        FolderUnavailable,
    }
}

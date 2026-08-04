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

    /**
     * The operation completed successfully.
     *
     * [pruneWarning] is an ADDITIVE, non-fatal signal (ENG-01 / D-01): a backup that was written and
     * verified stays a [Success] even when the post-success retention prune throws. The throw is
     * caught and surfaced as this fixed [PruneWarning] marker so the ViewModel can note that cleanup
     * failed — it NEVER flips the verified success to a [Failure] and NEVER carries any exception text
     * (T-15-11). Defaults to `null` (no prune problem), so every existing `Success()` construction and
     * every `is BackupResult.Success` when-branch is unaffected (semver-safe MINOR bump).
     *
     * [mediaWarning] is the media-archiving twin (ENGINE-01 / D-02): a DB backup that succeeded but
     * whose paired media archive failed stays a [Success], surfaced via this fixed [MediaWarning]
     * marker — same non-fatal, no-exception-text contract as [pruneWarning]. Defaults to `null`, so
     * every existing `Success()` construction and `is BackupResult.Success` when-branch is unaffected.
     */
    data class Success(val pruneWarning: PruneWarning? = null, val mediaWarning: MediaWarning? = null) : BackupResult

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

/**
 * A fixed, non-fatal marker that the post-success retention prune FAILED (ENG-01 / D-01 / T-15-11).
 *
 * Carried on a [BackupResult.Success] / [DriveBackupResult.Success] when a backup was written and
 * verified but the housekeeping prune afterward threw. It is deliberately a value-free `data object`:
 * it MUST NOT carry the prune exception, its message, or any wire/stack text — only the fact that
 * cleanup did not complete. The ViewModel maps its mere presence to fixed copy; nothing from the
 * exception ever reaches the UI (T-15-11, ASVS V7). Shared by both result types.
 */
data object PruneWarning

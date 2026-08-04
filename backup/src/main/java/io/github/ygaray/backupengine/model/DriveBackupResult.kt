package io.github.ygaray.backupengine.model

/**
 * The fixed outcome of a Drive BACKUP operation (BAK-03, RESEARCH § HTTP status → mapping L413-425).
 *
 * A twin of [BackupResult] for the Drive destination, but carrying finer resolution than the 4-value
 * [BackupResult.Reason] can express: the Drive path must distinguish a **401 re-auth** ([Reason.NeedsReauth])
 * from a **no-network** ([Reason.NoNetwork]) from a **verify mismatch** ([Reason.VerifyFailed]) from a
 * generic HTTP/quota failure ([Reason.Failed]) so the ViewModel picks the exact fixed Snackbar copy
 * (RESEARCH L425 — thread the richer error through the VM, not into a new UI string).
 *
 * A [sealed interface] so callers branch every variant in an exhaustive `when` with no `else`. The
 * repository returns this instead of throwing across the `:app` <-> `:backup` boundary; nothing from
 * the wire (token / body / `resp.message`) is ever carried in it (T-17-06 / T-02-09).
 *
 * RESTORE reuses the shared [BackupResult] from the unchanged [io.github.ygaray.backupengine.BackupRepository.restore]
 * engine (D-04b) — this richer type is used by the Drive BACKUP entry point only.
 */
sealed interface DriveBackupResult {

    /**
     * The Drive backup completed and was md5/size-verified.
     *
     * [pruneWarning] mirrors [BackupResult.Success.pruneWarning] (ENG-01 / D-01): a verified Drive
     * backup stays a [Success] even when the post-success retention prune throws — the throw is caught
     * and surfaced as this fixed [PruneWarning] marker, never a [Failure], never any exception text
     * (T-15-11). Defaults to `null`, so every existing `Success()` construction and `is
     * DriveBackupResult.Success` when-branch is unaffected (semver-safe MINOR bump).
     *
     * [mediaWarning] mirrors [BackupResult.Success.mediaWarning] (ENGINE-01 / D-02): a verified Drive
     * DB backup whose paired media archive failed stays a [Success], surfaced via this fixed
     * [MediaWarning] marker — same non-fatal, no-exception-text contract. Defaults to `null`, so
     * every existing `Success()` construction and `is DriveBackupResult.Success` when-branch is
     * unaffected.
     */
    data class Success(val pruneWarning: PruneWarning? = null, val mediaWarning: MediaWarning? = null) : DriveBackupResult

    /** The Drive backup was refused or failed; [reason] selects the fixed user-facing copy. */
    data class Failure(val reason: Reason) : DriveBackupResult

    /**
     * The distinct, enumerable Drive-backup failure causes (RESEARCH § HTTP status → mapping).
     *
     * - [NeedsReauth]: HTTP 401 / no token — the grant expired or is missing; the VM surfaces the
     *   "Reconnect your Google account and try again." re-auth copy.
     * - [NoNetwork]: a transport `IOException` (no network / DNS) — the VM surfaces the no-connection copy.
     * - [VerifyFailed]: the uploaded file's server md5/size did not match the local file (D-02a).
     * - [Failed]: a generic Drive failure (403 quota / 404 / 5xx / any other) — generic backup copy.
     */
    enum class Reason {
        NeedsReauth,
        NoNetwork,
        VerifyFailed,
        Failed,
    }
}

package io.github.ygaray.backupengine.drive

/**
 * The typed error surface of [DriveClient] — the sibling of `LocalBackupSource`'s
 * `FolderUnavailableException` for the Drive path (RESEARCH § HTTP status → mapping).
 *
 * **Information-disclosure posture (T-17-01 / T-17-02, Pitfall 5 / T-02-09):** the message is a
 * FIXED generic label derived from [kind] — it NEVER interpolates the bearer token, the HTTP
 * response body, `resp.message`, or any stack text. The [DriveClient] throws these; the upstream
 * `DriveBackupSource` / repository map [kind] to a `BackupResult.Reason` and the ViewModel picks the
 * fixed Snackbar copy. Nothing from the wire ever reaches the UI through this exception.
 *
 * The nested [Kind] deliberately carries slightly more resolution than the 4-value
 * `BackupResult.Reason` so the ViewModel can distinguish (e.g.) the 401 re-auth copy from a generic
 * failure and the no-network branch from an HTTP error.
 */
class DriveException private constructor(
    /** The fixed error category the caller maps to UI copy. Never any wire detail. */
    val kind: Kind,
) : Exception(kind.name) {

    enum class Kind {
        /** HTTP 401 — token expired/revoked at run time; caller surfaces re-auth copy. */
        Unauthorized,

        /** HTTP 403 — rate limit / quota (`userRateLimitExceeded`). */
        RateLimited,

        /** HTTP 404 — the folder / fileId is gone. */
        NotFound,

        /** HTTP 5xx — Drive server error. */
        Server,

        /** Transport failure (no network, DNS, connection refused) — an `IOException`. */
        Network,

        /** The uploaded file's server md5/size did not match the local file (D-02a). */
        VerifyMismatch,

        /**
         * A 2xx Drive response whose body could not be parsed (e.g. malformed/empty JSON) — the call
         * succeeded at the HTTP layer but the payload was unusable (ENG-03 / D-03). DISTINCT from
         * [io.github.ygaray.backupengine.model.BackupResult.Reason.NotAValidBackup], which is a
         * restore-side integrity verdict from `validate()`, not a Drive response-parse failure.
         */
        Malformed,
    }

    companion object {
        /**
         * Map a non-successful HTTP status [code] to the matching [Kind] (RESEARCH § HTTP status →
         * mapping, L413-425). Any non-401/403/404 code >= 400 maps to [Kind.Server] (covers 5xx and
         * any unexpected 4xx we don't special-case). The response body/message is intentionally NOT a
         * parameter — nothing from the wire is captured.
         */
        fun fromStatus(code: Int): DriveException = DriveException(
            when (code) {
                401 -> Kind.Unauthorized
                403 -> Kind.RateLimited
                404 -> Kind.NotFound
                else -> Kind.Server
            },
        )

        /** A transport-level (IOException) failure — no network / connection refused. */
        val Network: DriveException get() = DriveException(Kind.Network)

        /** An unauthenticated Drive call (defensive; the CTA is hidden when disconnected). */
        val Unauthorized: DriveException get() = DriveException(Kind.Unauthorized)

        /** The D-02a upload verification failed (md5 or size mismatch). */
        val VerifyMismatch: DriveException get() = DriveException(Kind.VerifyMismatch)

        /** A 2xx response with an unparseable body (ENG-03 / D-03) — parsed by [DriveClient]'s guard. */
        val Malformed: DriveException get() = DriveException(Kind.Malformed)
    }
}

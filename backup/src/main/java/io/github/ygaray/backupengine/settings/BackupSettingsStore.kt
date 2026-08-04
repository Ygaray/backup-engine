package io.github.ygaray.backupengine.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.ygaray.backupengine.BackupFrequency
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The durable status / SAF-URI / pending-restore surface of the `:backup` engine (BAK-05).
 *
 * This store persists the small set of runtime-state keys the backup flow needs across process
 * death and reboot: the user-picked SAF tree URI (BAK-01), the last-local-backup timestamp+result
 * (BAK-05 status surface), and the pending-restore hand-off (flag + staged path + safety-copy path)
 * the restore swap consumes.
 *
 * **Single-store rule (prohibition):** this reuses the existing `caltracker_prefs`
 * [DataStore] handed in via [io.github.ygaray.backupengine.BackupConfig.dataStore] — it never stands up a
 * second DataStore instance. The store is passed as a constructor param (Plan 04's Hilt module
 * provides `config.dataStore`), mirroring the app's `UnitSystemPrefs` idiom.
 *
 * **Untrusted-persisted-input posture (T-15-08):** every persisted read is treated as untrusted —
 * each read-flow wraps its parse in `runCatching { ... }.getOrDefault(default)` so a corrupt or
 * mistyped persisted value falls back to the default rather than crashing the status flow, the same
 * posture as `UnitSystemPrefs`.
 *
 * > Initializer caveat: the pre-Hilt `RestoreSwapInitializer` (Plan 04) CANNOT use this
 * > DataStore-backed store — it reads its pending marker from a plain file written before restart.
 * > This store is the post-Hilt, ViewModel-time status surface, not the cold-start read.
 *
 * **Phase 16 auth keys (D-01, ACC-01/ACC-03):** this store also holds the three NON-SECRET
 * Google-account keys — the connected [googleAccountEmail], the [googleAuthGranted] flag, and the
 * offline-disconnect [revokePending] flag. The OAuth access token is **NEVER persisted here** (D-01):
 * once the `drive.file` scope is granted, Play services caches/refreshes the token and the host
 * re-obtains it silently each session via `AuthorizationClient`. This makes ACC-02 SC#2 ("no token
 * material in the store") pass **by construction** — there is no token key to find.
 */
class BackupSettingsStore(
    private val dataStore: DataStore<Preferences>,
) {

    /** The persisted SAF tree URI string, or `null` when no folder has been picked yet (BAK-01). */
    val localSafTreeUri: Flow<String?> = dataStore.data.map { prefs ->
        // Persisted strings are untrusted (T-15-08) — never let a read throw.
        runCatching { prefs[LOCAL_SAF_TREE_URI] }.getOrDefault(null)
    }

    /** Persist the picked SAF tree URI string (BAK-01 persistence half). */
    suspend fun setLocalSafTreeUri(uri: String) {
        dataStore.edit { it[LOCAL_SAF_TREE_URI] = uri }
    }

    /**
     * The last local-backup status for the BAK-05 surface. Reads as [LocalBackupStatus.NeverBackedUp]
     * when no backup has been recorded, and falls back to that default if the persisted values are
     * corrupt (T-15-08) — never a crash.
     */
    val lastLocalBackupStatus: Flow<LocalBackupStatus> = dataStore.data.map { prefs ->
        runCatching {
            val at = prefs[LAST_LOCAL_BACKUP_AT]
            val ok = prefs[LAST_LOCAL_BACKUP_OK]
            when {
                at == null || ok == null -> LocalBackupStatus.NeverBackedUp
                ok -> LocalBackupStatus.Success(at)
                else -> LocalBackupStatus.Failure(at)
            }
        }.getOrDefault(LocalBackupStatus.NeverBackedUp)
    }

    /** Record the outcome of a local backup: its wall-clock [timestampMillis] and [ok] flag (BAK-05). */
    suspend fun recordLocalBackupResult(timestampMillis: Long, ok: Boolean) {
        dataStore.edit {
            it[LAST_LOCAL_BACKUP_AT] = timestampMillis
            it[LAST_LOCAL_BACKUP_OK] = ok
        }
    }

    /**
     * The last Drive-backup status for the per-destination BAK-05 surface (Phase 17). Mirrors
     * [lastLocalBackupStatus] exactly: reads as [DriveBackupStatus.NeverBackedUp] when no Drive
     * backup has been recorded, and falls back to that default if the persisted values are corrupt
     * (T-15-08 / T-17-04) — never a crash. Uses its OWN keys in the SAME `caltracker_prefs` store as
     * the local status; there is no second DataStore and no interference between the two surfaces.
     */
    val lastDriveBackupStatus: Flow<DriveBackupStatus> = dataStore.data.map { prefs ->
        runCatching {
            val at = prefs[LAST_DRIVE_BACKUP_AT]
            val ok = prefs[LAST_DRIVE_BACKUP_OK]
            when {
                at == null || ok == null -> DriveBackupStatus.NeverBackedUp
                ok -> DriveBackupStatus.Success(at)
                else -> DriveBackupStatus.Failure(at)
            }
        }.getOrDefault(DriveBackupStatus.NeverBackedUp)
    }

    /** Record the outcome of a Drive backup: its wall-clock [timestampMillis] and [ok] flag (BAK-05 → Drive). */
    suspend fun recordDriveBackupResult(timestampMillis: Long, ok: Boolean) {
        dataStore.edit {
            it[LAST_DRIVE_BACKUP_AT] = timestampMillis
            it[LAST_DRIVE_BACKUP_OK] = ok
        }
    }

    /** True when a restore has been staged and is awaiting the cold-start swap. Corrupt → false. */
    val restorePending: Flow<Boolean> = dataStore.data.map { prefs ->
        runCatching { prefs[RESTORE_PENDING] ?: false }.getOrDefault(false)
    }

    /** The app-private path of the validated backup staged for the swap, or `null`. */
    val restoreStagedPath: Flow<String?> = dataStore.data.map { prefs ->
        runCatching { prefs[RESTORE_STAGED_PATH] }.getOrDefault(null)
    }

    /** The app-private path of the pre-swap safety copy of the live DB, or `null`. */
    val restoreSafetyPath: Flow<String?> = dataStore.data.map { prefs ->
        runCatching { prefs[RESTORE_SAFETY_PATH] }.getOrDefault(null)
    }

    /**
     * Arm a pending restore: mark the flag and record the [stagedPath] (validated backup ready to
     * swap in) and [safetyPath] (pre-swap copy of the live DB for rollback), written atomically.
     */
    suspend fun setPendingRestore(stagedPath: String, safetyPath: String) {
        dataStore.edit {
            it[RESTORE_PENDING] = true
            it[RESTORE_STAGED_PATH] = stagedPath
            it[RESTORE_SAFETY_PATH] = safetyPath
        }
    }

    /** Clear the pending-restore hand-off once the swap has completed (or been abandoned). */
    suspend fun clearPendingRestore() {
        dataStore.edit {
            it.remove(RESTORE_PENDING)
            it.remove(RESTORE_STAGED_PATH)
            it.remove(RESTORE_SAFETY_PATH)
        }
    }

    // ---- Phase 16: Google-account connect state (D-01 — NON-SECRET only; NO token key) ----

    /** The connected Google account email for the ACC-03 surface, or `null` when disconnected. Corrupt → null. */
    val googleAccountEmail: Flow<String?> = dataStore.data.map { prefs ->
        runCatching { prefs[GOOGLE_ACCOUNT_EMAIL] }.getOrDefault(null)
    }

    /** True once the `drive.file` scope has been granted (ACC-01). Corrupt/unset → false (T-15-08). */
    val googleAuthGranted: Flow<Boolean> = dataStore.data.map { prefs ->
        runCatching { prefs[GOOGLE_AUTH_GRANTED] ?: false }.getOrDefault(false)
    }

    /**
     * True when a disconnect cleared local state offline and a server-side revoke is still owed (D-03).
     * The offline disconnect path sets this; it is cleared on the next successful [setConnected]
     * (simplest robust scheme — full retry-on-launch replay is deferred to Phase 18). Corrupt → false.
     */
    val revokePending: Flow<Boolean> = dataStore.data.map { prefs ->
        runCatching { prefs[REVOKE_PENDING] ?: false }.getOrDefault(false)
    }

    /**
     * The email of an in-flight connect awaiting the interactive consent gesture, or `null` (WR-01).
     *
     * The identity step (Credential Manager) resolves the email BEFORE the `AuthorizationClient` consent
     * `PendingIntent` is launched; the system may kill the app process while that consent Activity is
     * foregrounded. Persisting this NON-SECRET value (consistent with D-01 — it is an email, not a token)
     * lets `onConsentResult` recover the identity after process death instead of failing a grant that
     * actually succeeded. Corrupt → null. Cleared on connect success or disconnect.
     */
    val pendingGoogleEmail: Flow<String?> = dataStore.data.map { prefs ->
        runCatching { prefs[PENDING_GOOGLE_EMAIL] }.getOrDefault(null)
    }

    /** Stash the in-flight consent email so it survives process death during consent (WR-01). */
    suspend fun setPendingGoogleEmail(email: String) {
        dataStore.edit { it[PENDING_GOOGLE_EMAIL] = email }
    }

    /**
     * Record a successful connect (ACC-01): persist the [email] + set the granted flag, and clear any
     * outstanding [REVOKE_PENDING] — a fresh grant supersedes a still-owed offline revoke (D-03) — plus
     * the now-consumed [PENDING_GOOGLE_EMAIL] (WR-01). Written atomically. NO token is persisted (D-01).
     */
    suspend fun setConnected(email: String) {
        dataStore.edit {
            it[GOOGLE_ACCOUNT_EMAIL] = email
            it[GOOGLE_AUTH_GRANTED] = true
            it.remove(REVOKE_PENDING)
            it.remove(PENDING_GOOGLE_EMAIL)
            // Phase 18 (D-07c): a fresh grant supersedes any stale needs-reauth flag.
            it.remove(NEEDS_REAUTH)
        }
    }

    /** Clear the connected account (ACC-02): remove the email + granted + any pending-consent email. */
    suspend fun clearConnected() {
        dataStore.edit {
            it.remove(GOOGLE_ACCOUNT_EMAIL)
            it.remove(GOOGLE_AUTH_GRANTED)
            it.remove(PENDING_GOOGLE_EMAIL)
            // Phase 18 (D-07c): a disconnected account has no grant to re-auth — clear the flag.
            it.remove(NEEDS_REAUTH)
        }
    }

    /**
     * Offline-disconnect (D-03 / D-05b): clear the connected account AND arm the owed server-side
     * revoke in ONE atomic [DataStore.edit] — the exact inverse of [setConnected]. Doing both in a
     * single transactional edit is the whole point (AUTH-05, T-22-06): a half-cleared state (email
     * gone but grant still true, or grant cleared but revoke never armed) would strand a still-
     * authorized server grant that the app believes is disconnected. Clears GOOGLE_ACCOUNT_EMAIL,
     * GOOGLE_AUTH_GRANTED, PENDING_GOOGLE_EMAIL, NEEDS_REAUTH and sets REVOKE_PENDING = true.
     *
     * Library-native (resolved Option A): this makes `:backup` the single owner of the auth keys so
     * plan 22-06 can delete the app-side `AuthLocalStateStore` entirely.
     */
    suspend fun clearConnectedAndOweRevoke() {
        dataStore.edit {
            it.remove(GOOGLE_ACCOUNT_EMAIL)
            it.remove(GOOGLE_AUTH_GRANTED)
            it.remove(PENDING_GOOGLE_EMAIL)
            it.remove(NEEDS_REAUTH)
            it[REVOKE_PENDING] = true
        }
    }

    /**
     * Clear only the in-flight consent email (WR-01) — the inverse of [setPendingGoogleEmail], in a
     * single [DataStore.edit]. Library-native (resolved Option A) so the app can delete its own
     * pending-email state in plan 22-06.
     */
    suspend fun clearPendingGoogleEmail() {
        dataStore.edit { it.remove(PENDING_GOOGLE_EMAIL) }
    }

    /** Arm/disarm the offline "revoke pending" flag (D-03 offline disconnect path). */
    suspend fun setRevokePending(pending: Boolean) {
        dataStore.edit {
            if (pending) it[REVOKE_PENDING] = true else it.remove(REVOKE_PENDING)
        }
    }

    // ---- Phase 18: scheduled-backup frequency (SCH-03, D-04) + needs-reauth flag (SCH-05, D-07) ----

    /**
     * The scheduled-backup frequency for the LOCAL destination (SCH-03, D-04). Persisted by enum NAME
     * (never ordinal) so reordering [BackupFrequency] can't corrupt a stored value; an absent OR
     * corrupt/unknown persisted value falls back to [BackupFrequency.Off] (untrusted-input posture,
     * T-15-08 / D-04). Independent of the Drive frequency — they use distinct keys in the same store.
     */
    val localBackupFrequency: Flow<BackupFrequency> = dataStore.data.map { prefs ->
        runCatching { BackupFrequency.valueOf(prefs[LOCAL_BACKUP_FREQUENCY] ?: "Off") }
            .getOrDefault(BackupFrequency.Off)
    }

    /** The scheduled-backup frequency for the DRIVE destination (SCH-03, D-04). Corrupt/absent → Off. */
    val driveBackupFrequency: Flow<BackupFrequency> = dataStore.data.map { prefs ->
        runCatching { BackupFrequency.valueOf(prefs[DRIVE_BACKUP_FREQUENCY] ?: "Off") }
            .getOrDefault(BackupFrequency.Off)
    }

    /** Persist the LOCAL scheduled-backup frequency, storing the migration-safe enum name (SCH-03). */
    suspend fun setLocalBackupFrequency(freq: BackupFrequency) {
        dataStore.edit { it[LOCAL_BACKUP_FREQUENCY] = freq.name }
    }

    /** Persist the DRIVE scheduled-backup frequency, storing the migration-safe enum name (SCH-03). */
    suspend fun setDriveBackupFrequency(freq: BackupFrequency) {
        dataStore.edit { it[DRIVE_BACKUP_FREQUENCY] = freq.name }
    }

    /**
     * True when a scheduled Drive backup hit a 401 / null-token outcome and the grant must be
     * re-authorized (SCH-05, D-07a). Mirrors [revokePending] exactly: corrupt/absent → false
     * (T-15-08). Set ONLY on the typed `NeedsReauth` outcome in
     * [io.github.ygaray.backupengine.BackupRepository.runScheduledBackup] — never on a no-network / generic
     * failure — and cleared on a successful Drive backup as well as on connect / disconnect (D-07c).
     * The `:app` side observes this flag to post the reconnect notification (Open-Q1: no
     * NotificationManager in `:backup`).
     */
    val needsReauth: Flow<Boolean> = dataStore.data.map { prefs ->
        runCatching { prefs[NEEDS_REAUTH] ?: false }.getOrDefault(false)
    }

    /** Arm/disarm the needs-reauth flag (SCH-05, D-07a). Mirrors [setRevokePending]. */
    suspend fun setNeedsReauth(needsReauth: Boolean) {
        dataStore.edit {
            if (needsReauth) it[NEEDS_REAUTH] = true else it.remove(NEEDS_REAUTH)
        }
    }

    // ---- Phase 75 (ENGINE-01/02, D-01/D-02): media-restore-pending + media-backup-warning flags ----

    /**
     * True when a media restore has been staged and is awaiting reconcile. Mirrors [restorePending]
     * but is a fully INDEPENDENT flag (D-01) — a missing/failed media pairing never blocks or fails
     * the DB restore, and clearing this flag never touches [RESTORE_PENDING]. Corrupt/absent → false
     * (T-15-08). Deliberately has NO companion staged-path key (unlike [restorePending]'s
     * [restoreStagedPath]/[restoreSafetyPath]): the media-staged sibling paths are derivable from
     * `config.mediaDirectories` at reconcile time (post-Hilt, config is injected), whereas the DB's
     * pre-Hilt `RestoreSwapInitializer` has no `BackupConfig` available at all and must persist the
     * path.
     */
    val mediaRestorePending: Flow<Boolean> = dataStore.data.map { prefs ->
        runCatching { prefs[MEDIA_RESTORE_PENDING] ?: false }.getOrDefault(false)
    }

    /** Arm a pending media restore (D-01). No staged-path argument — see [mediaRestorePending] KDoc. */
    suspend fun setPendingMediaRestore() {
        dataStore.edit { it[MEDIA_RESTORE_PENDING] = true }
    }

    /** Clear the pending-media-restore flag once the media leg has reconciled (or been abandoned). */
    suspend fun clearPendingMediaRestore() {
        dataStore.edit { it.remove(MEDIA_RESTORE_PENDING) }
    }

    /**
     * True when a media archive failed to back up on an otherwise-successful backup and the warning
     * has not yet been dismissed (ENGINE-01, D-02). A durable twin of the in-memory
     * [io.github.ygaray.backupengine.model.MediaWarning] result marker — persisted so a SCHEDULED
     * (WorkManager) backup that drops media is never silent;
     * it surfaces on next app/backup-screen open regardless of manual vs. scheduled path. Mirrors
     * [needsReauth] exactly (boolean-only, no companion key). Corrupt/absent → false (T-15-08).
     */
    val mediaBackupWarning: Flow<Boolean> = dataStore.data.map { prefs ->
        runCatching { prefs[MEDIA_BACKUP_WARNING] ?: false }.getOrDefault(false)
    }

    /** Arm/disarm the media-backup-warning flag (ENGINE-01, D-02). Mirrors [setNeedsReauth]. */
    suspend fun setMediaBackupWarning(warning: Boolean) {
        dataStore.edit {
            if (warning) it[MEDIA_BACKUP_WARNING] = true else it.remove(MEDIA_BACKUP_WARNING)
        }
    }

    companion object {
        private val LOCAL_SAF_TREE_URI = stringPreferencesKey("local_saf_tree_uri")
        private val LAST_LOCAL_BACKUP_AT = longPreferencesKey("last_local_backup_at")
        private val LAST_LOCAL_BACKUP_OK = booleanPreferencesKey("last_local_backup_ok")
        // Phase 17 (BAK-05 → Drive): the per-destination Drive status keys, alongside the local
        // ones in the single caltracker_prefs store (single-store rule).
        private val LAST_DRIVE_BACKUP_AT = longPreferencesKey("last_drive_backup_at")
        private val LAST_DRIVE_BACKUP_OK = booleanPreferencesKey("last_drive_backup_ok")
        private val RESTORE_PENDING = booleanPreferencesKey("restore_pending")
        private val RESTORE_STAGED_PATH = stringPreferencesKey("restore_staged_path")
        private val RESTORE_SAFETY_PATH = stringPreferencesKey("restore_safety_path")

        // Phase 16 (D-01): non-secret Google-account state ONLY — deliberately NO token key.
        private val GOOGLE_ACCOUNT_EMAIL = stringPreferencesKey("google_account_email")
        private val GOOGLE_AUTH_GRANTED = booleanPreferencesKey("google_auth_granted")
        private val REVOKE_PENDING = booleanPreferencesKey("revoke_pending")

        // WR-01: the in-flight consent email, persisted so it survives process death during the
        // consent Activity. NON-SECRET (an email, not a token) — consistent with D-01.
        private val PENDING_GOOGLE_EMAIL = stringPreferencesKey("pending_google_email")

        // Phase 18 (SCH-03, D-04): per-destination scheduled-backup frequency, stored as the enum
        // NAME (migration-safe). (SCH-05, D-07): the needs-reauth flag driving the reconnect surface.
        private val LOCAL_BACKUP_FREQUENCY = stringPreferencesKey("local_backup_frequency")
        private val DRIVE_BACKUP_FREQUENCY = stringPreferencesKey("drive_backup_frequency")
        private val NEEDS_REAUTH = booleanPreferencesKey("needs_reauth")

        // Phase 75 (ENGINE-01/02, D-01/D-02): media-restore-pending + media-backup-warning flags.
        // Deliberately NO staged-path key alongside MEDIA_RESTORE_PENDING — see its KDoc.
        private val MEDIA_RESTORE_PENDING = booleanPreferencesKey("media_restore_pending")
        private val MEDIA_BACKUP_WARNING = booleanPreferencesKey("media_backup_warning")
    }
}

/**
 * The derived last-local-backup status for the BAK-05 status row. A small sealed value the ViewModel
 * maps to fixed user-facing copy — no raw prefs leak to the UI.
 */
sealed interface LocalBackupStatus {
    /** No local backup has ever been recorded. */
    data object NeverBackedUp : LocalBackupStatus

    /** The most recent local backup succeeded at [timestampMillis]. */
    data class Success(val timestampMillis: Long) : LocalBackupStatus

    /** The most recent local backup failed at [timestampMillis]. */
    data class Failure(val timestampMillis: Long) : LocalBackupStatus
}

/**
 * The derived last-Drive-backup status for the per-destination BAK-05 status row (Phase 17). A twin
 * of [LocalBackupStatus] — a small sealed value the ViewModel maps to fixed user-facing copy so no
 * raw prefs (and no Drive HTTP/exception text) ever leak to the UI (T-02-09 / T-17-02).
 */
sealed interface DriveBackupStatus {
    /** No Drive backup has ever been recorded. */
    data object NeverBackedUp : DriveBackupStatus

    /** The most recent Drive backup succeeded at [timestampMillis]. */
    data class Success(val timestampMillis: Long) : DriveBackupStatus

    /** The most recent Drive backup failed at [timestampMillis]. */
    data class Failure(val timestampMillis: Long) : DriveBackupStatus
}

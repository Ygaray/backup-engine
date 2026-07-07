package io.github.ygaray.backupengine

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import java.io.File

/**
 * The entire reuse surface the host app crosses into the isolated `:backup` engine (LIB-02).
 *
 * This seam is the ONLY type both modules share. `:app` supplies exactly one implementation and
 * binds it into Hilt; `:backup` consumes only this interface and never imports `com.caltracker.app`
 * (LIB-01, enforced by the one-directional build graph). Keeping the surface this small is what
 * makes the engine drop-in reusable in another app: a new host implements these six members and
 * gets the full backup/restore engine.
 *
 * Design mirrors the app's seam-interface idiom (e.g. `UnitSystemPrefsSeam`): a focused interface
 * whose implementation is fake-able in tests.
 */
interface BackupConfig {

    /** Filename prefix for produced backups, e.g. `"caltracker"`. */
    val appName: String

    /** The live Room database file the engine snapshots / restores (e.g. `caltracker.db`). */
    val databaseFile: File

    /**
     * The app's expected Room schema version — the guard ceiling. A backup whose embedded
     * `user_version` exceeds this is refused as [model.BackupResult.Reason.SchemaTooNew] so a
     * newer DB is never downgraded into an older app.
     */
    val currentSchemaVersion: Int

    /**
     * The existing `caltracker_prefs` DataStore, handed in by the host. The engine reuses THIS
     * instance for its settings (SAF URI, last-result, pending-restore flag) — it never stands up
     * a second store (prohibition).
     */
    val dataStore: DataStore<Preferences>

    /**
     * Relaunch the host app after a restore swap (LIBRARY-DESIGN L-6, WR-04). The default is a generic
     * launcher-intent relaunch that works for any app, so a host need not override it: start the
     * package's launch intent on a fresh task and hard-exit the current process so the cold start
     * picks up the swapped database file.
     *
     * **Only exits when a relaunch was actually scheduled (WR-04).** If `getLaunchIntentForPackage`
     * returns null (no launcher activity / unusual host), this returns `false` WITHOUT calling
     * `exit(0)` — blindly hard-killing with nothing scheduled to bring the app back would leave the
     * staged swap unapplied until a manual relaunch and strand the "reopening…" interstitial. The
     * caller ([BackupRepository.restore]) treats a `false` return as a [model.BackupResult.Reason.WriteFailed]
     * (staged but not applied) rather than reporting success.
     *
     * @return `true` when a relaunch was scheduled and the process is exiting; `false` when no launch
     *   intent was available (no exit, restart not performed). On the happy path `exit(0)` terminates
     *   the process, so control never actually returns to the caller.
     */
    fun restartApp(context: Context): Boolean {
        val intent = context.packageManager
            .getLaunchIntentForPackage(context.packageName)
            ?.apply {
                addFlags(
                    android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                        android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK,
                )
            }
            ?: return false // no launcher: let the caller decide, do NOT blindly kill the process
        context.startActivity(intent)
        Runtime.getRuntime().exit(0)
        return true // unreachable in production (exit terminates), keeps the contract explicit
    }

    /**
     * The Drive access token for the current grant, or `null` when disconnected / unavailable (D-02).
     *
     * The engine consumes only this token string — it never imports the auth vendor deps
     * (`credentials`, `play-services-auth`, `googleid`), which live in `:app`. Phase 17's
     * `DriveBackupSource` calls this to authenticate its Drive REST requests.
     *
     * The host re-obtains the token **silently** each session via `AuthorizationClient` once the
     * `drive.file` scope is granted (Play services owns the caching/refresh), so it is **NEVER
     * persisted** (D-01) — there is no token on disk. A `null` return means "not connected / no token
     * available"; the caller treats that as an unauthenticated Drive path rather than an error.
     *
     * Like [restartApp], this is a default-implemented member so a host without any Drive/auth
     * integration (or the JVM test fakes) need not override it — the default returns `null`
     * (disconnected). Hosts that support Drive override it to delegate to their `AuthManager`.
     */
    suspend fun driveAccessToken(): String? = null

    /**
     * Display name of the user-visible Drive backup folder, e.g. `"caltracker Backups"` (D-01a).
     *
     * GENERIC and config-driven — the `:backup` engine never hardcodes a CalTracker-specific folder
     * name (the module is destined for JitPack extraction in Phase 19, so nothing app-specific may be
     * baked in). The default derives from [appName]; a future host overrides only if it wants
     * different copy. Like [driveAccessToken] / [restartApp], this is a default-implemented member so
     * existing impls and the JVM test fakes keep compiling with no change.
     */
    val driveFolderName: String get() = "$appName Backups"

    /**
     * The private `appProperties` marker key that identifies THIS app's backup folder in Drive, e.g.
     * `"caltracker_backup_folder"` (D-01a, D-03).
     *
     * Find-or-create matches on this marker rather than the folder name, so the folder survives a
     * user-rename without spawning a duplicate. GENERIC and config-driven (derived from [appName]) for
     * the same extraction-reusability reason as [driveFolderName]; defaulted for non-breaking adoption.
     */
    val driveMarkerKey: String get() = "${appName}_backup_folder"

    /**
     * How many of the newest backups to KEEP per destination when retention prunes (SCH-04, D-01, LIB-02).
     *
     * GENERIC and config-driven — the `:backup` engine never hardcodes a CalTracker-specific count
     * (the module is destined for JitPack extraction in Phase 19). Defaulted to 5 (D-05) so existing
     * host impls and the JVM test fakes keep compiling with no change; a future host overrides only
     * if it wants a different retention window. The prune floor is enforced independently in
     * [BackupRepository.prune] (`require(keepN >= 1)`), so a host can never accidentally delete the
     * last good backup by lowering this.
     */
    val retentionCount: Int get() = 5
}

package io.github.ygaray.backupengine

import android.content.Context
import android.util.Log
import io.github.ygaray.backupengine.di.DriveSource
import io.github.ygaray.backupengine.drive.DriveException
import io.github.ygaray.backupengine.media.MediaArchiveManager
import io.github.ygaray.backupengine.model.BackupRef
import io.github.ygaray.backupengine.model.BackupResult
import io.github.ygaray.backupengine.model.DriveBackupResult
import io.github.ygaray.backupengine.model.MediaWarning
import io.github.ygaray.backupengine.model.PruneWarning
import io.github.ygaray.backupengine.settings.BackupSettingsStore
import io.github.ygaray.backupengine.source.BackupSource
import io.github.ygaray.backupengine.source.FolderUnavailableException
import io.github.ygaray.backupengine.startup.RestoreSwapInitializer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The single orchestration seam of the `:backup` engine — the one class both the ViewModel (Plan 06)
 * and a future scheduled Worker (Phase 18) call for BOTH flows (BAK-02, BAK-04, RST-01, RST-03).
 *
 * It composes the already-tested primitives — [DatabaseFileManager] (snapshot/validate/swap paths),
 * [BackupSource] (the local/Drive destination), and [BackupSettingsStore] (durable status + the
 * pending-restore hand-off) — into two suspend flows and returns a fixed [BackupResult]. It NEVER
 * throws across the `:app` <-> `:backup` boundary: every failure maps to a [BackupResult.Failure]
 * reason the ViewModel turns into fixed copy, so no exception text reaches the UI (T-15-11, D-06).
 *
 * Consumes ONLY the injected [BackupConfig] interface — no `com.caltracker.app` type crosses in
 * (LIB-01/LIB-02); the host binds the concrete config in Plan 05.
 */
@Singleton
open class BackupRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val config: BackupConfig,
    private val fileManager: DatabaseFileManager,
    private val source: BackupSource,
    @param:DriveSource private val driveSource: BackupSource,
    private val settings: BackupSettingsStore,
) {

    /**
     * The gated media snapshot/extract engine (ENGINE-01/02/03, D-05). Stateless (no constructor
     * dependencies), so it is constructed here directly rather than Hilt-injected — this keeps the
     * `BackupRepository` constructor shape UNCHANGED (every existing test/DI call site is unaffected).
     */
    private val mediaArchiveManager = MediaArchiveManager()

    /**
     * BACKUP flow (BAK-02, BAK-04): snapshot the live DB to a checkpointed single file, [BackupSource.put]
     * it into the destination under a collision-free timestamped name, verify, and record the result.
     *
     * Guarded end-to-end (the app's `SetupViewModel` guarded-emit pattern): ANY throwable — a source
     * write failure, a folder-unavailable, an I/O error — is caught, recorded as a failure, and mapped
     * to a fixed [BackupResult.Failure] reason. This method NEVER rethrows (T-15-11).
     */
    suspend fun backup(): BackupResult = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        // WR-02: hoist temp out of the try so a `finally` can always reclaim it — a failed source.put
        // must not leak a full-size snapshot-*.db in backup-temp.
        val temp = File(tempDir(), "snapshot-$now.db")
        // ENGINE-01 (D-02, D-05): hoisted alongside `temp` so the SAME `finally` always reclaims a
        // partial/leftover media snapshot — mirrors WR-02's exact reclaim discipline.
        val mediaTemp = File(tempDir(), "media-$now.zip")
        try {
            val snapped = fileManager.snapshot(config.databaseFile, temp)
            if (!snapped) {
                settings.recordLocalBackupResult(now, ok = false)
                return@withContext BackupResult.Failure(BackupResult.Reason.WriteFailed)
            }

            val name = backupName(now)
            val written = source.put(temp, name)

            settings.recordLocalBackupResult(now, ok = true)

            // ENGINE-01 (D-02, D-05): gated media leg — CalTracker (empty mediaDirectories) executes
            // NONE of this, staying byte-identical. On a non-empty config, snapshot + put a
            // stem-paired "<stem>.media.zip" sidecar through the SAME unmodified source.put seam. A
            // media failure NEVER downgrades the already-recorded DB success above (D-02) — it only
            // arms the durable MediaWarning.
            val mediaWarning = if (config.mediaDirectories.isNotEmpty()) {
                backupMediaLeg(source, name, mediaTemp)
            } else {
                null
            }

            // Retention (SCH-04, D-01c): prune AFTER the verified success is recorded. WR-01: exclude the
            // just-written ref by IDENTITY, not by positional index 0 — SAF `lastModified()` granularity can
            // tie the fresh file with a same-second existing one, and on a tie the new file is not guaranteed
            // to sort to index 0, so a positional `drop(keepN)` could delete the file we just wrote.
            //
            // ENG-01 (D-01/D-01b/D-01c/T-22-04): the record-success-FIRST ordering above is load-bearing and
            // MUST NOT be reordered. A post-success prune throw is NON-FATAL housekeeping — wrap ONLY the prune
            // call in runCatching so its throw can NEVER unwind into the outer `catch (Throwable)` and re-record
            // ok = false / return a Failure. It is surfaced instead as an additive, text-free PruneWarning marker
            // on the still-Success result (T-15-11: no exception text crosses).
            val pruneWarning = runCatching {
                prune(source, config.retentionCount, written)
            }.exceptionOrNull()?.let { PruneWarning }
            BackupResult.Success(pruneWarning, mediaWarning)
        } catch (e: FolderUnavailableException) {
            // A missing/un-granted SAF destination is its own fixed reason, still never rethrown.
            settings.recordLocalBackupResult(now, ok = false)
            BackupResult.Failure(BackupResult.Reason.FolderUnavailable)
        } catch (e: Throwable) {
            settings.recordLocalBackupResult(now, ok = false)
            BackupResult.Failure(BackupResult.Reason.WriteFailed)
        } finally {
            // WR-02: always reclaim the temp snapshots — on success, on a failed put, on any throw.
            temp.delete()
            mediaTemp.delete()
        }
    }

    /**
     * BACKUP-TO-DRIVE flow (BAK-03): the Drive twin of [backup]. Mirrors its guarded structure exactly
     * — hoist the temp, `finally { temp.delete() }`, record per-destination status, never rethrow —
     * but writes to the Drive destination and returns the richer [DriveBackupResult] so the ViewModel
     * can pick the exact fixed Snackbar (401 re-auth vs no-network vs verify-mismatch vs generic).
     *
     * ANY [DriveException] is caught and its `kind` mapped to a [DriveBackupResult.Reason]
     * (Unauthorized→NeedsReauth, Network→NoNetwork, VerifyMismatch→VerifyFailed, else→Failed); any
     * other throwable maps to [DriveBackupResult.Reason.Failed]. This method NEVER rethrows across the
     * `:app` <-> `:backup` boundary (T-17-06) and no wire text crosses in the result.
     *
     * Reuses [backupName] verbatim (BAK-04 same-minute guard) — but scoped to the DRIVE listing so a
     * Drive collision guard reads Drive names, not local ones.
     */
    suspend fun backupToDrive(): DriveBackupResult = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        // Mirror backup()'s hoist-out-of-try so `finally` always reclaims the snapshot temp.
        val temp = File(tempDir(), "snapshot-drive-$now.db")
        // ENGINE-01 (D-02, D-05): the Drive twin of backup()'s hoisted mediaTemp.
        val mediaTemp = File(tempDir(), "media-drive-$now.zip")
        try {
            val snapped = fileManager.snapshot(config.databaseFile, temp)
            if (!snapped) {
                settings.recordDriveBackupResult(now, ok = false)
                return@withContext DriveBackupResult.Failure(DriveBackupResult.Reason.Failed)
            }

            val name = driveBackupName(now)
            val written = driveSource.put(temp, name)

            settings.recordDriveBackupResult(now, ok = true)

            // ENGINE-01 (D-02, D-05): the Drive twin of backup()'s gated media leg — same gate, same
            // stem-pairing, same never-downgrade-the-DB-Success contract, just against driveSource.
            val mediaWarning = if (config.mediaDirectories.isNotEmpty()) {
                backupMediaLeg(driveSource, name, mediaTemp)
            } else {
                null
            }

            // Retention (SCH-04, D-01c): prune the DRIVE listing after the verified success, same
            // post-put/no-rollback contract as the local path. WR-01: exclude the just-written ref by
            // identity rather than positional index 0 (see backup() for the tie-ordering rationale).
            //
            // ENG-01 (D-01/T-22-04): identical shape to backup() — record-success stays FIRST, the prune throw
            // is caught by runCatching (never reaching the outer catch, never flipping to Failure) and surfaced
            // as a text-free PruneWarning on the still-Success Drive result.
            val pruneWarning = runCatching {
                prune(driveSource, config.retentionCount, written)
            }.exceptionOrNull()?.let { PruneWarning }
            DriveBackupResult.Success(pruneWarning, mediaWarning)
        } catch (e: DriveException) {
            settings.recordDriveBackupResult(now, ok = false)
            DriveBackupResult.Failure(e.kind.toDriveReason())
        } catch (e: Throwable) {
            settings.recordDriveBackupResult(now, ok = false)
            DriveBackupResult.Failure(DriveBackupResult.Reason.Failed)
        } finally {
            temp.delete()
            mediaTemp.delete()
        }
    }

    /**
     * LIST-DRIVE-BACKUPS (RST-02): the marked Drive folder's backups, newest-first (the client already
     * sorts). Guarded — a network/token failure yields an EMPTY list the VM renders as empty/failed,
     * never a crash (T-17-08). Mirrors the `runCatching{}.getOrDefault(emptyList())` guard the same
     * `backupName` collision check already uses on the local listing.
     */
    suspend fun listDriveBackups(): List<BackupRef> = withContext(Dispatchers.IO) {
        runCatching { driveSource.list() }.getOrDefault(emptyList())
    }

    /**
     * RESTORE flow (RST-01, RST-03): fetch the candidate, VALIDATE it (integrity + `user_version`
     * ceiling) BEFORE touching anything, then — only on a pass — take a safety-copy of the live DB,
     * stage the validated file, arm the pending-restore hand-off (both the durable [BackupSettingsStore]
     * status AND the plain marker/paths the pre-Hilt [RestoreSwapInitializer] reads), and request a
     * restart so the cold-start Initializer applies the swap.
     *
     * Refusal-before-staging (T-15-09): a newer-schema or corrupt candidate returns a
     * [BackupResult.Failure] with NOTHING staged, flagged, or restarted. Never rethrows.
     *
     * The LOCAL path fetches from [source]; the DRIVE restore ([restoreFromDrive]) fetches from
     * [driveSource] and then feeds this SAME validate→safety-copy→swap→restart engine (D-04b) — one
     * restore path, no second implementation.
     */
    suspend fun restore(ref: BackupRef): BackupResult = restoreFrom(source, ref)

    /**
     * DRIVE RESTORE (RST-02, D-04b): download the chosen Drive [ref] and feed it to the UNCHANGED
     * Phase-15 restore engine. Byte-for-byte the same validate→safety-copy→swap→restart flow as the
     * local [restore] — only the fetch source differs. A corrupt/newer-schema downloaded `.db` is
     * refused with live data untouched (T-17-05).
     */
    suspend fun restoreFromDrive(ref: BackupRef): BackupResult = restoreFrom(driveSource, ref)

    /**
     * The shared restore engine (D-04b) both [restore] (local) and [restoreFromDrive] converge on.
     * Only the [fetchSource] differs; the validate→safety-copy→swap→restart body below is identical
     * for every destination, so there is exactly one restore path.
     */
    private suspend fun restoreFrom(fetchSource: BackupSource, ref: BackupRef): BackupResult = withContext(Dispatchers.IO) {
        // PHASE 1 — fetch + validate. A failure HERE genuinely means an unusable/absent candidate:
        // a FolderUnavailableException maps to FolderUnavailable, anything else to NotAValidBackup
        // (WR-01). This is the only phase where NotAValidBackup is a correct verdict.
        val candidate = try {
            fetchSource.get(ref)
        } catch (e: FolderUnavailableException) {
            return@withContext BackupResult.Failure(BackupResult.Reason.FolderUnavailable)
        } catch (e: Throwable) {
            return@withContext BackupResult.Failure(BackupResult.Reason.NotAValidBackup)
        }

        // GATE FIRST: integrity + schema ceiling. A refusal returns before any state changes.
        val refusal = runCatching { fileManager.validate(candidate, config.currentSchemaVersion) }
            .getOrDefault(BackupResult.Failure(BackupResult.Reason.NotAValidBackup))
        if (refusal != null) {
            candidate.delete()
            return@withContext refusal
        }

        // PHASE 2 — post-validate staging. The candidate is PROVEN valid here (WR-01): a safety-copy,
        // stage, arm, or restart failure is a WriteFailed, NEVER a mislabeled NotAValidBackup.
        //
        // ENG-04 / D-04 / T-22-03: hoist the deterministic staged/safety paths out of the try so the catch
        // blocks below can reclaim any partial `.staged`/`.safety` a mid-stage failure left behind. The
        // cleanup lives in the CATCH ONLY — NOT a finally (Pitfall 4): the happy path calls
        // config.restartApp() which exit(0)s before returning, so a finally would delete the freshly-armed
        // staged/safety siblings out from under the pending cold-start swap. reconcilePendingRestore()
        // handles the armed-but-not-yet-swapped boot case and is intentionally untouched.
        val liveDb = config.databaseFile
        val stagedPath = liveDb.path + RestoreSwapInitializer.STAGED_SUFFIX
        val safetyPath = liveDb.path + RestoreSwapInitializer.SAFETY_SUFFIX
        try {
            val markerFile = File(liveDb.path + RestoreSwapInitializer.MARKER_SUFFIX)

            // 1. Safety-copy the live DB (the Initializer's rollback source).
            liveDb.copyTo(File(safetyPath), overwrite = true)
            // 2. Stage the validated candidate at the deterministic staged path.
            candidate.copyTo(File(stagedPath), overwrite = true)
            candidate.delete()
            // 3. Arm the hand-off: durable status keys (ViewModel surface) AND the plain marker the
            //    pre-Hilt Initializer reads at cold start (it cannot touch DataStore).
            settings.setPendingRestore(stagedPath, safetyPath)
            markerFile.writeText("pending")

            // 4. Relaunch so the cold-start Initializer performs the swap on a closed DB file. On the
            //    happy path restartApp exits the process and never returns. A `false` return means no
            //    launch intent was available (WR-04): the swap is staged but NOT applied, so report
            //    WriteFailed rather than a false Success that strands the "reopening…" interstitial.
            val restarting = config.restartApp(context)
            if (!restarting) {
                // WR-04: no launch intent — the swap was staged but will NOT be applied, so this is a genuine
                // stage failure. Reclaim the partial staged/safety siblings before reporting WriteFailed so no
                // orphaned full-size copies remain (ENG-04); nothing was armed for a cold-start swap here.
                cleanupRestoreStaging(stagedPath, safetyPath, candidate)
                return@withContext BackupResult.Failure(BackupResult.Reason.WriteFailed)
            }
            BackupResult.Success()
        } catch (e: FolderUnavailableException) {
            // ENG-04 (T-22-03): a mid-stage failure never armed the swap — reclaim any partial `.staged`/
            // `.safety` (and the candidate if not yet deleted) so a failed restore strands no orphans.
            cleanupRestoreStaging(stagedPath, safetyPath, candidate)
            BackupResult.Failure(BackupResult.Reason.FolderUnavailable)
        } catch (e: Throwable) {
            // Past validate() the candidate IS valid; a staging/copy/restart failure is a write failure.
            // ENG-04 (T-22-03): reclaim the partial staged/safety siblings in the CATCH (never a finally,
            // Pitfall 4) so no orphaned files remain after the failed restore.
            cleanupRestoreStaging(stagedPath, safetyPath, candidate)
            BackupResult.Failure(BackupResult.Reason.WriteFailed)
        }
    }

    /**
     * ENG-04 / D-04 (T-22-03): reclaim a FAILED restore-stage's partial artifacts — the deterministic
     * `.staged`/`.safety` siblings of the live DB and the fetched [candidate] if it was not already
     * consumed — so an interrupted restore leaves the staging area exactly as it found it. Called ONLY
     * from the PHASE-2 catch blocks (never a finally): the happy path exits the process via
     * `restartApp()` before returning, so this must never fire on the armed-swap path (Pitfall 4).
     * Fully guarded — a cleanup failure must not mask the original restore failure.
     */
    private fun cleanupRestoreStaging(stagedPath: String, safetyPath: String, candidate: File) {
        runCatching { File(stagedPath).delete() }
        runCatching { File(safetyPath).delete() }
        runCatching { if (candidate.exists()) candidate.delete() }
    }

    /**
     * Reconcile the durable restore hand-off ONCE the app is back up after a swap (CR-01/IN-04).
     *
     * The pre-Hilt [RestoreSwapInitializer] consumes and deletes only the plain marker file at cold
     * start — it cannot touch DataStore. So after a completed swap the durable channels are left
     * desynced: `RESTORE_PENDING` stays `true` forever and the full-size `.safety` copy (and any
     * leftover `.staged`) of the live DB is never reclaimed, silently doubling on-disk footprint per
     * restore. This wires the previously-orphaned [BackupSettingsStore.clearPendingRestore] surface
     * (IN-04) into a real post-Hilt call site (the ViewModel init).
     *
     * Contract:
     *  - Fires ONLY when a restore is pending (`restorePending == true`) AND the marker file is GONE
     *    (== the swap already ran at cold start). While the marker is still present the swap is armed
     *    but not yet applied, so this is a strict no-op — it must never clear the flag or delete the
     *    staged/safety siblings out from under a pending swap.
     *  - Idempotent: a second call after a clean reconcile does nothing.
     *  - Fully guarded: no exception crosses the boundary (the same never-throw posture as the two
     *    orchestration flows). A failed cleanup leaves the durable state untouched for a later retry.
     */
    suspend fun reconcilePendingRestore() {
        runCatching {
            val liveDb = config.databaseFile
            val marker = File(liveDb.path + RestoreSwapInitializer.MARKER_SUFFIX)
            // Marker present == swap still armed/pending -> do NOT tear down the hand-off.
            if (!settings.restorePending.first() || marker.exists()) return
            // Swap completed at cold start: reclaim the durable copies and clear the flag.
            File(liveDb.path + RestoreSwapInitializer.SAFETY_SUFFIX).delete()
            File(liveDb.path + RestoreSwapInitializer.STAGED_SUFFIX).delete()
            settings.clearPendingRestore()
        }.onFailure {
            // WR-02: keep the never-throw posture, but do NOT swallow silently — a partial teardown
            // (a `.safety`/`.staged` deleted while `RESTORE_PENDING` is still true) must leave a
            // diagnostic trail. The durable state is intentionally left untouched for a later retry.
            Log.w(TAG, "reconcilePendingRestore failed; durable restore state left for retry", it)
        }
    }

    /**
     * SCHEDULED BACKUP (SCH-01/02/05, D-07a): the single entry point the background [worker.BackupWorker]
     * calls. Dispatches on the [destination] tag to the EXISTING verified [backup] / [backupToDrive]
     * flows — it adds NO second success path (RESEARCH Pitfall 5), so retention + status recording +
     * the never-throw posture all come for free from the reused flows. Its job is only to translate the
     * per-destination result into a typed [ScheduledOutcome] the worker maps to a WorkManager `Result`.
     *
     * LOCAL: [BackupResult.Success] → [ScheduledOutcome.Success]; any failure (WriteFailed /
     * FolderUnavailable) → [ScheduledOutcome.Transient] (a retriable local hiccup, never a reauth).
     *
     * DRIVE: [DriveBackupResult.Success] → clears `needs_reauth` then [ScheduledOutcome.Success];
     * `NeedsReauth` (401 / null token) → persists `needs_reauth = true` then [ScheduledOutcome.NeedsReauth];
     * `NoNetwork` → [ScheduledOutcome.NoNetwork]; `Failed` / `VerifyFailed` → [ScheduledOutcome.Transient].
     *
     * `needs_reauth` is written ONLY on the `NeedsReauth` branch (D-07a) — a mid-flight network drop
     * (`NoNetwork`) or a generic failure can never masquerade as a revoked grant. An unknown destination
     * tag maps to [ScheduledOutcome.Transient] (the worker bounds retries), never a crash.
     */
    open suspend fun runScheduledBackup(destination: String): ScheduledOutcome = when (destination) {
        DESTINATION_LOCAL -> when (val result = backup()) {
            // ENGINE-01 (D-02): thread the media warning through — it was already persisted via
            // settings.setMediaBackupWarning() inside backup()'s media leg, so nothing extra to do here.
            is BackupResult.Success -> ScheduledOutcome.Success(mediaWarning = result.mediaWarning)
            is BackupResult.Failure -> ScheduledOutcome.Transient
        }

        DESTINATION_DRIVE -> when (val result = backupToDrive()) {
            is DriveBackupResult.Success -> {
                settings.setNeedsReauth(false)
                // ENGINE-01 (D-02): same threading as the local branch, already persisted inside backupToDrive().
                ScheduledOutcome.Success(mediaWarning = result.mediaWarning)
            }
            is DriveBackupResult.Failure -> when (result.reason) {
                DriveBackupResult.Reason.NeedsReauth -> {
                    settings.setNeedsReauth(true)
                    ScheduledOutcome.NeedsReauth
                }
                DriveBackupResult.Reason.NoNetwork -> ScheduledOutcome.NoNetwork
                DriveBackupResult.Reason.VerifyFailed,
                DriveBackupResult.Reason.Failed -> ScheduledOutcome.Transient
            }
        }

        else -> ScheduledOutcome.Transient
    }

    /**
     * COUNT-BASED RETENTION PRUNE (SCH-04, D-01/D-01b): keep the newest [keepN] backups in [source],
     * delete the rest. Called only AFTER a verified `put()` from the [backup] / [backupToDrive] success
     * paths (D-01c — fires after ANY successful backup, scheduled or manual).
     *
     * WR-01 — deterministic just-written protection: when [justWritten] is supplied, the freshly-put ref
     * is excluded from the delete candidates by IDENTITY (`ref` equality), then the newest `keepN - 1` of
     * the remaining files are kept and the older tail deleted. This is independent of filesystem clock
     * resolution: SAF `lastModified()` can be second-granular, so a same-second tie between the new file
     * and an existing one is not guaranteed to sort the new file to index 0 — a positional `drop(keepN)`
     * could then delete the file we just wrote. Excluding by identity removes that failure mode entirely.
     * (When [justWritten] is null — e.g. an explicit maintenance prune — the legacy positional behaviour
     * over the newest-first listing is used.)
     *
     * Guards (D-01b): `require(keepN >= 1)` floor (never delete the last good backup); a no-op when the
     * effective listing size is `<= keepN`; the source listing is already app-scoped (LocalBackupSource
     * `caltracker-` prefix; DriveBackupSource marked folder), so the loop can never reach unrelated user
     * files (T-18-02). A failed `delete()` PROPAGATES loudly (not swallowed) and must not roll back the
     * already-recorded success — the caller wires this after the success write, not inside it.
     */
    suspend fun prune(source: BackupSource, keepN: Int, justWritten: BackupRef? = null) {
        require(keepN >= 1) { "retention keepN must be >= 1 to preserve the last good backup" }
        val all = source.list() // post-put listing, newest-first
        // ENGINE-01 (D-05): a paired "<stem>.media.zip" sidecar shares its `.db`'s EXACT stem — group by
        // stem so retention counts/deletes BACKUP OPERATIONS, not individual files, and a `.db` is never
        // pruned without its paired `.media.zip` (or vice versa). When mediaDirectories is empty
        // (CalTracker), every group is a singleton `[ref]` in list order — byte-identical to the
        // pre-media prune behavior below.
        val groups = groupByStemNewestFirst(all)
        if (justWritten == null) {
            if (groups.size <= keepN) return // no-op when at/under the window — never delete the last good backup
            groups.drop(keepN).forEach { group -> group.forEach { source.delete(it) } } // oldest tail; a failed delete throws loudly
            return
        }
        // Never a delete candidate: the just-written STEM (its .db and any paired .media.zip) is
        // protected by identity regardless of sort ties.
        val others = groups.filterNot { group -> group.any { it.ref == justWritten.ref } }
        if (others.size <= keepN - 1) return // the new file + everything else already fits the window
        others.drop(keepN - 1).forEach { group -> group.forEach { source.delete(it) } } // oldest tail; a failed delete throws loudly
    }

    /**
     * Group [refs] by their `.db`/`.media.zip` stem (D-05), preserving each stem's FIRST-APPEARANCE
     * position in the already-newest-first [refs] listing. A backup op's `.db` and its paired
     * `.media.zip` are written moments apart in the same run, so their timestamps are effectively
     * identical and they land adjacent (or near-adjacent) in the newest-first listing — first-appearance
     * order is therefore a faithful newest-first GROUP ordering. When `refs` contains only `.db` entries
     * (CalTracker's empty `mediaDirectories`), every group is a singleton and this is a no-op reshape.
     */
    private fun groupByStemNewestFirst(refs: List<BackupRef>): List<List<BackupRef>> {
        val byStem = LinkedHashMap<String, MutableList<BackupRef>>()
        for (ref in refs) {
            byStem.getOrPut(stemOf(ref.name)) { mutableListOf() } += ref
        }
        return byStem.values.toList()
    }

    /** The backup-op stem for a `<stem>.db` or `<stem>.media.zip` name (D-05) — else [name] unchanged. */
    private fun stemOf(name: String): String = when {
        name.endsWith(MEDIA_ARCHIVE_SUFFIX) -> name.removeSuffix(MEDIA_ARCHIVE_SUFFIX)
        name.endsWith(".db") -> name.removeSuffix(".db")
        else -> name
    }

    /**
     * The gated media backup leg (ENGINE-01, D-02, D-05): snapshot [config.mediaDirectories] to
     * [mediaTemp], then [BackupSource.put] it into [destSource] under the SAME stem [dbName] the
     * caller just wrote the `.db` under, producing a stem-paired `<stem>.media.zip` sidecar through
     * the UNMODIFIED source seam. Wrapped end-to-end in `runCatching` (the module's non-throwing-
     * boundary posture, mirrors [reconcilePendingRestore]'s leg guards) — ANY failure (a snapshot that
     * returns `false`, or a throwing `put`) arms the durable [BackupSettingsStore.setMediaBackupWarning]
     * flag and returns a [MediaWarning] WITHOUT ever throwing back into the caller, so the
     * already-recorded DB success is never touched (D-02's core invariant).
     */
    private suspend fun backupMediaLeg(destSource: BackupSource, dbName: String, mediaTemp: File): MediaWarning? {
        val result = runCatching {
            val snapped = mediaArchiveManager.snapshot(config.mediaDirectories, mediaTemp)
            check(snapped) { "media snapshot failed" }
            destSource.put(mediaTemp, mediaSidecarName(dbName))
        }
        return if (result.isSuccess) {
            settings.setMediaBackupWarning(false)
            null
        } else {
            Log.w(TAG, "media backup leg failed for '$dbName'; DB backup unaffected (D-02)", result.exceptionOrNull())
            settings.setMediaBackupWarning(true)
            MediaWarning
        }
    }

    /** The stem-paired sidecar name for a `<stem>.db` (D-05): `<stem>.media.zip`. */
    private fun mediaSidecarName(dbName: String): String = dbName.removeSuffix(".db") + MEDIA_ARCHIVE_SUFFIX

    private fun tempDir(): File =
        File(config.databaseFile.parentFile, "backup-temp").apply { mkdirs() }

    /**
     * A collision-free, timestamped backup name: `caltracker-<yyyy-MM-dd>_<HHmm>.db`, extended to
     * `_ss` when two backups land in the same minute (UX-D-06 same-minute collision guard). The seconds
     * suffix is only appended when a same-minute name is already present in the destination listing.
     */
    private suspend fun backupName(nowMillis: Long): String {
        val prefix = config.appName
        val minute = SimpleDateFormat("yyyy-MM-dd'_'HHmm", Locale.US).format(Date(nowMillis))
        // WR-03: match on a PRECISE prefix (startsWith the normalized `prefix-minute` stem), not a
        // loose substring contains(). A contains() check false-positives on longer/`.bin`-suffixed
        // names and can match a shorter timestamp substring inside a longer one — wrongly suppressing
        // the seconds suffix and defeating the unique-filename intent (UX-D-06).
        val baseStem = "$prefix-$minute" // e.g. caltracker-2026-07-01_1430
        val existing = runCatching { source.list().map { it.name } }.getOrDefault(emptyList())
        if (existing.none { it.startsWith(baseStem) }) return "$baseStem.db"
        val seconds = SimpleDateFormat("ss", Locale.US).format(Date(nowMillis))
        return "${baseStem}_$seconds.db"
    }

    /**
     * The Drive twin of [backupName] (BAK-04 same-minute guard) — identical `caltracker-<yyyy-MM-dd>_<HHmm>.db`
     * scheme, but the same-minute collision check reads the DRIVE listing, not the local one, so a Drive
     * backup's uniqueness is decided against Drive names. Guarded like [backupName]: a listing failure
     * falls back to the base stem rather than throwing.
     */
    private suspend fun driveBackupName(nowMillis: Long): String {
        val prefix = config.appName
        val minute = SimpleDateFormat("yyyy-MM-dd'_'HHmm", Locale.US).format(Date(nowMillis))
        val baseStem = "$prefix-$minute"
        val existing = runCatching { driveSource.list().map { it.name } }.getOrDefault(emptyList())
        if (existing.none { it.startsWith(baseStem) }) return "$baseStem.db"
        val seconds = SimpleDateFormat("ss", Locale.US).format(Date(nowMillis))
        return "${baseStem}_$seconds.db"
    }

    /**
     * Map a [DriveException.Kind] to the fixed [DriveBackupResult.Reason] the ViewModel turns into copy
     * (RESEARCH L413-425 / T-17-06). Only the fixed category crosses — never any wire text:
     *  - [DriveException.Kind.Unauthorized] (401 / null token) → [DriveBackupResult.Reason.NeedsReauth]
     *  - [DriveException.Kind.Network] (transport IOException) → [DriveBackupResult.Reason.NoNetwork]
     *  - [DriveException.Kind.VerifyMismatch] (md5/size) → [DriveBackupResult.Reason.VerifyFailed]
     *  - everything else (403 quota / 404 / 5xx / 2xx-malformed) → [DriveBackupResult.Reason.Failed]
     */
    private fun DriveException.Kind.toDriveReason(): DriveBackupResult.Reason = when (this) {
        DriveException.Kind.Unauthorized -> DriveBackupResult.Reason.NeedsReauth
        DriveException.Kind.Network -> DriveBackupResult.Reason.NoNetwork
        DriveException.Kind.VerifyMismatch -> DriveBackupResult.Reason.VerifyFailed
        DriveException.Kind.RateLimited,
        DriveException.Kind.NotFound,
        // ENG-03 / D-03: a 2xx response with an unparseable body is a coarse generic failure to the VM.
        DriveException.Kind.Malformed,
        DriveException.Kind.Server -> DriveBackupResult.Reason.Failed
    }

    companion object {
        private const val TAG = "BackupRepository"

        /**
         * The destination tags [runScheduledBackup] dispatches on, shared with the worker and the
         * scheduler so the input-data contract is defined in exactly one place (no stringly-typed
         * drift between producer and consumer).
         */
        const val DESTINATION_LOCAL = "LOCAL"
        const val DESTINATION_DRIVE = "DRIVE"

        /** The stem-paired media sidecar suffix (ENGINE-01, D-05) — `<stem>.db` -> `<stem>.media.zip`. */
        private const val MEDIA_ARCHIVE_SUFFIX = ".media.zip"
    }
}

/**
 * The typed outcome of a [BackupRepository.runScheduledBackup] run (SCH-05, D-07a/D-07b).
 *
 * A [sealed interface] so the [worker.BackupWorker] maps every variant onto a WorkManager `Result`
 * in an exhaustive `when` with no `else`. Deliberately UI-free and framework-free (no `androidx.work`
 * type here) so it lives in the reusable engine (LIB-01):
 *
 *  - [Success]: the backup completed and verified → the worker returns `Result.success()`.
 *  - [NeedsReauth]: a Drive 401 / null-token — the grant must be re-authorized; `needs_reauth` has been
 *    persisted → the worker returns terminal `Result.failure()` (no retry can fix a revoked grant, D-07b).
 *  - [NoNetwork]: a transport failure → the worker returns `Result.retry()` (exponential backoff).
 *  - [Transient]: any other retriable failure → the worker retries up to a bounded attempt count,
 *    then `Result.failure()` (D-07b — no infinite battery-draining retry loop).
 */
sealed interface ScheduledOutcome {
    /**
     * The scheduled backup completed and was verified.
     *
     * [mediaWarning] mirrors [BackupResult.Success.mediaWarning] / [DriveBackupResult.Success.mediaWarning]
     * (ENGINE-01, D-02): a scheduled backup whose DB leg succeeded but whose paired media archive
     * failed still maps to [Success] — never a retriable failure — carrying this fixed, text-free
     * marker so the un-watched scheduled path is never silently missing media. The durable
     * `mediaBackupWarning` flag is armed by the underlying [BackupRepository.backup] /
     * [BackupRepository.backupToDrive] call BEFORE this value is constructed; [mediaWarning] here is
     * purely informational threading, not a second persistence point. Defaulted to `null` so a
     * MediaWarning-free construction reads naturally as "clean success."
     */
    data class Success(val mediaWarning: MediaWarning? = null) : ScheduledOutcome

    /** A Drive 401 / null-token: the grant must be re-authorized (terminal for the worker). */
    data object NeedsReauth : ScheduledOutcome

    /** A transport / no-network failure: retriable with backoff. */
    data object NoNetwork : ScheduledOutcome

    /** Any other retriable failure: bounded retry then terminal failure. */
    data object Transient : ScheduledOutcome
}

package io.github.ygaray.backupengine

import android.database.sqlite.SQLiteDatabase
import android.os.Build
import io.github.ygaray.backupengine.model.BackupResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The reusable, app-agnostic SQLite/file engine at the heart of the `:backup` module (BAK-04, RST-03).
 *
 * It has NO knowledge of the app's schema — it manipulates the raw `.db` file and uses
 * `android.database.sqlite` directly (never Room). Every operation is a defined input->output
 * contract against real SQLite files, which is why this class is TDD-covered on the JVM via
 * Robolectric — including the API 26-29 fallback branch the Gate-1 device never exercises.
 *
 * Fail-loud policy: an I/O/SQLite failure surfaces as a `false` return (snapshot/verify) or a thrown
 * exception the caller maps to a [io.github.ygaray.backupengine.model.BackupResult.Failure]; a silently-empty
 * file is NEVER reported as success (RESEARCH Pitfall 15, project fail-loud policy).
 */
open class DatabaseFileManager {

    /**
     * Produce a single self-contained, WAL-safe snapshot of [source] at [dest] (BAK-04).
     *
     * Chooses the branch by [useVacuumInto], which defaults to `Build.VERSION.SDK_INT >= 30`
     * (platform SQLite >= 3.27 where `VACUUM INTO` exists) and is overridable so both branches run
     * under one JVM test (RESEARCH Pitfall 1, resolved Q2):
     *  - VACUUM INTO branch: one statement produces a fully-checkpointed, integrity-consistent file
     *    with no `-wal`/`-shm` to ship.
     *  - Fallback branch (API 26-29): `PRAGMA wal_checkpoint(TRUNCATE)` on the live DB FIRST — never
     *    copy without checkpointing (Pitfall 1) — then copy the `.db` (whose `-wal` is now empty).
     *
     * The `dest` and any stale `dest-wal`/`dest-shm` are removed before writing so a re-run can't
     * inherit a foreign WAL. Success is gated by a post-write [verify] (size>0 + integrity_check).
     *
     * @return true only when the snapshot was written AND passes post-write verification.
     */
    suspend fun snapshot(
        source: File,
        dest: File,
        useVacuumInto: Boolean = Build.VERSION.SDK_INT >= 30,
    ): Boolean = withContext(Dispatchers.IO) {
        // Clear any prior output + its sidecars so we never ship a stale/foreign WAL.
        dest.delete()
        File(dest.path + "-wal").delete()
        File(dest.path + "-shm").delete()

        val live = SQLiteDatabase.openDatabase(source.path, null, SQLiteDatabase.OPEN_READWRITE)
        try {
            if (useVacuumInto) {
                // Single statement: checkpointed + integrity-consistent single file. VACUUM INTO
                // returns a (empty) result set, so it MUST run via rawQuery, not execSQL, or Android
                // rejects it with "Queries can be performed using ... query or rawQuery methods only".
                live.rawQuery("VACUUM INTO ?", arrayOf(dest.absolutePath)).use { it.moveToFirst() }
            } else {
                // API 26-29: checkpoint the live DB into the main file, THEN copy the .db only.
                live.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
                source.copyTo(dest, overwrite = true)
                // The copied file inherits WAL journal mode, so any reader materializes -wal/-shm
                // sidecars again. Rewrite it to a rollback-journal (DELETE) so the snapshot is a
                // genuinely self-contained single .db with no sidecar expectation (BAK-04, Pitfall 2).
                SQLiteDatabase.openDatabase(dest.path, null, SQLiteDatabase.OPEN_READWRITE).use { snap ->
                    snap.rawQuery("PRAGMA journal_mode=DELETE", null).use { it.moveToFirst() }
                }
                File(dest.path + "-wal").delete()
                File(dest.path + "-shm").delete()
            }
        } finally {
            live.close()
        }

        // Post-write verify gates success — a truncated/corrupt output is a failure (Pitfall 15).
        verify(dest)
    }

    /**
     * Post-write / candidate integrity gate: the file must be non-empty AND open as SQLite with a
     * clean `PRAGMA integrity_check` (RESEARCH Pitfall 15; the untrusted-input gate for restore, D-03).
     *
     * Returns false (never throws) on a truncated, corrupt, or non-SQLite file so callers can map it
     * to a fixed-copy failure without exception text crossing the UI boundary.
     */
    fun verify(file: File): Boolean {
        if (!file.exists() || file.length() == 0L) return false
        return try {
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                db.rawQuery("PRAGMA integrity_check", null).use { c ->
                    c.moveToFirst() && c.getString(0).equals("ok", ignoreCase = true)
                }
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Read the intrinsic `PRAGMA user_version` off an arbitrary candidate `.db`, opened READ-ONLY
     * outside Room (RESEARCH Pattern 4). Room stamps this = its `@Database(version=…)` on every DB
     * it creates, so it is intrinsic to the file and cannot desync from a sidecar.
     *
     * @throws android.database.sqlite.SQLiteException if [candidate] is not a valid SQLite file —
     *   the caller ([validate]) maps that to [BackupResult.Reason.NotAValidBackup].
     */
    fun readUserVersion(candidate: File): Int =
        SQLiteDatabase.openDatabase(candidate.path, null, SQLiteDatabase.OPEN_READONLY)
            .use { it.version }

    /**
     * Validate an UNTRUSTED restore candidate before any swap (RST-03, D-03). Two gates:
     *  1. Integrity/openability: a non-SQLite/corrupt file (open throws OR [verify] fails) is refused
     *     as [BackupResult.Reason.NotAValidBackup] — never swapped in (threat T-15-02).
     *  2. Schema ceiling: a candidate whose `user_version` exceeds [currentSchemaVersion] is refused
     *     as [BackupResult.Reason.SchemaTooNew] (restoring a newer DB could brick/wipe live data,
     *     T-15-03). Equal (direct) and older (Room migrates on next open) are allowed.
     *
     * @return `null` when the candidate is allowed; a [BackupResult.Failure] with the refusal reason
     *   otherwise. Never throws — refusals are values so no exception text crosses the UI (D-06).
     */
    fun validate(candidate: File, currentSchemaVersion: Int): BackupResult.Failure? {
        if (!verify(candidate)) {
            return BackupResult.Failure(BackupResult.Reason.NotAValidBackup)
        }
        val version = try {
            readUserVersion(candidate)
        } catch (_: Exception) {
            return BackupResult.Failure(BackupResult.Reason.NotAValidBackup)
        }
        if (version > currentSchemaVersion) {
            return BackupResult.Failure(BackupResult.Reason.SchemaTooNew)
        }
        return null
    }

    /**
     * Cold-start atomic swap of the live DB with a validated [staged] file (RST-03, Pitfall 2, CR-02).
     *
     * Sequence, designed so a crash at any point is recoverable and never leaves a corrupt live DB:
     *  1. Copy the current live [target] to [safetyCopy] (the rollback source).
     *  2. Delete [target] + `-wal` + `-shm` — stale sidecars would corrupt the restored main file.
     *  3. Move [staged] into [target].
     *
     * **Guarded, verified rollback (CR-02).** On ANY exception the live [target] is restored from
     * [safetyCopy] via [restoreFromSafety], which is ITSELF guarded and verified. The rollback can
     * fail too — the classic trigger for the original failure is a full disk, and rollback must write
     * another full-DB copy into that same full filesystem. Two distinct outcomes:
     *  - **Clean rollback** (restore verified good): returns `false`. The original live data survives a
     *    mid-swap failure (threat T-15-04); callers and the pre-Hilt Initializer treat this as "swap
     *    not applied, live DB intact" and clear the marker.
     *  - **Doubly-failing swap** (restore NOT verified good): the live [target] is missing/corrupt AND
     *    [safetyCopy] is our only remaining good copy — so we do NOT delete the safety copy and we
     *    `error(...)` LOUDLY. The exception propagates out of the pre-Hilt Initializer so the marker is
     *    left armed for a retry rather than booting with no live DB (no silent data loss, no boot loop
     *    that consumed a deleted `staged`). This is the hard-failure escalation path.
     *
     * Paths are caller-supplied and deterministic so Plan 04's pre-Hilt Initializer can reuse this
     * idempotently.
     *
     * @return true when the staged file is in place and integrity-clean; false after a verified-clean
     *   rollback.
     * @throws IllegalStateException when the rollback itself could not produce a verified-good live DB
     *   (safety copy preserved; failure surfaced loudly for a retry).
     */
    suspend fun applySwap(
        target: File,
        staged: File,
        safetyCopy: File,
    ): Boolean = withContext(Dispatchers.IO) {
        // Safety-copy the live DB first — this is the rollback source (RST-03).
        target.copyTo(safetyCopy, overwrite = true)
        try {
            if (!staged.exists()) {
                error("staged restore file missing: ${staged.path}")
            }
            // Remove the live main file + BOTH sidecars before dropping the restored file in.
            target.delete()
            File(target.path + "-wal").delete()
            File(target.path + "-shm").delete()

            if (!staged.renameTo(target)) {
                // Cross-filesystem rename can fail; fall back to copy+delete.
                staged.copyTo(target, overwrite = true)
                staged.delete()
            }

            check(verify(target)) { "post-swap integrity_check failed" }
            true
        } catch (_: Exception) {
            // Guarded rollback (CR-02): restore the live DB from the safety-copy AND verify it. A
            // rollback that itself fails (e.g. full disk) must not silently return "clean" — it leaves
            // the live DB absent/corrupt with no reported failure.
            val rolledBack = restoreFromSafety(target, safetyCopy)
            if (!rolledBack) {
                // target is missing/corrupt AND safety is our only good copy — do NOT delete safety,
                // leave the marker armed (by throwing so the Initializer's marker.delete never runs),
                // and signal the hard failure loudly.
                error(
                    "restore rollback failed; live DB restored=false, safety copy preserved at " +
                        safetyCopy.path,
                )
            }
            false
        }
    }

    /**
     * Roll the live [target] back from [safetyCopy] and VERIFY the result (CR-02). Guarded so a
     * failing copy (full disk) or a corrupt safety copy yields `false` rather than an escaping
     * exception, letting [applySwap] distinguish a clean rollback (true) from an unrecoverable state
     * (false → escalate loudly). Left `open` so a JVM test can force the doubly-failing branch.
     *
     * @return true only when the live DB was restored AND passes [verify]; false otherwise.
     */
    protected open fun restoreFromSafety(target: File, safetyCopy: File): Boolean =
        runCatching {
            safetyCopy.copyTo(target, overwrite = true)
            verify(target)
        }.getOrDefault(false)
}

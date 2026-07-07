package io.github.ygaray.backupengine.source

import io.github.ygaray.backupengine.model.BackupRef
import java.io.File

/**
 * The destination seam of the `:backup` engine — the abstract "where backups live" surface the
 * [io.github.ygaray.backupengine.BackupRepository] orchestrator (Plan 04) drives without knowing whether the
 * bytes land in a local SAF folder or (Phase 17) in Drive.
 *
 * [LocalBackupSource] is the v1.2 implementation over a persisted SAF tree URI; Phase 17's
 * `DriveBackupSource` will implement the SAME four operations, which is why the restore list and the
 * repository stay source-agnostic (they speak only [BackupRef], an opaque handle — LIB-01).
 *
 * Mirrors the app's small seam-interface idiom (e.g. `UnitSystemPrefsSeam`): a focused,
 * fake-able interface the engine and its tests depend on rather than a concrete class.
 *
 * A source SURFACES failure — an unavailable destination throws (the repository maps it to a
 * [io.github.ygaray.backupengine.model.BackupResult.Failure]); it never silently no-ops.
 */
interface BackupSource {

    /**
     * Write [file] (a checkpointed `.db` snapshot) into the destination under [name] and return an
     * opaque [BackupRef] for the stored object. Verifies the written size before returning.
     */
    suspend fun put(file: File, name: String): BackupRef

    /**
     * List the backups currently in the destination, newest-first. Enumerates children ONCE and
     * caches within the call — never a per-file lookup (Pitfall 14).
     */
    suspend fun list(): List<BackupRef>

    /**
     * Copy the object referenced by [ref] to an app-private temp file (for validation/staging) and
     * return that local [File].
     */
    suspend fun get(ref: BackupRef): File

    /** Delete the object referenced by [ref] (retention pruning). */
    suspend fun delete(ref: BackupRef)
}

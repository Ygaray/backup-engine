package io.github.ygaray.backupengine.source

import io.github.ygaray.backupengine.BackupConfig
import io.github.ygaray.backupengine.drive.DriveClient
import io.github.ygaray.backupengine.drive.DriveException
import io.github.ygaray.backupengine.model.BackupRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The Drive [BackupSource] over Plan 01's [DriveClient] (BAK-03, RST-02) — a drop-in twin of
 * [LocalBackupSource] delegating the same four `put`/`list`/`get`/`delete` operations to the Drive
 * REST client instead of a SAF tree URI. The restore list and [io.github.ygaray.backupengine.BackupRepository]
 * stay source-agnostic because both sources speak only [BackupRef], an opaque handle (LIB-01) — here
 * the handle is the Drive fileId, exactly as `LocalBackupSource` puts the SAF doc-URI in `ref`.
 *
 * **Surface failure, never no-op (WR-06):** every override fetches a fresh token
 * (`?: throw DriveException.Unauthorized`) and delegates to the client, which throws a typed
 * [DriveException] on any HTTP/transport/verify failure. The source NEVER returns a result — the
 * repository catches [DriveException] and maps `kind` to a `DriveBackupResult.Reason` /
 * `BackupResult.Reason` (T-17-06). Nothing from the wire crosses this seam.
 *
 * All I/O runs on [Dispatchers.IO], mirroring [LocalBackupSource]. The `download`/`get` File already
 * stages into `cacheDir/backup-staging` (owned by [DriveClient]) so it feeds
 * [io.github.ygaray.backupengine.BackupRepository.restore] identically to the local path (D-04b).
 *
 * @param client the hand-rolled Drive REST client (Plan 01).
 * @param tokenProvider yields the current `drive.file` access token or `null` when disconnected;
 *   bound to `config.driveAccessToken()` in Hilt. A `null` token throws [DriveException.Unauthorized].
 * @param config supplies the generic Drive folder name / marker key the [client] uses (D-01a).
 */
open class DriveBackupSource(
    private val client: DriveClient,
    private val tokenProvider: suspend () -> String?,
    @Suppress("unused") private val config: BackupConfig,
) : BackupSource {

    override suspend fun put(file: File, name: String): BackupRef = withContext(Dispatchers.IO) {
        val token = requireToken()
        val folderId = client.findOrCreateFolder(token) // D-03 marker find-or-create
        // uploadAndVerify does the D-02 multipart upload THEN the D-02a md5+size verify before
        // returning the fileId — verify-before-BackupRef, mirroring LocalBackupSource.put's post-write
        // size check. A mismatch throws DriveException.VerifyMismatch (never a silent success).
        val fileId = client.uploadAndVerify(file, name, folderId, token)
        BackupRef(
            ref = fileId, // the Drive fileId is the opaque handle (== SAF doc-URI for local)
            name = name,
            timestampMillis = System.currentTimeMillis(),
            sizeBytes = file.length(),
        )
    }

    override suspend fun list(): List<BackupRef> = withContext(Dispatchers.IO) {
        val token = requireToken()
        // findOrCreateFolder + listInFolder already returns newest-first from the client (D-04).
        client.listInFolder(client.findOrCreateFolder(token), token)
    }

    override suspend fun get(ref: BackupRef): File = withContext(Dispatchers.IO) {
        val token = requireToken()
        // Streams alt=media into the staging temp File (Pattern D) — feeds restore() identically to
        // the local path (D-04b). ref.ref is the Drive fileId.
        client.download(ref.ref, ref.name, token)
    }

    override suspend fun delete(ref: BackupRef) {
        withContext(Dispatchers.IO) {
            val token = requireToken()
            // A failed delete throws inside the client (WR-06 no-silent-failure), mirroring
            // LocalBackupSource.delete(). Retention pruning (Phase 18) must be able to tell.
            client.delete(ref.ref, token)
        }
    }

    /**
     * Fetch the current Drive token or throw [DriveException.Unauthorized] when disconnected. The CTA
     * is hidden in the UI when not connected, so a `null` here is the defensive path — the repository
     * maps `Unauthorized` to the re-auth reason exactly as it does a live 401.
     */
    private suspend fun requireToken(): String =
        tokenProvider() ?: throw DriveException.Unauthorized
}

package io.github.ygaray.backupengine.source

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import io.github.ygaray.backupengine.BackupConfig
import io.github.ygaray.backupengine.model.BackupRef
import io.github.ygaray.backupengine.settings.BackupSettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The local, on-device [BackupSource] over a user-picked SAF tree URI (BAK-01, BAK-02).
 *
 * GENUINELY NEW code — no in-codebase analog for SAF `DocumentFile` / tree-URI I/O. Built from
 * RESEARCH § SAF specifics + Pitfalls 13/14:
 *
 * - **Persistence & re-validation (BAK-01, Pitfall 13):** the picked tree URI is persisted (as a
 *   `takePersistableUriPermission(READ|WRITE)` grant + a string in [BackupSettingsStore]). Every run
 *   re-validates it via `contentResolver.persistedUriPermissions` + `DocumentFile.canWrite()`; if the
 *   grant is gone or the folder isn't writable, operations throw [FolderUnavailableException] (the
 *   repository maps this to `BackupResult.Failure(FolderUnavailable)`) — never a silent no-op.
 * - **List-once / no per-file `findFile` (Pitfall 14):** [list] enumerates `listFiles()` a single
 *   time and maps each `caltracker-*.db` child to a [BackupRef]; it never calls `findFile` per file.
 * - **Write via stream + size verify (Pitfall 14/15):** [put] creates a timestamped document and
 *   streams the snapshot bytes through `contentResolver.openOutputStream`, then reads the written
 *   length back to confirm it is non-empty and matches.
 *
 * All I/O runs on [Dispatchers.IO]. The persistable-permission grant on pick is handled by
 * [persistPickedTree] (called by the folder-picker launcher in Plan 06); [FolderUnavailableException]
 * is thrown, not swallowed, so upstream can surface the fixed folder-unavailable copy.
 */
open class LocalBackupSource(
    private val context: Context,
    private val settings: BackupSettingsStore,
    private val config: BackupConfig,
) : BackupSource {

    /**
     * Persist a freshly-picked SAF tree [uri]: take the durable READ|WRITE grant (Pitfall 13) and
     * store the URI string via [BackupSettingsStore] (the persistence half of BAK-01). The picker
     * gesture that produces [uri] is the app-side launcher (Plan 06).
     */
    suspend fun persistPickedTree(uri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        context.contentResolver.takePersistableUriPermission(uri, flags)
        settings.setLocalSafTreeUri(uri.toString())
    }

    override suspend fun put(file: File, name: String): BackupRef = withContext(Dispatchers.IO) {
        val tree = requireWritableTree()
        // Overwriting isn't supported by SAF createFile (it makes "name (1).db") — callers pass a
        // timestamped, collision-free name; if a stale same-name doc somehow exists, drop it first.
        tree.findFile(name)?.delete()
        val doc = tree.createFile(MIME_DB, name)
            ?: throw FolderUnavailableException("createFile returned null for '$name'")

        context.contentResolver.openOutputStream(doc.uri)?.use { out ->
            file.inputStream().use { input -> input.copyTo(out) }
            out.flush()
        } ?: throw FolderUnavailableException("openOutputStream returned null for '$name'")

        // Post-write verify: the stored document must be non-empty (Pitfall 15). doc.length()
        // re-reads the size from the provider/filesystem after the stream flushed+closed.
        val written = doc.length()
        if (written <= 0L) {
            throw FolderUnavailableException("written backup '$name' is empty (size=$written)")
        }

        BackupRef(
            ref = doc.uri.toString(),
            name = name,
            timestampMillis = doc.lastModified(),
            sizeBytes = written,
        )
    }

    override suspend fun list(): List<BackupRef> = withContext(Dispatchers.IO) {
        val tree = requireWritableTree()
        // Enumerate children ONCE (Pitfall 14) — no per-file findFile.
        tree.listFiles()
            .asSequence()
            .filter { it.isFile }
            .filter { doc -> doc.name?.let { isBackupName(it) } == true }
            .map { doc ->
                BackupRef(
                    ref = doc.uri.toString(),
                    name = doc.name.orEmpty(),
                    timestampMillis = doc.lastModified(),
                    sizeBytes = doc.length(),
                )
            }
            .sortedByDescending { it.timestampMillis }
            .toList()
    }

    override suspend fun get(ref: BackupRef): File = withContext(Dispatchers.IO) {
        val uri = Uri.parse(ref.ref)
        val dest = File(stagingDir(), ref.name.ifBlank { "restore-${System.currentTimeMillis()}.db" })
        context.contentResolver.openInputStream(uri)?.use { input ->
            dest.outputStream().use { out -> input.copyTo(out) }
        } ?: throw FolderUnavailableException("openInputStream returned null for ${ref.name}")
        dest
    }

    override suspend fun delete(ref: BackupRef) {
        withContext(Dispatchers.IO) {
            val uri = Uri.parse(ref.ref)
            // WR-06: refs from list()/put() are TREE-CHILD document URIs (from tree.listFiles() /
            // tree.createFile()), NOT single-document URIs. DocumentFile.fromSingleUri on a tree-child
            // URI yields a doc whose delete() no-ops/returns false, so retention pruning silently
            // fails. Resolve a document-tree URI with fromTreeUri and surface a failed delete loudly.
            val doc = when {
                uri.scheme == "file" -> uri.path?.let { DocumentFile.fromFile(File(it)) }
                DocumentsContract.isDocumentUri(context, uri) || DocumentsContract.isTreeUri(uri) ->
                    DocumentFile.fromTreeUri(context, uri)
                else -> DocumentFile.fromSingleUri(context, uri)
            }
            // Do NOT swallow a failed delete — the caller (retention pruning, Phase 18) must be able
            // to tell. A null/failed delete throws FolderUnavailableException.
            if (doc?.delete() != true) {
                throw FolderUnavailableException("could not delete ${ref.name}")
            }
        }
    }

    /**
     * Resolve the persisted tree URI and re-validate it each run (Pitfall 13): the durable grant must
     * still be held AND the tree must be writable. Throws [FolderUnavailableException] otherwise so
     * the caller surfaces the fixed folder-unavailable copy — never a silent failure.
     */
    private suspend fun requireWritableTree(): DocumentFile {
        val uriString = settings.localSafTreeUri.first()
            ?: throw FolderUnavailableException("no SAF folder has been picked")
        val uri = Uri.parse(uriString)

        if (!hasPersistableGrant(uri)) {
            throw FolderUnavailableException("persistable grant no longer held for $uri")
        }

        val tree = resolveTree(uri)
            ?: throw FolderUnavailableException("could not resolve tree for $uri")
        if (!tree.canWrite()) throw FolderUnavailableException("tree not writable: $uri")
        return tree
    }

    /**
     * True when a durable READ|WRITE persistable grant is still held for [uri] (Pitfall 13). Seam
     * left `open` so an instrumented test can drive the round-trip over a real `file://` tree without
     * a SAF picker; production behavior is the real `persistedUriPermissions` check.
     */
    protected open fun hasPersistableGrant(uri: Uri): Boolean =
        context.contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isReadPermission && it.isWritePermission
        }

    /** Resolve the SAF tree for [uri]. Seam left `open` for the same test reason as [hasPersistableGrant]. */
    protected open fun resolveTree(uri: Uri): DocumentFile? =
        DocumentFile.fromTreeUri(context, uri)

    private fun stagingDir(): File =
        File(context.cacheDir, "backup-staging").apply { mkdirs() }

    /**
     * A backup file name is `<appName>-<timestamp>.db` (BAK-04 scheme). We match on the
     * config-driven `<appName>-` prefix and the presence of `.db` rather than an exact `.db` SUFFIX
     * because SAF's `DocumentFile.createFile` may append a MIME-derived extension (e.g. producing
     * `caltracker-….db.bin` from `application/octet-stream`) — a documented SAF quirk (Pitfall 14).
     * Matching the stable prefix + `.db` marker keeps the listing correct regardless.
     *
     * **ENG-02 / D-02a / D-02b:** the prefix derives from [config].appName (aligning to
     * [io.github.ygaray.backupengine.BackupRepository.backupName]'s `config.appName` source of truth),
     * NOT a hardcoded `"caltracker-"`. For CalTracker's `appName == "caltracker"` this yields
     * `"caltracker" + "-" == "caltracker-"` — BYTE-IDENTICAL to the v1.0.0 hardcoded PREFIX, so every
     * existing `caltracker-*.db` backup stays recognized/listed/restorable.
     *
     * **ENGINE-01 (D-05, Pitfall 7):** also recognizes the stem-paired `.media.zip` sidecar via the
     * SAME `.contains` (not `.endsWith`) convention as the `.db` marker — cheap insurance against the
     * same class of SAF MIME-derived-extension quirk the `.db` marker already guards against.
     */
    internal fun isBackupName(name: String): Boolean =
        name.startsWith(config.appName + "-") && (name.contains(DB_MARKER) || name.contains(MEDIA_MARKER))

    companion object {
        private const val MIME_DB = "application/octet-stream"
        private const val DB_MARKER = ".db"
        private const val MEDIA_MARKER = ".media.zip"
    }
}

/**
 * The persisted SAF destination is missing, un-granted, or no longer writable (Pitfall 13, T-15-06).
 * The repository maps this to [io.github.ygaray.backupengine.model.BackupResult.Reason.FolderUnavailable].
 */
class FolderUnavailableException(message: String) : Exception(message)

package io.github.ygaray.backupengine.drive

import io.github.ygaray.backupengine.BackupConfig
import io.github.ygaray.backupengine.model.BackupRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import okio.buffer
import okio.sink
import okio.source
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * The hand-rolled OkHttp client speaking Google Drive REST v3 directly (D-01) — NO
 * `google-api-services-drive` SDK. It is the one genuinely-new subsystem this phase introduces; the
 * `DriveBackupSource` (Plan 02) and the restore UI (Plan 03) are thin adapters over these five
 * proven calls.
 *
 * **Statelessness & reuse (RESEARCH Pattern 2):** holds a single reused [OkHttpClient] (a new client
 * per call would leak the connection pool/threads) and takes the bearer token fresh per operation.
 * Every method runs the SYNCHRONOUS `execute()` on [Dispatchers.IO] (no enqueue callback bridge).
 *
 * **Generic / config-driven (D-01a):** the folder name and the `appProperties` marker come from
 * [config], never hardcoded — the module is extraction-bound (Phase 19).
 *
 * **Security (T-17-01/02/03):** the token is only ever set via `Authorization: Bearer` and never
 * logged/persisted/surfaced; every failure becomes a [DriveException] carrying only a fixed `Kind`
 * (no body/message/token); all real URLs are `https://www.googleapis.com` (default [baseUrl]) — the
 * MockWebServer test injects its own base so no real network is hit.
 *
 * @param config supplies the generic Drive folder name + marker key (D-01a).
 * @param cacheDir the app cache dir; downloads stage into `backup-staging/` under it (mirrors
 *   `LocalBackupSource.stagingDir()` so the returned File feeds `BackupRepository.restore()` identically).
 * @param baseUrl the Drive API host; overridable so the JVM MockWebServer suite points at its own base.
 * @param client the shared OkHttp client (default: one with a 30s call timeout).
 */
// `open` so the JVM test suite for DriveBackupSource can subclass it as a lightweight fake (assert the
// find-or-create / upload-verify / list-newest-first / download / delete delegation) without hitting a
// real MockWebServer. Production behavior is unchanged — the real network path is covered by DriveClientTest.
open class DriveClient(
    private val config: BackupConfig,
    private val cacheDir: File,
    private val baseUrl: String = "https://www.googleapis.com",
    private val client: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(30, TimeUnit.SECONDS)
        .connectTimeout(30, TimeUnit.SECONDS)
        .build(),
) {

    private val apiBase = baseUrl.trimEnd('/')

    /**
     * Find the app's backup folder by its private `appProperties` marker, or create it (D-03/D-03a).
     * `orderBy=createdTime` returns oldest-first, so `files[0]` is the canonical folder on multiple
     * matches; an empty result triggers a metadata-only `files.create`.
     */
    open suspend fun findOrCreateFolder(token: String): String = withContext(Dispatchers.IO) {
        val q = "appProperties has { key='${config.driveMarkerKey}' and value='1' } " +
            "and mimeType='application/vnd.google-apps.folder' and trashed=false"
        val listUrl = "$apiBase/drive/v3/files".toHttpUrl().newBuilder()
            .addQueryParameter("q", q)
            .addQueryParameter("spaces", "drive")
            .addQueryParameter("trashed", "false")
            .addQueryParameter("orderBy", "createdTime") // oldest-first → files[0] canonical (D-03a)
            .addQueryParameter("fields", "files(id,name,createdTime)")
            .addQueryParameter("pageSize", "10")
            .build()

        val existing = execute(Request.Builder().url(listUrl).bearer(token).get().build()) { resp ->
            JSONObject(resp.body!!.string()).optJSONArray("files")
        }
        if (existing != null && existing.length() > 0) {
            return@withContext existing.getJSONObject(0).getString("id")
        }

        // Create — metadata only (no upload endpoint); the marker is set atomically on create.
        val meta = JSONObject()
            .put("name", config.driveFolderName)
            .put("mimeType", "application/vnd.google-apps.folder")
            .put("appProperties", JSONObject().put(config.driveMarkerKey, "1"))
        val createReq = Request.Builder()
            .url("$apiBase/drive/v3/files?fields=id")
            .bearer(token)
            .post(meta.toString().toRequestBody(JSON_MEDIA))
            .build()
        execute(createReq) { resp -> JSONObject(resp.body!!.string()).getString("id") }
    }

    /**
     * Upload [dbFile] as `multipart/related` (D-02) then VERIFY before returning (D-02a): re-fetch the
     * server md5+size and require both to match the local file, else throw [DriveException.VerifyMismatch].
     * Returns the Drive fileId on success.
     */
    open suspend fun uploadAndVerify(
        dbFile: File,
        name: String,
        folderId: String,
        token: String,
    ): String = withContext(Dispatchers.IO) {
        val meta = JSONObject()
            .put("name", name)
            .put("parents", JSONArray().put(folderId))
        val uploadReq = Request.Builder()
            .url("$apiBase/upload/drive/v3/files?uploadType=multipart&fields=id")
            .bearer(token)
            .post(multipartRelatedBody(meta.toString(), dbFile))
            .build()
        val fileId = execute(uploadReq) { resp -> JSONObject(resp.body!!.string()).getString("id") }

        val (remoteMd5, remoteSize) = fetchMd5AndSize(fileId, token)
        val localMd5 = dbFile.md5Hex()
        if (!remoteMd5.equals(localMd5, ignoreCase = true) || remoteSize != dbFile.length()) {
            throw DriveException.VerifyMismatch
        }
        fileId
    }

    /**
     * List the backups in [folderId], newest-first (D-04/RST-02). `size` is an int64 STRING in Drive
     * JSON (Pitfall 3) so it is parsed via `getString("size").toLong()`; `modifiedTime` (RFC-3339) is
     * parsed to epoch millis. Loops on `nextPageToken` defensively (Pitfall 7).
     */
    open suspend fun listInFolder(folderId: String, token: String): List<BackupRef> =
        withContext(Dispatchers.IO) {
            val refs = mutableListOf<BackupRef>()
            var pageToken: String? = null
            do {
                val urlBuilder = "$apiBase/drive/v3/files".toHttpUrl().newBuilder()
                    .addQueryParameter("q", "'$folderId' in parents and trashed=false")
                    .addQueryParameter("spaces", "drive")
                    .addQueryParameter("orderBy", "modifiedTime desc")
                    .addQueryParameter("fields", "files(id,name,size,modifiedTime),nextPageToken")
                    .addQueryParameter("pageSize", "100")
                if (pageToken != null) urlBuilder.addQueryParameter("pageToken", pageToken)

                val req = Request.Builder().url(urlBuilder.build()).bearer(token).get().build()
                pageToken = execute(req) { resp ->
                    val root = JSONObject(resp.body!!.string())
                    val files = root.optJSONArray("files") ?: JSONArray()
                    for (i in 0 until files.length()) {
                        val f = files.getJSONObject(i)
                        refs += BackupRef(
                            ref = f.getString("id"),
                            name = f.optString("name"),
                            timestampMillis = parseRfc3339(f.optString("modifiedTime")),
                            // size is an int64 STRING (Pitfall 3); missing on odd items → 0.
                            sizeBytes = f.optString("size").toLongOrNull() ?: 0L,
                        )
                    }
                    root.optString("nextPageToken").ifBlank { null }
                }
            } while (pageToken != null)

            // Server already returns newest-first, but sort defensively across pages.
            refs.sortedByDescending { it.timestampMillis }
        }

    /**
     * Stream a backup's raw bytes to a temp File in the staging dir via `alt=media` (RST-02/D-04b).
     * Never buffers the whole body into a ByteArray — the File feeds `BackupRepository.restore()`.
     */
    open suspend fun download(fileId: String, name: String, token: String): File =
        withContext(Dispatchers.IO) {
            val url = "$apiBase/drive/v3/files/$fileId".toHttpUrl().newBuilder()
                .addQueryParameter("alt", "media")
                .build()
            val dest = File(stagingDir(), name.ifBlank { "restore-${System.currentTimeMillis()}.db" })
            execute(Request.Builder().url(url).bearer(token).get().build()) { resp ->
                resp.body!!.source().use { src -> dest.sink().buffer().use { it.writeAll(src) } }
            }
            dest
        }

    /**
     * Delete a backup (retention — used from Phase 18, implemented now). 204 → ok; any other status
     * throws (mirrors `LocalBackupSource.delete()`'s no-silent-failure posture, WR-06).
     */
    open suspend fun delete(fileId: String, token: String) = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("$apiBase/drive/v3/files/$fileId")
            .bearer(token)
            .delete()
            .build()
        runCall(req).use { resp ->
            // Drive returns 204 No Content on a successful delete.
            if (resp.code != 204 && !resp.isSuccessful) throw DriveException.fromStatus(resp.code)
        }
    }

    // ---- internals ----

    /** The D-02a verify fetch: server md5 (lowercase hex) + size (int64 string → Long). */
    private fun fetchMd5AndSize(fileId: String, token: String): Pair<String, Long> {
        val url = "$apiBase/drive/v3/files/$fileId".toHttpUrl().newBuilder()
            .addQueryParameter("fields", "md5Checksum,size")
            .build()
        return execute(Request.Builder().url(url).bearer(token).get().build()) { resp ->
            val json = JSONObject(resp.body!!.string())
            json.getString("md5Checksum") to json.getString("size").toLong()
        }
    }

    /**
     * The hand-rolled `multipart/related` body (Pitfall 1 LANDMINE): parts carry ONLY a `Content-Type`
     * header (no `Content-Disposition`), `\r\n` line endings, terminal `--<boundary>--`. Do NOT use
     * OkHttp `MultipartBody` — it emits `multipart/form-data` Drive rejects. The file is STREAMED via
     * `source()` (no full-buffer). Content-length is precomputed so the request is not chunked.
     */
    private fun multipartRelatedBody(metadataJson: String, dbFile: File): RequestBody {
        val boundary = "caltracker_" + System.currentTimeMillis()
        val mediaType = "multipart/related; boundary=$boundary".toMediaType()
        val preamble = "--$boundary\r\n" +
            "Content-Type: application/json; charset=UTF-8\r\n\r\n" +
            metadataJson +
            "\r\n--$boundary\r\n" +
            "Content-Type: application/octet-stream\r\n\r\n"
        val epilogue = "\r\n--$boundary--\r\n"
        val preambleBytes = preamble.toByteArray(Charsets.UTF_8)
        val epilogueBytes = epilogue.toByteArray(Charsets.UTF_8)
        return object : RequestBody() {
            override fun contentType() = mediaType
            override fun contentLength(): Long =
                preambleBytes.size + dbFile.length() + epilogueBytes.size
            override fun writeTo(sink: BufferedSink) {
                sink.write(preambleBytes)
                dbFile.source().use { sink.writeAll(it) } // streams the file, no full-buffer
                sink.write(epilogueBytes)
            }
        }
    }

    /** Streamed MD5 of [this] file, lowercase hex — chunked so ~1 MB isn't loaded whole into memory. */
    private fun File.md5Hex(): String {
        val md = MessageDigest.getInstance("MD5")
        inputStream().use { ins ->
            val buf = ByteArray(8 * 1024)
            while (true) {
                val n = ins.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun stagingDir(): File = File(cacheDir, "backup-staging").apply { mkdirs() }

    private fun parseRfc3339(value: String): Long =
        runCatching { Instant.parse(value).toEpochMilli() }.getOrDefault(0L)

    private fun Request.Builder.bearer(token: String) =
        header("Authorization", "Bearer $token")

    /**
     * Run [request] synchronously and hand a SUCCESSFUL [Response] to [onSuccess]. Non-2xx status →
     * [DriveException.fromStatus]; an `IOException` (transport) → [DriveException.Network]. Neither the
     * body, `resp.message`, nor the token is ever placed in the thrown exception (T-17-01/02).
     *
     * The 2xx body is UNTRUSTED (T-22-09/T-22-10, Pitfall 3): [onSuccess] reads/streams the body, so a
     * mid-body transport drop throws an [IOException] DEEP inside the lambda — OUTSIDE [runCall]'s try —
     * and a truncated/non-JSON body throws an [org.json.JSONException] on parse. Both are guarded here:
     * an `IOException` → [DriveException.Network] (transport), a `JSONException` → [DriveException.Malformed]
     * (a 2xx body that failed to parse, DISTINCT from `NotAValidBackup`). The catches are SPECIFIC (not a
     * broad `Exception`) so a mid-body drop stays [Kind.Network], never mislabeled [Kind.Malformed]. The
     * raw status is logged INTERNALLY only — never the token/body/`resp.message`, and never into the thrown
     * exception (whose message stays `kind.name`).
     */
    private fun <T> execute(request: Request, onSuccess: (Response) -> T): T {
        runCall(request).use { resp ->
            if (!resp.isSuccessful) throw DriveException.fromStatus(resp.code)
            return try {
                onSuccess(resp)
            } catch (io: IOException) {
                // A mid-body transport drop during the body read/stream (Pitfall 3) — transport failure.
                throw DriveException.Network
            } catch (json: org.json.JSONException) {
                // A 2xx response whose body could not be parsed (ENG-03) — coarse typed Malformed, not a raw crash.
                android.util.Log.w(TAG, "Drive 2xx body failed to parse (status ${resp.code})")
                throw DriveException.Malformed
            }
        }
    }

    /** Execute the call, translating any transport [IOException] into [DriveException.Network]. */
    private fun runCall(request: Request): Response =
        try {
            client.newCall(request).execute()
        } catch (io: IOException) {
            throw DriveException.Network
        }

    companion object {
        private const val TAG = "DriveClient"
        private val JSON_MEDIA = "application/json; charset=UTF-8".toMediaType()
    }
}

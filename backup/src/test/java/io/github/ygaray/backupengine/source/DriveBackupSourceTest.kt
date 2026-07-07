package io.github.ygaray.backupengine.source

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.test.core.app.ApplicationProvider
import io.github.ygaray.backupengine.BackupConfig
import io.github.ygaray.backupengine.drive.DriveClient
import io.github.ygaray.backupengine.drive.DriveException
import io.github.ygaray.backupengine.model.BackupRef
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * JVM unit suite for [DriveBackupSource] — the Drive [BackupSource] twin of [LocalBackupSource]
 * (BAK-03, RST-02). Uses a lightweight FAKE [DriveClient] (subclassed — the client is `open`) plus a
 * fake token provider so the four ops are proven to delegate correctly with NO real network and NO
 * MockWebServer: put→find-or-create+upload-verify, list newest-first, get→staged temp File, delete
 * throwing on failure, and a `null` token short-circuiting to [DriveException.Unauthorized].
 */
@RunWith(RobolectricTestRunner::class)
class DriveBackupSourceTest {

    private lateinit var ctx: Context
    private lateinit var scratch: File
    private lateinit var client: FakeDriveClient
    private var token: String? = "tok-123"

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        scratch = File(ctx.cacheDir, "drivesrc-${System.nanoTime()}").apply { mkdirs() }
        client = FakeDriveClient(scratch)
        token = "tok-123"
    }

    private fun newSource(): DriveBackupSource =
        DriveBackupSource(client, tokenProvider = { token }, config = FakeBackupConfig)

    // --- put: token → find-or-create → upload+verify → BackupRef(fileId) ---

    @Test
    fun `put finds-or-creates the folder, uploads-and-verifies, and returns a ref holding the fileId`() = runBlocking {
        val snapshot = File(scratch, "snapshot.db").apply { writeText("db-bytes") }

        val ref = newSource().put(snapshot, "caltracker-2026-07-02_1200.db")

        assertEquals("folder resolved via findOrCreateFolder", 1, client.findOrCreateCalls)
        assertEquals("uploadAndVerify invoked once", 1, client.uploaded.size)
        val (upFile, upName, upFolder) = client.uploaded.single()
        assertEquals("caltracker-2026-07-02_1200.db", upName)
        assertEquals("folder-id", upFolder)
        assertEquals(snapshot.length(), upFile.length())
        // ref.ref carries the opaque Drive fileId (the handle get()/delete() resolve back), like the
        // local source puts the SAF doc-URI in ref.
        assertEquals("verified-file-id", ref.ref)
        assertEquals("caltracker-2026-07-02_1200.db", ref.name)
        assertEquals(snapshot.length(), ref.sizeBytes)
    }

    @Test
    fun `put propagates a verify mismatch as DriveException (never a silent success)`() = runBlocking {
        client.uploadThrows = DriveException.VerifyMismatch
        val snapshot = File(scratch, "snapshot.db").apply { writeText("db-bytes") }

        try {
            newSource().put(snapshot, "caltracker-x.db")
            fail("expected a DriveException to surface, not a silent success")
        } catch (e: DriveException) {
            assertEquals(DriveException.Kind.VerifyMismatch, e.kind)
        }
    }

    // --- list: delegates to findOrCreate + listInFolder, newest-first from the client ---

    @Test
    fun `list returns the client's newest-first refs for the marked folder`() = runBlocking {
        client.listResult = listOf(
            BackupRef("id-new", "caltracker-2026-07-02_1200.db", 2_000L, 10L),
            BackupRef("id-old", "caltracker-2026-07-01_0900.db", 1_000L, 10L),
        )

        val refs = newSource().list()

        assertEquals(1, client.findOrCreateCalls)
        assertEquals("folder-id", client.listedFolder)
        assertEquals(listOf("id-new", "id-old"), refs.map { it.ref })
    }

    // --- get: streams the chosen ref to a staged temp File (feeds restore identically) ---

    @Test
    fun `get downloads the ref to a staged temp File`() = runBlocking {
        val ref = BackupRef("file-77", "caltracker-2026-07-02_1200.db", 0L, 0L)

        val staged = newSource().get(ref)

        assertEquals("file-77", client.downloadedId)
        assertNotNull(staged)
        assertTrue("staged file exists on disk", staged.exists())
        assertEquals("db-payload", staged.readText())
    }

    // --- delete: a failed delete throws (no silent failure, WR-06) ---

    @Test
    fun `delete throws when the client delete fails`() = runBlocking {
        client.deleteThrows = DriveException.fromStatus(404)
        val ref = BackupRef("gone-id", "caltracker-x.db", 0L, 0L)

        try {
            newSource().delete(ref)
            fail("a failed delete must surface, not no-op")
        } catch (e: DriveException) {
            assertEquals(DriveException.Kind.NotFound, e.kind)
        }
    }

    @Test
    fun `delete delegates the fileId to the client on success`() = runBlocking {
        val ref = BackupRef("del-id", "caltracker-x.db", 0L, 0L)

        newSource().delete(ref)

        assertEquals("del-id", client.deletedId)
    }

    // --- null token → Unauthorized on every op, before any client call ---

    @Test
    fun `a null token short-circuits put to Unauthorized without touching the client`() = runBlocking {
        token = null
        val snapshot = File(scratch, "snapshot.db").apply { writeText("db-bytes") }

        try {
            newSource().put(snapshot, "caltracker-x.db")
            fail("expected Unauthorized when disconnected")
        } catch (e: DriveException) {
            assertEquals(DriveException.Kind.Unauthorized, e.kind)
            assertEquals("no client call on a null token", 0, client.findOrCreateCalls)
        }
    }

    @Test
    fun `a null token short-circuits list to Unauthorized`() = runBlocking {
        token = null
        try {
            newSource().list()
            fail("expected Unauthorized")
        } catch (e: DriveException) {
            assertEquals(DriveException.Kind.Unauthorized, e.kind)
        }
    }

    // --- test doubles ---

    /**
     * A lightweight [DriveClient] fake (the client is `open`) recording delegation and returning fixed
     * handles. Constructed with `config`/`cacheDir` only to satisfy the base ctor — no method here
     * touches the network.
     */
    private class FakeDriveClient(cacheDir: File) : DriveClient(FakeBackupConfig, cacheDir) {
        var findOrCreateCalls = 0
        val uploaded = mutableListOf<Triple<File, String, String>>()
        var uploadThrows: DriveException? = null
        var listResult: List<BackupRef> = emptyList()
        var listedFolder: String? = null
        var downloadedId: String? = null
        var deletedId: String? = null
        var deleteThrows: DriveException? = null
        private val stagingDir = File(cacheDir, "backup-staging").apply { mkdirs() }

        override suspend fun findOrCreateFolder(token: String): String {
            findOrCreateCalls++
            return "folder-id"
        }

        override suspend fun uploadAndVerify(dbFile: File, name: String, folderId: String, token: String): String {
            uploadThrows?.let { throw it }
            uploaded += Triple(dbFile, name, folderId)
            return "verified-file-id"
        }

        override suspend fun listInFolder(folderId: String, token: String): List<BackupRef> {
            listedFolder = folderId
            return listResult
        }

        override suspend fun download(fileId: String, name: String, token: String): File {
            downloadedId = fileId
            return File(stagingDir, name).apply { writeText("db-payload") }
        }

        override suspend fun delete(fileId: String, token: String) {
            deleteThrows?.let { throw it }
            deletedId = fileId
        }
    }

    /** A minimal [BackupConfig] fake — only the Drive folder/marker defaults are exercised here. */
    private object FakeBackupConfig : BackupConfig {
        override val appName: String = "caltracker"
        override val databaseFile: File = File("unused.db")
        override val currentSchemaVersion: Int = 5
        override val dataStore: DataStore<Preferences> = InMemoryDataStore()
    }

    private class InMemoryDataStore : DataStore<Preferences> {
        private val flow = MutableStateFlow(emptyPreferences())
        override val data: Flow<Preferences> = flow
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            val updated = transform(flow.value)
            flow.value = updated
            return updated
        }
    }
}

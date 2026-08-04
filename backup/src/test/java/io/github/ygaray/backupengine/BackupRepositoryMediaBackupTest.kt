package io.github.ygaray.backupengine

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.test.core.app.ApplicationProvider
import io.github.ygaray.backupengine.model.BackupRef
import io.github.ygaray.backupengine.model.BackupResult
import io.github.ygaray.backupengine.model.DriveBackupResult
import io.github.ygaray.backupengine.model.MediaWarning
import io.github.ygaray.backupengine.settings.BackupSettingsStore
import io.github.ygaray.backupengine.source.BackupSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * TDD spec for the gated media BACKUP leg (ENGINE-01, D-01/D-02/D-05, plan 75-03 Task 1).
 *
 * Proves:
 *  - `config.mediaDirectories.isNotEmpty()` gates a paired `<stem>.media.zip` sidecar through the
 *    UNMODIFIED `BackupSource.put` on BOTH destinations, same stem as the just-written `.db`;
 *  - an empty `mediaDirectories` (CalTracker default) issues ZERO media puts and never touches the
 *    durable `mediaBackupWarning` flag — byte-identical to pre-phase behavior;
 *  - a forced media failure still returns `Success` carrying a non-null `MediaWarning`, with the
 *    durable `mediaBackupWarning` flag armed, and the DB leg's recorded status untouched (D-02);
 *  - retention prune deletes a `.db` and its paired `.media.zip` together by stem (D-05), never
 *    orphaning one (proven directly against `BackupRepository.prune`, mirroring `RetentionPruneTest`).
 */
@RunWith(RobolectricTestRunner::class)
class BackupRepositoryMediaBackupTest {

    private lateinit var scratch: File
    private lateinit var liveDb: File
    private lateinit var source: FakeBackupSource
    private lateinit var driveSource: FakeBackupSource
    private lateinit var settings: BackupSettingsStore

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        scratch = File(ctx.cacheDir, "media-backup-${System.nanoTime()}").apply { mkdirs() }
        // A REAL SQLite live DB — backup() runs DatabaseFileManager.snapshot(), which would short-circuit
        // to WriteFailed before the media leg under test ever runs against a non-SQLite stub file.
        liveDb = File(scratch, "caltracker.db")
        android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(liveDb, null).use { h ->
            h.execSQL("CREATE TABLE IF NOT EXISTS notes (id INTEGER PRIMARY KEY)")
        }
        source = FakeBackupSource()
        driveSource = FakeBackupSource()
        settings = BackupSettingsStore(InMemoryDataStore())
    }

    private fun mediaDir(): File =
        File(scratch, "album_images").apply {
            mkdirs()
            File(this, "photo.jpg").writeText("fake-photo-bytes")
        }

    private fun repoWith(mediaDirs: List<File> = emptyList()): BackupRepository {
        val config = FakeBackupConfig(liveDb, mediaDirs)
        return BackupRepository(
            ApplicationProvider.getApplicationContext(),
            config,
            DatabaseFileManager(),
            source,
            driveSource,
            settings,
        )
    }

    @Test
    fun `empty mediaDirectories issues zero media puts and never touches the warning flag`() = runBlocking {
        val repo = repoWith(mediaDirs = emptyList())

        val result = repo.backup()

        assertTrue("DB backup must still succeed", result is BackupResult.Success)
        assertEquals("only the .db put — no media leg for an empty mediaDirectories", 1, source.put.size)
        assertNull("no media leg ran, so mediaWarning must stay null", (result as BackupResult.Success).mediaWarning)
        assertFalse("an empty mediaDirectories must never touch mediaBackupWarning", settings.mediaBackupWarning.first())
    }

    @Test
    fun `non-empty mediaDirectories produces a stem-paired local sidecar`() = runBlocking {
        val repo = repoWith(mediaDirs = listOf(mediaDir()))

        val result = repo.backup()

        assertTrue("DB backup must succeed", result is BackupResult.Success)
        assertNull("a healthy media leg carries no warning", (result as BackupResult.Success).mediaWarning)
        assertEquals("exactly one .db put + one .media.zip put", 2, source.put.size)
        val dbName = source.put[0].second
        val mediaName = source.put[1].second
        assertTrue("db name uses the .db scheme", dbName.endsWith(".db"))
        assertEquals(
            "the media sidecar must share the EXACT stem the .db was written under",
            dbName.removeSuffix(".db") + ".media.zip",
            mediaName,
        )
        assertFalse("a successful media leg clears any prior warning", settings.mediaBackupWarning.first())
    }

    @Test
    fun `non-empty mediaDirectories produces a stem-paired Drive sidecar`() = runBlocking {
        val repo = repoWith(mediaDirs = listOf(mediaDir()))

        val result = repo.backupToDrive()

        assertTrue("Drive backup must succeed", result is DriveBackupResult.Success)
        assertNull("a healthy media leg carries no warning", (result as DriveBackupResult.Success).mediaWarning)
        assertEquals("exactly one .db put + one .media.zip put on the DRIVE source", 2, driveSource.put.size)
        assertEquals("the local source is untouched by a Drive backup", 0, source.put.size)
        val dbName = driveSource.put[0].second
        val mediaName = driveSource.put[1].second
        assertEquals(dbName.removeSuffix(".db") + ".media.zip", mediaName)
    }

    @Test
    fun `a forced media failure still returns Success with a MediaWarning and never fails the DB leg`() = runBlocking {
        // Two directories sharing the same File.name force MediaArchiveManager.snapshot() to fail its
        // uniqueness require() (D-03a's own documented failure mode) — a real, deterministic media
        // failure with no fake/mock hook needed.
        val dupA = File(scratch, "dupParentA/media").apply { mkdirs() }
        val dupB = File(scratch, "dupParentB/media").apply { mkdirs() }
        val repo = repoWith(mediaDirs = listOf(dupA, dupB))

        val result = repo.backup()

        assertTrue(
            "a media-leg failure must NEVER flip the verified DB Success to Failure (D-02)",
            result is BackupResult.Success,
        )
        assertEquals(
            "the surfaced warning must be the fixed, text-free MediaWarning marker",
            MediaWarning,
            (result as BackupResult.Success).mediaWarning,
        )
        assertTrue(
            "the durable mediaBackupWarning flag must be armed on a media-leg failure (D-02)",
            settings.mediaBackupWarning.first(),
        )
        assertEquals("the .db put still happened — only the media put/snapshot failed", 1, source.put.size)
    }

    @Test
    fun `retention prune deletes a stem-paired media zip together with its db, never orphaning one`() = runBlocking {
        val repo = repoWith(mediaDirs = listOf(mediaDir()))
        // 3 backup-op pairs, newest-first; keepN=1 keeps only the newest pair.
        val pairs = (0 until 3).flatMap { i ->
            val stem = "caltracker-$i"
            listOf(
                BackupRef(ref = "db$i", name = "$stem.db", timestampMillis = (3 - i).toLong(), sizeBytes = 1L),
                BackupRef(ref = "media$i", name = "$stem.media.zip", timestampMillis = (3 - i).toLong(), sizeBytes = 1L),
            )
        }
        source.listValue = pairs

        repo.prune(source, keepN = 1)

        assertEquals(
            "keepN=1 over 3 pairs deletes BOTH files of the oldest 2 pairs, never orphaning either half",
            setOf("db1", "media1", "db2", "media2"),
            source.deleted.map { it.ref }.toSet(),
        )
        assertTrue(
            "the newest pair (stem 0) must never be a delete candidate",
            source.deleted.none { it.ref == "db0" || it.ref == "media0" },
        )
    }

    // --- fakes (mirrors RetentionPruneTest's / BackupRepositoryPruneWarningTest's scaffold) ---

    private class FakeBackupSource : BackupSource {
        val put = mutableListOf<Pair<File, String>>()
        val deleted = mutableListOf<BackupRef>()
        var listValue: List<BackupRef> = emptyList()

        override suspend fun put(file: File, name: String): BackupRef {
            put += file to name
            return BackupRef(ref = "fake://$name", name = name, timestampMillis = 0L, sizeBytes = file.length())
        }

        override suspend fun list(): List<BackupRef> = listValue

        override suspend fun get(ref: BackupRef): File = error("not used")

        override suspend fun delete(ref: BackupRef) {
            deleted += ref
        }
    }

    private class FakeBackupConfig(
        override val databaseFile: File,
        override val mediaDirectories: List<File>,
    ) : BackupConfig {
        override val appName: String = "caltracker"
        override val currentSchemaVersion: Int = 5
        override val dataStore: DataStore<Preferences> = InMemoryDataStore()
    }

    private class InMemoryDataStore : DataStore<Preferences> {
        private val flow = MutableStateFlow(emptyPreferences())
        override val data: Flow<Preferences> = flow
        override suspend fun updateData(
            transform: suspend (t: Preferences) -> Preferences,
        ): Preferences {
            val updated = transform(flow.value)
            flow.value = updated
            return updated
        }
    }
}

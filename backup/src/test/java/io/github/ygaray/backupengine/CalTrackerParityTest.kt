package io.github.ygaray.backupengine

import android.database.sqlite.SQLiteDatabase
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.test.core.app.ApplicationProvider
import io.github.ygaray.backupengine.model.BackupRef
import io.github.ygaray.backupengine.model.BackupResult
import io.github.ygaray.backupengine.model.DriveBackupResult
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
 * The CalTracker-parity proof (ENGINE-04, D-06, plan 75-05 Task 1).
 *
 * CalTracker's real [BackupConfig] implementation never overrides `mediaDirectories` — this
 * [FakeBackupConfig] deliberately mirrors that EXACTLY: it does not declare a `mediaDirectories`
 * override at all, so it resolves to the interface's own `emptyList()` default (D-06). Every
 * assertion below is therefore the concrete byte-identity proof that CalTracker's actual backup/
 * restore behavior is unaffected by ENGINE-01/02/03 landing:
 *  - [BackupRepository.backup] / [BackupRepository.backupToDrive] issue exactly ONE `.db` put and
 *    ZERO `.media.zip` puts, never touch [BackupSettingsStore.mediaBackupWarning], and return a
 *    `Success` whose `mediaWarning` stays `null`.
 *  - [BackupRepository.restore] never lists/fetches a media sidecar and never arms
 *    [BackupSettingsStore.mediaRestorePending].
 *
 * Mirrors [RetentionPruneTest] / [BackupRepositoryPruneWarningTest] / [BackupRepositoryMediaRestoreTest]'s
 * existing fake scaffold conventions — no new fixture idiom introduced.
 */
@RunWith(RobolectricTestRunner::class)
class CalTrackerParityTest {

    private lateinit var scratch: File
    private lateinit var liveDb: File
    private lateinit var ctx: android.content.Context

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        scratch = File(ctx.cacheDir, "parity-${System.nanoTime()}").apply { mkdirs() }
        liveDb = File(scratch, "caltracker.db")
        SQLiteDatabase.openOrCreateDatabase(liveDb, null).use { db ->
            db.execSQL("CREATE TABLE IF NOT EXISTS t (id INTEGER PRIMARY KEY)")
            db.version = 1
        }
    }

    // --- backup() / backupToDrive(): only a .db put, never a .media.zip, warning never armed ---

    @Test
    fun `backup with default mediaDirectories issues exactly one db put and zero media zip puts`() = runBlocking {
        val source = CapturingBackupSource()
        val driveSource = CapturingBackupSource()
        val settings = BackupSettingsStore(InMemoryDataStore())
        val repo = BackupRepository(ctx, FakeBackupConfig(liveDb), DatabaseFileManager(), source, driveSource, settings)

        val result = repo.backup()

        assertTrue("a valid DB snapshot must succeed", result is BackupResult.Success)
        assertEquals("exactly one .db put, no sidecar", 1, source.putNames.size)
        assertTrue(".db-only put", source.putNames.single().endsWith(".db"))
        assertTrue("zero .media.zip puts", source.putNames.none { it.endsWith(".media.zip") })
        assertTrue("the drive source must be untouched by a local backup", driveSource.putNames.isEmpty())
        assertFalse("mediaBackupWarning must never be armed", settings.mediaBackupWarning.first())
        assertNull("Success.mediaWarning must stay null", (result as BackupResult.Success).mediaWarning)
    }

    @Test
    fun `backupToDrive with default mediaDirectories issues exactly one db put and zero media zip puts`() = runBlocking {
        val source = CapturingBackupSource()
        val driveSource = CapturingBackupSource()
        val settings = BackupSettingsStore(InMemoryDataStore())
        val repo = BackupRepository(ctx, FakeBackupConfig(liveDb), DatabaseFileManager(), source, driveSource, settings)

        val result = repo.backupToDrive()

        assertTrue("a valid DB snapshot must succeed", result is DriveBackupResult.Success)
        assertEquals("exactly one .db put on the Drive source, no sidecar", 1, driveSource.putNames.size)
        assertTrue(".db-only put", driveSource.putNames.single().endsWith(".db"))
        assertTrue("zero .media.zip puts", driveSource.putNames.none { it.endsWith(".media.zip") })
        assertTrue("the local source must be untouched by a Drive backup", source.putNames.isEmpty())
        assertFalse("mediaBackupWarning must never be armed", settings.mediaBackupWarning.first())
        assertNull("Success.mediaWarning must stay null", (result as DriveBackupResult.Success).mediaWarning)
    }

    // --- restore(): never lists/fetches a media sidecar, never arms mediaRestorePending ---

    @Test
    fun `restore with default mediaDirectories never lists or fetches a media sidecar and never arms the flag`() = runBlocking {
        val source = CapturingRestoreSource(scratch)
        val settings = BackupSettingsStore(InMemoryDataStore())
        val repo = BackupRepository(ctx, FakeBackupConfig(liveDb), DatabaseFileManager(), source, source, settings)
        val ref = BackupRef(ref = "candidate", name = "caltracker-restore.db", timestampMillis = 1L, sizeBytes = 1L)

        val result = repo.restore(ref)

        assertTrue(
            "this fake always declines restart -> deterministic WriteFailed, proving the DB leg ran to completion",
            result is BackupResult.Failure,
        )
        assertEquals(BackupResult.Reason.WriteFailed, (result as BackupResult.Failure).reason)
        assertEquals("an empty mediaDirectories must never even list() the source for a sidecar", 0, source.listCallCount)
        assertTrue("no media get() call for an empty mediaDirectories", source.getCalls.none { it.name.endsWith(".media.zip") })
        assertFalse("mediaRestorePending must never be armed", settings.mediaRestorePending.first())
    }

    // --- fakes (mirrors RetentionPruneTest / BackupRepositoryMediaRestoreTest scaffolds) ---

    private class CapturingBackupSource : BackupSource {
        val putNames = mutableListOf<String>()

        override suspend fun put(file: File, name: String): BackupRef {
            putNames += name
            return BackupRef(ref = "fake://$name", name = name, timestampMillis = 0L, sizeBytes = file.length())
        }

        override suspend fun list(): List<BackupRef> = emptyList()

        override suspend fun get(ref: BackupRef): File = error("not used")

        override suspend fun delete(ref: BackupRef) = Unit
    }

    private class CapturingRestoreSource(private val root: File) : BackupSource {
        var listCallCount = 0
        val getCalls = mutableListOf<BackupRef>()

        override suspend fun put(file: File, name: String): BackupRef =
            BackupRef(ref = "fake://$name", name = name, timestampMillis = 0L, sizeBytes = file.length())

        override suspend fun list(): List<BackupRef> {
            listCallCount++
            return emptyList()
        }

        override suspend fun get(ref: BackupRef): File {
            getCalls += ref
            val candidate = File(root, "fetched-${ref.ref}.db")
            SQLiteDatabase.openOrCreateDatabase(candidate, null).use { db ->
                db.execSQL("CREATE TABLE IF NOT EXISTS t (id INTEGER PRIMARY KEY)")
                db.version = 1
            }
            return candidate
        }

        override suspend fun delete(ref: BackupRef) = Unit
    }

    /**
     * CalTracker's real `BackupConfig` implementation never overrides `mediaDirectories` —
     * deliberately NOT declared here either, so it resolves to the interface's own `emptyList()`
     * default (D-06). `restartApp` is overridden ONLY to keep this JVM test deterministic (the
     * interface default calls `context.startActivity` + `Runtime.getRuntime().exit(0)`, unsafe
     * under Robolectric) — unrelated to the mediaDirectories parity claim under test, mirroring
     * [BackupRepositoryMediaRestoreTest]'s identical rationale.
     */
    private class FakeBackupConfig(override val databaseFile: File) : BackupConfig {
        override val appName: String = "caltracker"
        override val currentSchemaVersion: Int = 5
        override val dataStore: DataStore<Preferences> = InMemoryDataStore()
        override fun restartApp(context: android.content.Context): Boolean = false
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

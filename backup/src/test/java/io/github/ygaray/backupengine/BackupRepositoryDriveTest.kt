package io.github.ygaray.backupengine

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.test.core.app.ApplicationProvider
import io.github.ygaray.backupengine.drive.DriveException
import io.github.ygaray.backupengine.model.BackupRef
import io.github.ygaray.backupengine.model.BackupResult
import io.github.ygaray.backupengine.model.DriveBackupResult
import io.github.ygaray.backupengine.settings.BackupSettingsStore
import io.github.ygaray.backupengine.settings.DriveBackupStatus
import io.github.ygaray.backupengine.source.BackupSource
import io.github.ygaray.backupengine.startup.RestoreSwapInitializer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * JVM suite for [BackupRepository]'s Drive entry points (BAK-03, RST-02) — proves:
 *  - `backupToDrive()` snapshots→puts→records Drive status and returns [DriveBackupResult.Success];
 *  - EVERY [DriveException.Kind] maps to the right [DriveBackupResult.Reason] and NEVER rethrows (T-17-06);
 *  - `listDriveBackups()` is guarded (a throwing source yields an empty list, T-17-08);
 *  - a downloaded Drive candidate flows through the UNCHANGED validate→safety-copy→swap→restart engine
 *    (D-04b) — restore convergence, not a second restore path.
 *
 * Reuses the same on-disk scratch-DB + fake-config setup shape as [BackupRepositoryTest].
 */
@RunWith(RobolectricTestRunner::class)
class BackupRepositoryDriveTest {

    private lateinit var ctx: Context
    private lateinit var scratch: File
    private lateinit var liveDb: File
    private lateinit var local: FakeSource
    private lateinit var drive: FakeSource
    private lateinit var config: FakeConfig
    private lateinit var settings: BackupSettingsStore
    private lateinit var repo: BackupRepository

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        scratch = File(ctx.cacheDir, "brepodrive-${System.nanoTime()}").apply { mkdirs() }
        liveDb = File(scratch, "caltracker.db")
        seedDb(liveDb, version = 5)
        local = FakeSource(scratch)
        drive = FakeSource(scratch)
        settings = BackupSettingsStore(InMemoryDataStore())
        config = FakeConfig(liveDb, currentSchemaVersion = 5)
        repo = BackupRepository(ctx, config, DatabaseFileManager(), local, drive, settings)
    }

    // --- backupToDrive: success records Drive status ---

    @Test
    fun `backupToDrive puts a timestamped snapshot and records a Drive success`() = runBlocking {
        val result = repo.backupToDrive()

        assertEquals(DriveBackupResult.Success, result)
        assertEquals(1, drive.put.size)
        assertTrue("Drive name follows the caltracker- scheme", drive.put.single().second.startsWith("caltracker-"))
        assertTrue("Drive status recorded as success", settings.readDriveOk() == true)
        // The LOCAL source is untouched by a Drive backup.
        assertEquals(0, local.put.size)
    }

    // --- backupToDrive: every DriveException.Kind maps to a distinct Reason, never throws ---

    @Test
    fun `backupToDrive maps a 401 to NeedsReauth and never throws`() = runBlocking {
        drive.putThrows = DriveException.Unauthorized
        val result = repo.backupToDrive()
        assertEquals(DriveBackupResult.Failure(DriveBackupResult.Reason.NeedsReauth), result)
        assertTrue(settings.readDriveOk() == false)
    }

    @Test
    fun `backupToDrive maps a transport IOException (Network) to NoNetwork`() = runBlocking {
        drive.putThrows = DriveException.Network
        val result = repo.backupToDrive()
        assertEquals(DriveBackupResult.Failure(DriveBackupResult.Reason.NoNetwork), result)
        assertTrue(settings.readDriveOk() == false)
    }

    @Test
    fun `backupToDrive maps a verify mismatch to VerifyFailed`() = runBlocking {
        drive.putThrows = DriveException.VerifyMismatch
        val result = repo.backupToDrive()
        assertEquals(DriveBackupResult.Failure(DriveBackupResult.Reason.VerifyFailed), result)
    }

    @Test
    fun `backupToDrive maps a 403 rate-limit to a generic Failed`() = runBlocking {
        drive.putThrows = DriveException.fromStatus(403)
        val result = repo.backupToDrive()
        assertEquals(DriveBackupResult.Failure(DriveBackupResult.Reason.Failed), result)
    }

    @Test
    fun `backupToDrive maps a 404 to a generic Failed`() = runBlocking {
        drive.putThrows = DriveException.fromStatus(404)
        assertEquals(DriveBackupResult.Failure(DriveBackupResult.Reason.Failed), repo.backupToDrive())
    }

    @Test
    fun `backupToDrive maps a 5xx server error to a generic Failed`() = runBlocking {
        drive.putThrows = DriveException.fromStatus(500)
        assertEquals(DriveBackupResult.Failure(DriveBackupResult.Reason.Failed), repo.backupToDrive())
    }

    @Test
    fun `backupToDrive maps a non-Drive throwable to Failed and records failure without throwing`() = runBlocking {
        drive.putThrows = IllegalStateException("boom")
        val result = repo.backupToDrive()
        assertEquals(DriveBackupResult.Failure(DriveBackupResult.Reason.Failed), result)
        assertTrue(settings.readDriveOk() == false)
    }

    // --- listDriveBackups: guarded (a throwing source yields empty, T-17-08) ---

    @Test
    fun `listDriveBackups returns the drive source listing newest-first`() = runBlocking {
        drive.listResult = listOf(
            BackupRef("id-new", "caltracker-2026-07-02_1200.db", 2_000L, 10L),
            BackupRef("id-old", "caltracker-2026-07-01_0900.db", 1_000L, 10L),
        )
        assertEquals(listOf("id-new", "id-old"), repo.listDriveBackups().map { it.ref })
    }

    @Test
    fun `listDriveBackups yields an empty list when the source throws (never crashes)`() = runBlocking {
        drive.listThrows = DriveException.Network
        assertEquals(emptyList<BackupRef>(), repo.listDriveBackups())
    }

    // --- Drive restore feeds the UNCHANGED engine (D-04b) ---

    @Test
    fun `restoreFromDrive of a valid same-version candidate stages+safety-copies+restarts via the shared engine`() = runBlocking {
        val ref = drive.stage(seedCandidate("cand-drive.db", version = 5))

        val result = repo.restoreFromDrive(ref)

        assertEquals(BackupResult.Success, result)
        assertTrue("staged for the Initializer", File(liveDb.path + RestoreSwapInitializer.STAGED_SUFFIX).exists())
        assertTrue("safety copy taken", File(liveDb.path + RestoreSwapInitializer.SAFETY_SUFFIX).exists())
        assertTrue("pending marker written", File(liveDb.path + RestoreSwapInitializer.MARKER_SUFFIX).exists())
        assertTrue("restart requested on the valid path", config.restartRequested)
        assertTrue("fetched from the DRIVE source", drive.getServed)
    }

    @Test
    fun `restoreFromDrive of a newer-schema candidate is refused with nothing staged (T-17-05)`() = runBlocking {
        val ref = drive.stage(seedCandidate("cand-newer.db", version = 6))

        val result = repo.restoreFromDrive(ref)

        assertEquals(BackupResult.Failure(BackupResult.Reason.SchemaTooNew), result)
        assertFalse(File(liveDb.path + RestoreSwapInitializer.STAGED_SUFFIX).exists())
        assertFalse(File(liveDb.path + RestoreSwapInitializer.SAFETY_SUFFIX).exists())
        assertFalse("no restart on a refusal", config.restartRequested)
    }

    @Test
    fun `restoreFromDrive of a corrupt download is refused as NotAValidBackup, live data untouched`() = runBlocking {
        val corrupt = File(scratch, "cand-corrupt.db").apply { writeText("not a sqlite db") }
        val ref = drive.stage(corrupt)

        val result = repo.restoreFromDrive(ref)

        assertEquals(BackupResult.Failure(BackupResult.Reason.NotAValidBackup), result)
        assertFalse(config.restartRequested)
    }

    // --- helpers / doubles ---

    private fun seedDb(file: File, version: Int) {
        val h = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(file, null)
        h.version = version
        h.execSQL("CREATE TABLE IF NOT EXISTS notes (id INTEGER PRIMARY KEY)")
        h.close()
    }

    private fun seedCandidate(name: String, version: Int): File {
        val f = File(scratch, name)
        val h = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(f, null)
        h.version = version
        h.execSQL("CREATE TABLE IF NOT EXISTS notes (id INTEGER PRIMARY KEY)")
        h.close()
        return f
    }

    private suspend fun BackupSettingsStore.readDriveOk(): Boolean? =
        when (lastDriveBackupStatus.first()) {
            is DriveBackupStatus.Success -> true
            is DriveBackupStatus.Failure -> false
            else -> null
        }

    /** A [BackupSource] fake usable as both the local and the Drive source. */
    private class FakeSource(private val dir: File) : BackupSource {
        val put = mutableListOf<Pair<File, String>>()
        var putThrows: Throwable? = null
        var listResult: List<BackupRef> = emptyList()
        var listThrows: Throwable? = null
        var getServed = false
        private val staged = mutableMapOf<String, File>()

        fun stage(file: File): BackupRef {
            val ref = "fake://${file.name}"
            staged[ref] = file
            return BackupRef(ref = ref, name = file.name, timestampMillis = 0L, sizeBytes = file.length())
        }

        override suspend fun put(file: File, name: String): BackupRef {
            putThrows?.let { throw it }
            put += file to name
            return BackupRef(ref = "fake://$name", name = name, timestampMillis = 0L, sizeBytes = file.length())
        }

        override suspend fun list(): List<BackupRef> {
            listThrows?.let { throw it }
            return listResult
        }

        override suspend fun get(ref: BackupRef): File {
            getServed = true
            val src = staged[ref.ref] ?: error("no staged candidate for ${ref.ref}")
            val dest = File(dir, "got-${src.name}")
            src.copyTo(dest, overwrite = true)
            return dest
        }

        override suspend fun delete(ref: BackupRef) {}
    }

    private class FakeConfig(
        override val databaseFile: File,
        override val currentSchemaVersion: Int,
    ) : BackupConfig {
        override val appName: String = "caltracker"
        override val dataStore: DataStore<Preferences> = InMemoryDataStore()
        var restartRequested = false

        override fun restartApp(context: Context): Boolean {
            restartRequested = true
            return true
        }
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

package io.github.ygaray.backupengine

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.test.core.app.ApplicationProvider
import io.github.ygaray.backupengine.model.BackupRef
import io.github.ygaray.backupengine.model.BackupResult
import io.github.ygaray.backupengine.settings.BackupSettingsStore
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
 * Robolectric unit suite for [BackupRepository] — the single orchestration seam for both flows
 * (BAK-02, BAK-04, RST-01, RST-03). Uses fakes for [BackupSource] and a real on-disk scratch DB so
 * the ordering guarantees (validate-before-staging, restart-only-on-valid, never-throw) are proven
 * off-device with no Hilt graph.
 */
@RunWith(RobolectricTestRunner::class)
class BackupRepositoryTest {

    private lateinit var ctx: Context
    private lateinit var scratch: File
    private lateinit var liveDb: File
    private lateinit var source: FakeBackupSource
    private lateinit var driveSource: FakeBackupSource
    private lateinit var config: FakeBackupConfig
    private lateinit var settings: BackupSettingsStore
    private lateinit var repo: BackupRepository

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        scratch = File(ctx.cacheDir, "brepo-${System.nanoTime()}").apply { mkdirs() }
        liveDb = File(scratch, "caltracker.db")
        seedDb(liveDb, currentVersion = 5)
        source = FakeBackupSource(scratch)
        driveSource = FakeBackupSource(scratch)
        settings = BackupSettingsStore(InMemoryDataStore())
        config = FakeBackupConfig(liveDb, currentSchemaVersion = 5)
        repo = BackupRepository(ctx, config, DatabaseFileManager(), source, driveSource, settings)
    }

    // --- helpers ---

    private fun seedDb(file: File, currentVersion: Int) {
        val h = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(file, null)
        h.version = currentVersion
        h.execSQL("CREATE TABLE IF NOT EXISTS notes (id INTEGER PRIMARY KEY)")
        h.close()
    }

    private fun stagedFile() = File(liveDb.path + RestoreSwapInitializer.STAGED_SUFFIX)
    private fun safetyFile() = File(liveDb.path + RestoreSwapInitializer.SAFETY_SUFFIX)
    private fun markerFile() = File(liveDb.path + RestoreSwapInitializer.MARKER_SUFFIX)

    // --- backup ---

    @Test
    fun `backup snapshots puts verifies and records success`() = runBlocking {
        val result = repo.backup()

        assertEquals(BackupResult.Success(), result)
        assertEquals(1, source.put.size)
        assertTrue("put name follows the caltracker- scheme", source.put.first().second.startsWith("caltracker-"))
        assertTrue("a success result was recorded", settings.readOk() == true)
    }

    @Test
    fun `backup returns WriteFailed and records failure when the source put throws`() = runBlocking {
        source.putThrows = true

        val result = repo.backup()

        assertEquals(BackupResult.Failure(BackupResult.Reason.WriteFailed), result)
        assertTrue("a failure result was recorded", settings.readOk() == false)
    }

    // --- restore: valid path ---

    @Test
    fun `restore of a valid same-version candidate safety-copies stages flags then restarts in order`() = runBlocking {
        val ref = source.stage(seedCandidate("cand-valid.db", version = 5))

        val result = repo.restore(ref)

        assertEquals(BackupResult.Success(), result)
        assertTrue("staged file written for the Initializer", stagedFile().exists())
        assertTrue("safety copy of the live DB taken", safetyFile().exists())
        assertTrue("plain pending marker written", markerFile().exists())
        assertTrue("restart requested exactly on the valid path", config.restartRequested)
        // Ordering: the safety copy + staged + marker all exist BEFORE restart was requested.
        assertTrue(config.stateAtRestart.all { it })
    }

    // --- restore: refusals (nothing touched) ---

    @Test
    fun `restore of a newer-schema candidate returns SchemaTooNew and stages nothing`() = runBlocking {
        val ref = source.stage(seedCandidate("cand-newer.db", version = 6))

        val result = repo.restore(ref)

        assertEquals(BackupResult.Failure(BackupResult.Reason.SchemaTooNew), result)
        assertNothingStaged()
        assertFalse("restart must NOT be requested on a refusal", config.restartRequested)
    }

    @Test
    fun `restore of a corrupt candidate returns NotAValidBackup and stages nothing`() = runBlocking {
        val corrupt = File(scratch, "cand-corrupt.db").apply { writeText("not a sqlite db") }
        val ref = source.stage(corrupt)

        val result = repo.restore(ref)

        assertEquals(BackupResult.Failure(BackupResult.Reason.NotAValidBackup), result)
        assertNothingStaged()
        assertFalse(config.restartRequested)
    }

    @Test
    fun `restart is requested only on the valid path`() = runBlocking {
        // A refusal first: no restart.
        repo.restore(source.stage(seedCandidate("c1.db", version = 6)))
        assertFalse(config.restartRequested)
        // Then a valid one: restart requested.
        repo.restore(source.stage(seedCandidate("c2.db", version = 5)))
        assertTrue(config.restartRequested)
    }

    // --- CR-01/IN-04: reconcile the durable hand-off after a completed swap ---

    @Test
    fun `reconcile clears the flag and reclaims safety+staged when the swap has completed`() = runBlocking {
        // Arm a pending restore + leave the .safety/.staged siblings, but NO marker (swap ran at cold start).
        settings.setPendingRestore(stagedFile().path, safetyFile().path)
        stagedFile().writeText("leftover-staged")
        safetyFile().writeText("leftover-safety")
        assertFalse("marker absent == swap completed", markerFile().exists())

        repo.reconcilePendingRestore()

        assertFalse("RESTORE_PENDING cleared", settings.restorePending.first())
        assertFalse("orphaned .safety reclaimed", safetyFile().exists())
        assertFalse("orphaned .staged reclaimed", stagedFile().exists())
    }

    @Test
    fun `reconcile is a no-op while the swap is still armed (marker present)`() = runBlocking {
        settings.setPendingRestore(stagedFile().path, safetyFile().path)
        stagedFile().writeText("armed-staged")
        safetyFile().writeText("armed-safety")
        markerFile().writeText("pending") // swap NOT yet run

        repo.reconcilePendingRestore()

        assertTrue("flag must stay armed while a swap is pending", settings.restorePending.first())
        assertTrue("staged must survive while armed", stagedFile().exists())
        assertTrue("safety must survive while armed", safetyFile().exists())
    }

    @Test
    fun `reconcile is idempotent on a second call`() = runBlocking {
        settings.setPendingRestore(stagedFile().path, safetyFile().path)
        safetyFile().writeText("leftover-safety")

        repo.reconcilePendingRestore()
        // Second call: flag already clear, files already gone — must not throw and stay clear.
        repo.reconcilePendingRestore()

        assertFalse(settings.restorePending.first())
        assertFalse(safetyFile().exists())
    }

    @Test
    fun `a successful restore then reconcile leaves no permanent pending flag or orphaned safety`() = runBlocking {
        val ref = source.stage(seedCandidate("cand-recon.db", version = 5))
        assertEquals(BackupResult.Success(), repo.restore(ref))
        assertTrue("restore armed the pending flag", settings.restorePending.first())

        // Simulate the cold-start swap having run + cleared the marker.
        markerFile().delete()
        repo.reconcilePendingRestore()

        assertFalse("no permanent RESTORE_PENDING after reconcile", settings.restorePending.first())
        assertFalse("no orphaned .safety after reconcile", safetyFile().exists())
    }

    // --- WR-01: post-validate failure maps to WriteFailed, not NotAValidBackup ---

    @Test
    fun `restore maps a folder-unavailable fetch to FolderUnavailable not NotAValidBackup`() = runBlocking {
        val ref = source.stage(seedCandidate("cand-fu.db", version = 5))
        source.getThrows = io.github.ygaray.backupengine.source.FolderUnavailableException("gone")

        val result = repo.restore(ref)

        assertEquals(BackupResult.Failure(BackupResult.Reason.FolderUnavailable), result)
    }

    @Test
    fun `restore maps a post-validate staging failure to WriteFailed (valid backup never NotAValidBackup)`() = runBlocking {
        // A valid candidate passes validate(); force the post-validate restart to report a write failure.
        val ref = source.stage(seedCandidate("cand-wr01.db", version = 5))
        config.restartScheduled = false // WR-04: no launch intent -> restart returns false

        val result = repo.restore(ref)

        // Past validate() the candidate is valid; the failure must be WriteFailed, NOT NotAValidBackup.
        assertEquals(BackupResult.Failure(BackupResult.Reason.WriteFailed), result)
        assertTrue("restart was attempted", config.restartRequested)
    }

    // --- WR-02: a failed source.put deletes the temp snapshot (no leak) ---

    @Test
    fun `backup deletes its temp snapshot even when source put throws`() = runBlocking {
        source.putThrows = true

        val result = repo.backup()

        assertEquals(BackupResult.Failure(BackupResult.Reason.WriteFailed), result)
        val tempDir = File(liveDb.parentFile, "backup-temp")
        val leftovers = tempDir.listFiles()?.filter { it.name.startsWith("snapshot-") }.orEmpty()
        assertTrue("no snapshot-*.db temp leaked after a failed put: $leftovers", leftovers.isEmpty())
    }

    // --- WR-03: same-minute collision guard uses precise startsWith, not loose contains ---

    @Test
    fun `backupName does not falsely suppress the seconds suffix on a longer listed name that only contains the stem`() = runBlocking {
        // A listed name that CONTAINS but does not START WITH our base stem must NOT register as a
        // same-minute collision. contains() would false-positive here; startsWith() correctly ignores it.
        // Since the fake's minute won't collide with a "prefixed-…" name, the produced name is the plain
        // base stem with NO `_ss` seconds suffix — the exact `caltracker-YYYY-MM-DD_HHmm.db` shape.
        source.listNames = listOf("prefixed-caltracker-2026-07-01_1430.db.bin")

        repo.backup()

        val name = source.put.single().second
        val baseShape = Regex("^caltracker-\\d{4}-\\d{2}-\\d{2}_\\d{4}\\.db$")
        assertTrue("produced the base-stem name with no seconds suffix: $name", baseShape.matches(name))
    }

    // --- test doubles / helpers ---

    private fun assertNothingStaged() {
        assertFalse("no staged file", stagedFile().exists())
        assertFalse("no safety copy", safetyFile().exists())
        assertFalse("no pending marker", markerFile().exists())
    }

    private fun seedCandidate(name: String, version: Int): File {
        val f = File(scratch, name)
        val h = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(f, null)
        h.version = version
        h.execSQL("CREATE TABLE IF NOT EXISTS notes (id INTEGER PRIMARY KEY)")
        h.close()
        return f
    }

    private suspend fun BackupSettingsStore.readOk(): Boolean? {
        return when (lastLocalBackupStatus.first()) {
            is io.github.ygaray.backupengine.settings.LocalBackupStatus.Success -> true
            is io.github.ygaray.backupengine.settings.LocalBackupStatus.Failure -> false
            else -> null
        }
    }

    /** A [BackupSource] fake recording puts and serving staged candidates by ref. */
    private class FakeBackupSource(private val dir: File) : BackupSource {
        val put = mutableListOf<Pair<File, String>>()
        var putThrows = false
        var listNames: List<String> = emptyList()
        var getThrows: Throwable? = null
        private val staged = mutableMapOf<String, File>()

        /** Register a local candidate file and return the ref [get] will serve. */
        fun stage(file: File): BackupRef {
            val ref = "fake://${file.name}"
            staged[ref] = file
            return BackupRef(ref = ref, name = file.name, timestampMillis = 0L, sizeBytes = file.length())
        }

        override suspend fun put(file: File, name: String): BackupRef {
            if (putThrows) throw java.io.IOException("boom")
            put += file to name
            return BackupRef(ref = "fake://$name", name = name, timestampMillis = 0L, sizeBytes = file.length())
        }

        override suspend fun list(): List<BackupRef> =
            listNames.map { BackupRef(ref = "fake://$it", name = it, timestampMillis = 0L, sizeBytes = 0L) }

        override suspend fun get(ref: BackupRef): File {
            getThrows?.let { throw it }
            val src = staged[ref.ref] ?: error("no staged candidate for ${ref.ref}")
            // Mirror LocalBackupSource.get: copy to an app-private temp before validation/staging.
            val dest = File(dir, "got-${src.name}")
            src.copyTo(dest, overwrite = true)
            return dest
        }

        override suspend fun delete(ref: BackupRef) {}
    }

    /** A [BackupConfig] fake with a recording restartApp. */
    private class FakeBackupConfig(
        override val databaseFile: File,
        override val currentSchemaVersion: Int,
    ) : BackupConfig {
        override val appName: String = "caltracker"
        override val dataStore: DataStore<Preferences> = InMemoryDataStore()
        var restartRequested = false
        /** WR-04: simulate a host with no launch intent — restartApp returns false (no relaunch). */
        var restartScheduled = true
        val stateAtRestart = mutableListOf<Boolean>()

        override fun restartApp(context: Context): Boolean {
            restartRequested = true
            // Capture that the staged/safety/marker files all exist at restart time (ordering proof).
            stateAtRestart += File(databaseFile.path + RestoreSwapInitializer.STAGED_SUFFIX).exists()
            stateAtRestart += File(databaseFile.path + RestoreSwapInitializer.SAFETY_SUFFIX).exists()
            stateAtRestart += File(databaseFile.path + RestoreSwapInitializer.MARKER_SUFFIX).exists()
            return restartScheduled
        }
    }

    /** A minimal in-memory [DataStore] so the store round-trips without a real file. */
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

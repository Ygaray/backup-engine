package io.github.ygaray.backupengine

import android.database.sqlite.SQLiteDatabase
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
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Wave-0 RED spec for ENG-04 (restore leg) — a restore that fails DURING PHASE-2 staging (after the
 * candidate has already validated) must leave NO orphaned `.staged` / `.safety` siblings of the live
 * DB behind (Phase 22, plan 22-02).
 *
 * v1.0.0 [BackupRepository.restoreFrom] PHASE 2 writes the safety copy FIRST
 * (`liveDb.copyTo(File(safetyPath))`) and only THEN stages the validated candidate
 * (`candidate.copyTo(File(stagedPath))`). If the stage copy throws AFTER the safety copy has already
 * been written, the `catch (e: Throwable)` maps to a `WriteFailed` [BackupResult.Failure] but does
 * NOT reclaim the half-written `.safety` (nor any partial `.staged`). That orphaned full-size copy of
 * the live DB is silently stranded in the app-private dir — the ENG-04 leak this test pins.
 *
 * The fix (plan 22-02) adds a cleanup in the PHASE-2 catch that deletes the `.staged` / `.safety`
 * siblings when staging failed and no swap was armed, so a failed restore leaves the staging area
 * exactly as it found it.
 *
 * ### RED-by-design (Wave 0)
 * The cleanup does not exist at v1.0.0, so after the injected staging failure the `.safety` orphan
 * remains and the "no orphans" assertion FAILS. That RED is the correct Wave-0 outcome; do NOT edit
 * BackupRepository to make it pass — plan 22-02 owns that change.
 *
 * ### Failure injection (no production hooks needed)
 * The candidate is a GENUINELY valid SQLite DB so the real [DatabaseFileManager.validate] passes
 * (returns `null`) exactly as production does. We then pre-create a DIRECTORY at the deterministic
 * `stagedPath` (`liveDb.path + STAGED_SUFFIX`). PHASE 2 first copies the live DB to `safetyPath`
 * (succeeds → writes the `.safety` orphan), then `candidate.copyTo(File(stagedPath), overwrite=true)`
 * throws because the destination is a directory — reproducing "staging failed after the safety copy
 * was written" without poking any private field or overriding a non-open method.
 */
@RunWith(RobolectricTestRunner::class)
class BackupRepositoryRestoreCleanupTest {

    private lateinit var scratch: File
    private lateinit var liveDb: File
    private lateinit var source: FakeRestoreSource
    private lateinit var settings: BackupSettingsStore
    private lateinit var repo: BackupRepository

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        scratch = File(ctx.cacheDir, "restore-clean-${System.nanoTime()}").apply { mkdirs() }
        // A real, valid SQLite live DB (readable → the safety copy succeeds).
        liveDb = File(scratch, "caltracker.db")
        newValidSqliteDb(liveDb)
        source = FakeRestoreSource(scratch)
        settings = BackupSettingsStore(InMemoryDataStore())
        val config = FakeBackupConfig(liveDb)
        // Real DatabaseFileManager so validate() genuinely passes on the valid candidate.
        repo = BackupRepository(ctx, config, DatabaseFileManager(), source, source, settings)
    }

    @Test
    fun `a restore that fails during staging leaves no orphaned staged or safety files`() = runBlocking {
        // Force the stage copy to throw AFTER the safety copy is written: a directory sits at the
        // deterministic staged path, so candidate.copyTo(stagedPath) cannot overwrite it.
        val stagedPath = File(liveDb.path + RestoreSwapInitializer.STAGED_SUFFIX)
        stagedPath.mkdirs()
        val safetyPath = File(liveDb.path + RestoreSwapInitializer.SAFETY_SUFFIX)

        val ref = BackupRef(ref = "candidate", name = "caltracker-restore.db", timestampMillis = 1L, sizeBytes = 1L)
        val result = repo.restore(ref)

        // The staging failure is a WriteFailed (candidate proven valid → never NotAValidBackup).
        assertTrue("a mid-staging failure must be a Failure", result is BackupResult.Failure)

        // RED: v1.0.0 leaves the `.safety` orphan behind (the safety copy ran before the stage copy
        // threw) — plan 22-02 adds the cleanup that reclaims the `.staged`/`.safety` siblings.
        assertTrue(
            "no orphaned .safety must remain after a failed restore stage (found ${safetyPath.path})",
            !safetyPath.exists(),
        )
        // The injected directory-at-staged-path is our fault-injection scaffold, not a real orphan;
        // the cleanup must leave NO staged FILE behind (a leftover directory here is the injection).
        assertTrue(
            "no orphaned .staged FILE must remain after a failed restore stage",
            !(stagedPath.exists() && stagedPath.isFile),
        )
    }

    // --- helpers / fakes ---

    /** Create a minimal but genuinely-valid SQLite DB at [file] so validate() passes. */
    private fun newValidSqliteDb(file: File) {
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL("CREATE TABLE IF NOT EXISTS t (id INTEGER PRIMARY KEY)")
            db.execSQL("INSERT INTO t (id) VALUES (1)")
            db.version = 1 // user_version <= currentSchemaVersion (5) → validate allows it
        }
    }

    private class FakeRestoreSource(private val root: File) : BackupSource {
        override suspend fun put(file: File, name: String): BackupRef =
            BackupRef(ref = "fake://$name", name = name, timestampMillis = 0L, sizeBytes = file.length())

        override suspend fun list(): List<BackupRef> = emptyList()

        /** Fetch a fresh, genuinely-valid SQLite candidate that the real validate() accepts. */
        override suspend fun get(ref: BackupRef): File {
            val candidate = File(root, "fetched-${ref.ref}.db")
            SQLiteDatabase.openOrCreateDatabase(candidate, null).use { db ->
                db.execSQL("CREATE TABLE IF NOT EXISTS t (id INTEGER PRIMARY KEY)")
                db.version = 1
            }
            return candidate
        }

        override suspend fun delete(ref: BackupRef) = Unit
    }

    private class FakeBackupConfig(override val databaseFile: File) : BackupConfig {
        override val appName: String = "caltracker"
        override val currentSchemaVersion: Int = 5
        override val dataStore: DataStore<Preferences> = InMemoryDataStore()
        // Restore fails before restartApp is reached; false keeps the fake self-contained regardless.
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

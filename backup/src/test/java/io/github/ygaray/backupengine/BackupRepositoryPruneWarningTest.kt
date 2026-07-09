package io.github.ygaray.backupengine

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.test.core.app.ApplicationProvider
import io.github.ygaray.backupengine.model.BackupRef
import io.github.ygaray.backupengine.model.BackupResult
import io.github.ygaray.backupengine.model.DriveBackupResult
import io.github.ygaray.backupengine.settings.BackupSettingsStore
import io.github.ygaray.backupengine.settings.DriveBackupStatus
import io.github.ygaray.backupengine.settings.LocalBackupStatus
import io.github.ygaray.backupengine.source.BackupSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Wave-0 RED spec for ENG-01 — retention-prune failure must NEVER flip a verified backup success to
 * a failure (Phase 22, plan 22-02).
 *
 * v1.0.0 [BackupRepository.backup] / [BackupRepository.backupToDrive] call [BackupRepository.prune]
 * AFTER recording `ok = true`. `prune` deletes a failed `delete()` LOUDLY (it throws — see
 * RetentionPruneTest `a failed delete propagates loudly`). That throw currently unwinds into the
 * flow's `catch (e: Throwable)`, which re-records `ok = false` and returns a `Failure`. So a
 * successfully-written-and-verified backup is wrongly reported as failed just because the post-success
 * housekeeping prune could not delete an old file (ENG-01). The fix (plan 22-02) catches the prune
 * failure and returns `Success` carrying a non-null `pruneWarning`, leaving the recorded `ok = true`
 * untouched.
 *
 * These tests arrange the post-success prune to throw (the fake's `deleteThrows = true`) and assert:
 *  - the result is `Success` (via `is`), NOT `Failure`, on BOTH the local and Drive paths,
 *  - the `Success` carries a non-null `pruneWarning`,
 *  - the recorded status stays `ok = true` (never flipped to a failure record).
 *
 * ### RED-by-design (Wave 0)
 * `BackupResult.Success` and `DriveBackupResult.Success` are `data object`s at v1.0.0 with NO
 * `pruneWarning` field, and the flows do NOT yet guard the prune throw. Referencing
 * `Success.pruneWarning` and expecting `Success` on a throwing prune both fail — that is the correct
 * Wave-0 RED. Do NOT edit BackupRepository / the result models to compile — plan 22-02 makes this GREEN.
 */
@RunWith(RobolectricTestRunner::class)
class BackupRepositoryPruneWarningTest {

    private lateinit var scratch: File
    private lateinit var source: FakeBackupSource
    private lateinit var driveSource: FakeBackupSource
    private lateinit var settings: BackupSettingsStore
    private lateinit var repo: BackupRepository

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        scratch = File(ctx.cacheDir, "prune-warn-${System.nanoTime()}").apply { mkdirs() }
        // A REAL SQLite live DB (not a "db" text stub): backup() runs DatabaseFileManager.snapshot(),
        // which opens the source with SQLiteDatabase — a non-SQLite file would throw there and short-
        // circuit to WriteFailed BEFORE the post-success prune under test ever runs. Seed a valid DB so
        // the flow reaches the prune step (mirrors BackupRepositoryDriveTest.seedDb).
        val liveDb = File(scratch, "caltracker.db")
        android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(liveDb, null).use { h ->
            h.execSQL("CREATE TABLE IF NOT EXISTS notes (id INTEGER PRIMARY KEY)")
        }
        source = FakeBackupSource()
        driveSource = FakeBackupSource()
        settings = BackupSettingsStore(InMemoryDataStore())
        val config = FakeBackupConfig(liveDb)
        repo = BackupRepository(ctx, config, DatabaseFileManager(), source, driveSource, settings)
    }

    /** Enough existing refs that prune tries to delete the oldest tail (keepN default is 5). */
    private fun refs(n: Int): List<BackupRef> =
        (0 until n).map { i ->
            BackupRef(ref = "r$i", name = "caltracker-$i.db", timestampMillis = (n - i).toLong(), sizeBytes = 1L)
        }

    @Test
    fun `local backup keeps Success with a pruneWarning when the post-success prune throws`() = runBlocking {
        // Arrange: 7 existing refs so prune must delete the oldest tail; that delete throws.
        source.listValue = refs(7)
        source.deleteThrows = true

        val result = repo.backup()

        // ENG-01: never flip a verified success to failure because housekeeping prune failed.
        assertTrue(
            "a verified backup whose post-success prune threw must remain Success, not Failure",
            result is BackupResult.Success,
        )
        // RED: Success is a data object with NO pruneWarning at v1.0.0 — plan 22-02 adds this field.
        assertNotNull(
            "the surfaced warning must be non-null so the ViewModel can note the prune failure",
            (result as BackupResult.Success).pruneWarning,
        )
        // The recorded status must remain the verified success — never re-recorded as ok = false.
        assertTrue(
            "the recorded local status must stay Success (ok=true), never flipped to Failure",
            settings.lastLocalBackupStatus.first() is LocalBackupStatus.Success,
        )
    }

    @Test
    fun `drive backup keeps Success with a pruneWarning when the post-success prune throws`() = runBlocking {
        driveSource.listValue = refs(7)
        driveSource.deleteThrows = true

        val result = repo.backupToDrive()

        assertTrue(
            "a verified Drive backup whose post-success prune threw must remain Success, not Failure",
            result is DriveBackupResult.Success,
        )
        // RED: DriveBackupResult.Success is a data object with NO pruneWarning at v1.0.0.
        assertNotNull(
            "the surfaced Drive warning must be non-null",
            (result as DriveBackupResult.Success).pruneWarning,
        )
        assertTrue(
            "the recorded Drive status must stay Success (ok=true), never flipped to Failure",
            settings.lastDriveBackupStatus.first() is DriveBackupStatus.Success,
        )
    }

    // --- fakes (mirrors RetentionPruneTest's scaffold; do NOT reinvent) ---

    private class FakeBackupSource : BackupSource {
        var listValue: List<BackupRef> = emptyList()
        var deleteThrows = false
        val deleted = mutableListOf<BackupRef>()

        override suspend fun put(file: File, name: String): BackupRef =
            BackupRef(ref = "fake://$name", name = name, timestampMillis = 0L, sizeBytes = file.length())

        override suspend fun list(): List<BackupRef> = listValue

        override suspend fun get(ref: BackupRef): File = error("not used")

        override suspend fun delete(ref: BackupRef) {
            if (deleteThrows) throw java.io.IOException("delete boom")
            deleted += ref
        }
    }

    private class FakeBackupConfig(override val databaseFile: File) : BackupConfig {
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

package io.github.ygaray.backupengine

import io.github.ygaray.backupengine.model.BackupRef
import io.github.ygaray.backupengine.settings.BackupSettingsStore
import io.github.ygaray.backupengine.source.BackupSource
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Wave-0 unit suite for count-based retention pruning (SCH-04, D-01/D-01b, T-18-01/T-18-02).
 *
 * Drives [BackupRepository.prune] directly against a recording [FakeBackupSource] so the delete-set
 * math is proven off-device with no Hilt graph:
 *  - newest-first list of 7 refs, keepN=5 → delete() called on exactly the oldest 2 (indices 5,6),
 *  - keepN >= list.size → zero deletes,
 *  - keepN=1 with 1 ref → zero deletes (the last good backup is never deleted),
 *  - keepN=0 → require() throws IllegalArgumentException.
 */
@RunWith(RobolectricTestRunner::class)
class RetentionPruneTest {

    private lateinit var scratch: File
    private lateinit var source: FakeBackupSource
    private lateinit var driveSource: FakeBackupSource
    private lateinit var repo: BackupRepository

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        scratch = File(ctx.cacheDir, "prune-${System.nanoTime()}").apply { mkdirs() }
        val liveDb = File(scratch, "caltracker.db").apply { writeText("db") }
        source = FakeBackupSource()
        driveSource = FakeBackupSource()
        val settings = BackupSettingsStore(InMemoryDataStore())
        val config = FakeBackupConfig(liveDb)
        repo = BackupRepository(ctx, config, DatabaseFileManager(), source, driveSource, settings)
    }

    /** newest-first list of `n` refs: index 0 newest, index n-1 oldest. */
    private fun refs(n: Int): List<BackupRef> =
        (0 until n).map { i ->
            BackupRef(ref = "r$i", name = "caltracker-$i.db", timestampMillis = (n - i).toLong(), sizeBytes = 1L)
        }

    @Test
    fun `keepN 5 over 7 deletes exactly the oldest two`() = runBlocking {
        val all = refs(7)
        source.listValue = all
        repo.prune(source, keepN = 5)
        // The oldest two are indices 5 and 6 in the newest-first listing.
        assertEquals(listOf("r5", "r6"), source.deleted.map { it.ref })
    }

    @Test
    fun `keepN equal to size is a no-op`() = runBlocking {
        source.listValue = refs(5)
        repo.prune(source, keepN = 5)
        assertTrue("no deletes when size == keepN", source.deleted.isEmpty())
    }

    @Test
    fun `keepN greater than size is a no-op`() = runBlocking {
        source.listValue = refs(3)
        repo.prune(source, keepN = 5)
        assertTrue("no deletes when size < keepN", source.deleted.isEmpty())
    }

    @Test
    fun `keepN 1 with a single backup never deletes the last good backup`() = runBlocking {
        source.listValue = refs(1)
        repo.prune(source, keepN = 1)
        assertTrue("the last good backup is preserved", source.deleted.isEmpty())
    }

    @Test
    fun `keepN 0 throws IllegalArgumentException`() = runBlocking {
        source.listValue = refs(3)
        try {
            repo.prune(source, keepN = 0)
            fail("expected require(keepN >= 1) to throw")
        } catch (e: IllegalArgumentException) {
            // expected — the floor guard (D-01b) refuses keepN < 1.
        }
    }

    @Test
    fun `justWritten is excluded by identity even when a sort tie misfiles it`() = runBlocking {
        // WR-01: simulate a same-second SAF tie where the just-written file is NOT at index 0.
        // keepN=2 over 3 refs. If prune dropped positionally it would delete r2 AND (wrongly) could
        // delete the newly written file when ties reorder it out of index 0. Excluding by identity,
        // the just-written ref is protected and only the true oldest tail is removed.
        val existing0 = BackupRef(ref = "r0", name = "caltracker-2026-07-01_1430.db", timestampMillis = 100L, sizeBytes = 1L)
        val justWritten = BackupRef(ref = "rNew", name = "caltracker-2026-07-01_1430_05.db", timestampMillis = 100L, sizeBytes = 1L)
        val oldest = BackupRef(ref = "r2", name = "caltracker-2026-06-01_0900.db", timestampMillis = 50L, sizeBytes = 1L)
        // Listing order puts the fresh file behind a same-timestamp existing one (the misfile scenario).
        source.listValue = listOf(existing0, justWritten, oldest)
        repo.prune(source, keepN = 2, justWritten = justWritten)
        // keepN=2 → keep justWritten + newest (keepN-1=1) of others (existing0); delete only the true oldest.
        assertEquals(listOf("r2"), source.deleted.map { it.ref })
        assertTrue("the just-written ref is never a delete candidate", source.deleted.none { it.ref == "rNew" })
    }

    @Test
    fun `justWritten no-op when new file plus others fit the window`() = runBlocking {
        val justWritten = BackupRef(ref = "rNew", name = "caltracker-new.db", timestampMillis = 100L, sizeBytes = 1L)
        // 3 refs total (justWritten + 2 others), keepN=3 → nothing to delete.
        source.listValue = listOf(justWritten) + refs(2)
        repo.prune(source, keepN = 3, justWritten = justWritten)
        assertTrue("no deletes when new file + others fit the window", source.deleted.isEmpty())
    }

    @Test
    fun `a failed delete propagates loudly`() = runBlocking {
        source.listValue = refs(7)
        source.deleteThrows = true
        try {
            repo.prune(source, keepN = 5)
            fail("expected the failed delete() to propagate")
        } catch (e: java.io.IOException) {
            // expected — a delete failure surfaces (D-01b guard 3), never swallowed.
        }
    }

    // --- fakes ---

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

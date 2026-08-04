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
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * TDD spec for the media RESTORE leg (ENGINE-02, D-01 A′ hybrid, plan 75-04).
 *
 * Task 1 proves the restore-time PHASE 2b media staging in [BackupRepository.restoreFrom]:
 *  - `config.mediaDirectories` empty -> byte-identical to today (zero media list/get calls, no
 *    staging, [BackupSettingsStore.mediaRestorePending] never armed);
 *  - a present, stem-paired `.media.zip` sidecar is staged into one `.media-staged` sibling PER
 *    configured directory and arms the independent flag;
 *  - a missing sidecar or a rejected (zip-slip) extract leaves the DB restore's own outcome
 *    (`BackupResult.Failure(WriteFailed)` — this suite's fake always declines the restart) totally
 *    unaffected and never arms the flag (D-01 — media never blocks/fails the DB restore).
 *
 * Task 2 (appended below in the same file, plan 75-04 Task 2) proves the
 * [BackupRepository.reconcilePendingRestore] media leg: finalize-into-place + clear-only-on-full-
 * success + independence from the (unchanged) DB leg.
 */
@RunWith(RobolectricTestRunner::class)
class BackupRepositoryMediaRestoreTest {

    private lateinit var scratch: File
    private lateinit var liveDb: File
    private lateinit var ctx: android.content.Context

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        scratch = File(ctx.cacheDir, "media-restore-${System.nanoTime()}").apply { mkdirs() }
        liveDb = File(scratch, "caltracker.db")
        SQLiteDatabase.openOrCreateDatabase(liveDb, null).use { db ->
            db.execSQL("CREATE TABLE IF NOT EXISTS t (id INTEGER PRIMARY KEY)")
            db.version = 1
        }
    }

    private val restoreRef = BackupRef(ref = "candidate", name = "caltracker-restore.db", timestampMillis = 1L, sizeBytes = 1L)

    private fun repoWith(source: FakeRestoreSource, settings: BackupSettingsStore, mediaDirs: List<File> = emptyList()): BackupRepository =
        BackupRepository(ctx, FakeBackupConfig(liveDb, mediaDirs), DatabaseFileManager(), source, source, settings)

    private fun buildMediaZip(archive: File, entries: List<Pair<String, ByteArray>>) {
        ZipOutputStream(archive.outputStream()).use { zip ->
            for ((entryName, content) in entries) {
                zip.putNextEntry(ZipEntry(entryName))
                zip.write(content)
                zip.closeEntry()
            }
        }
    }

    // --- Task 1: restore-time media staging (PHASE 2b) ---

    @Test
    fun `empty mediaDirectories restore issues zero media fetches and never arms the flag`() = runBlocking {
        val source = FakeRestoreSource(scratch)
        val settings = BackupSettingsStore(InMemoryDataStore())
        val repo = repoWith(source, settings, mediaDirs = emptyList())

        val result = repo.restore(restoreRef)

        assertTrue("this suite's fake always declines restart -> WriteFailed", result is BackupResult.Failure)
        assertEquals(BackupResult.Reason.WriteFailed, (result as BackupResult.Failure).reason)
        assertEquals("an empty mediaDirectories must never even list() the media source", 0, source.listCallCount)
        assertTrue("no media get() call for an empty mediaDirectories", source.getCalls.none { it.name.endsWith(".media.zip") })
        assertFalse("flag must never be armed", settings.mediaRestorePending.first())
    }

    @Test
    fun `a present sidecar is staged into per-directory media-staged siblings and arms the independent flag`() = runBlocking {
        val mediaDir = File(scratch, "album_images")
        val stagedSibling = File(scratch, "album_images.media-staged")
        val archive = File(scratch, "sidecar.zip")
        buildMediaZip(archive, listOf("album_images/photo.jpg" to "photo-bytes".toByteArray()))

        val source = FakeRestoreSource(scratch).apply {
            listValue = listOf(BackupRef(ref = "media-ref", name = "caltracker-restore.media.zip", timestampMillis = 1L, sizeBytes = 1L))
            mediaZipToServe = archive
        }
        val settings = BackupSettingsStore(InMemoryDataStore())
        val repo = repoWith(source, settings, mediaDirs = listOf(mediaDir))

        repo.restore(restoreRef)

        assertTrue(
            "the sidecar must be staged into the .media-staged sibling, not the live dir",
            File(stagedSibling, "photo.jpg").exists(),
        )
        assertFalse("the live dir must be untouched pre-reconcile", File(mediaDir, "photo.jpg").exists())
        assertTrue("the independent media flag must be armed", settings.mediaRestorePending.first())
    }

    @Test
    fun `a missing sidecar leaves the DB restore intact and does not arm the media flag`() = runBlocking {
        val mediaDir = File(scratch, "album_images")
        val source = FakeRestoreSource(scratch) // listValue defaults to emptyList -> no sidecar found
        val settings = BackupSettingsStore(InMemoryDataStore())
        val repo = repoWith(source, settings, mediaDirs = listOf(mediaDir))

        val result = repo.restore(restoreRef)

        assertTrue("a missing sidecar must not change the DB restore's own outcome", result is BackupResult.Failure)
        assertEquals(BackupResult.Reason.WriteFailed, (result as BackupResult.Failure).reason)
        assertFalse("no sidecar present -> flag must not be armed", settings.mediaRestorePending.first())
        assertFalse("no .media-staged sibling should be created", File(scratch, "album_images.media-staged").exists())
    }

    @Test
    fun `a rejected (zip-slip) sidecar leaves the DB restore intact and does not arm the media flag`() = runBlocking {
        val mediaDir = File(scratch, "album_images")
        val archive = File(scratch, "malicious.zip")
        buildMediaZip(archive, listOf("album_images/../../../../evil.txt" to "evil".toByteArray()))
        val source = FakeRestoreSource(scratch).apply {
            listValue = listOf(BackupRef(ref = "media-ref", name = "caltracker-restore.media.zip", timestampMillis = 1L, sizeBytes = 1L))
            mediaZipToServe = archive
        }
        val settings = BackupSettingsStore(InMemoryDataStore())
        val repo = repoWith(source, settings, mediaDirs = listOf(mediaDir))

        val result = repo.restore(restoreRef)

        assertTrue("an extract rejection must not change the DB restore's own outcome", result is BackupResult.Failure)
        assertEquals(BackupResult.Reason.WriteFailed, (result as BackupResult.Failure).reason)
        assertFalse("a rejected archive must not arm the flag", settings.mediaRestorePending.first())
    }

    // --- fakes ---

    private class FakeRestoreSource(private val root: File) : BackupSource {
        var listValue: List<BackupRef> = emptyList()
        var listCallCount = 0
        var mediaZipToServe: File? = null
        val getCalls = mutableListOf<BackupRef>()

        override suspend fun put(file: File, name: String): BackupRef =
            BackupRef(ref = "fake://$name", name = name, timestampMillis = 0L, sizeBytes = file.length())

        override suspend fun list(): List<BackupRef> {
            listCallCount++
            return listValue
        }

        override suspend fun get(ref: BackupRef): File {
            getCalls += ref
            return if (ref.name.endsWith(".media.zip")) {
                mediaZipToServe ?: error("no media zip fixture configured for ${ref.name}")
            } else {
                val candidate = File(root, "fetched-${ref.ref}.db")
                SQLiteDatabase.openOrCreateDatabase(candidate, null).use { db ->
                    db.execSQL("CREATE TABLE IF NOT EXISTS t (id INTEGER PRIMARY KEY)")
                    db.version = 1
                }
                candidate
            }
        }

        override suspend fun delete(ref: BackupRef) = Unit
    }

    private class FakeBackupConfig(
        override val databaseFile: File,
        override val mediaDirectories: List<File> = emptyList(),
    ) : BackupConfig {
        override val appName: String = "caltracker"
        override val currentSchemaVersion: Int = 5
        override val dataStore: DataStore<Preferences> = InMemoryDataStore()
        // Restore reaches the restart gate in every scenario this suite exercises; declining it
        // (false) keeps every test's DB outcome a deterministic WriteFailed regardless of the media
        // leg, which is exactly what proves the media leg never alters the DB path (D-01).
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

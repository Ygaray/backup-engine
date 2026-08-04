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

    // --- Task 2: reconcilePendingRestore() media leg ---

    @Test
    fun `reconcile finalizes the staged media sibling into place and clears the flag`() = runBlocking {
        val mediaDir = File(scratch, "album_images")
        val staged = File(scratch, "album_images.media-staged").apply { mkdirs() }
        File(staged, "photo.jpg").writeText("photo-bytes")

        val settings = BackupSettingsStore(InMemoryDataStore())
        settings.setPendingMediaRestore()
        val repo = repoWith(FakeRestoreSource(scratch), settings, mediaDirs = listOf(mediaDir))

        repo.reconcilePendingRestore()

        assertTrue("the live dir must now contain the finalized content", File(mediaDir, "photo.jpg").exists())
        assertFalse("the staged sibling must be gone after finalize", staged.exists())
        assertFalse("the flag must clear only once every configured directory finalized", settings.mediaRestorePending.first())
    }

    @Test
    fun `reconcile is a no-op when mediaRestorePending is false`() = runBlocking {
        val mediaDir = File(scratch, "album_images")
        val staged = File(scratch, "album_images.media-staged").apply { mkdirs() }
        File(staged, "photo.jpg").writeText("photo-bytes")

        val settings = BackupSettingsStore(InMemoryDataStore()) // never armed
        val repo = repoWith(FakeRestoreSource(scratch), settings, mediaDirs = listOf(mediaDir))

        repo.reconcilePendingRestore()

        assertTrue("an un-armed flag must leave the staged sibling untouched", staged.exists())
        assertFalse("the live dir must stay untouched", File(mediaDir, "photo.jpg").exists())
    }

    @Test
    fun `a partial finalize failure leaves the flag armed for an idempotent retry`() = runBlocking {
        // dirA finalizes normally; dirB's PARENT is made unwritable so both its same-fs renameTo AND
        // its copyRecursively fallback (which must mkdirs a fresh target when dirB doesn't yet exist)
        // fail — this aborts the whole media leg via the outer runCatching, proving the flag is left
        // armed rather than cleared on a PARTIAL success (D-01).
        val dirA = File(scratch, "album_images")
        val blockedParent = File(scratch, "blocked_parent").apply { mkdirs() }
        val dirB = File(blockedParent, "voice")

        val stagedA = File(scratch, "album_images.media-staged").apply { mkdirs() }
        File(stagedA, "photo.jpg").writeText("photo-bytes")
        val stagedB = File(blockedParent, "voice.media-staged").apply { mkdirs() }
        File(stagedB, "clip.mp3").writeText("clip-bytes")

        val settings = BackupSettingsStore(InMemoryDataStore())
        settings.setPendingMediaRestore()
        val repo = repoWith(FakeRestoreSource(scratch), settings, mediaDirs = listOf(dirA, dirB))

        blockedParent.setWritable(false)
        try {
            repo.reconcilePendingRestore()

            assertTrue(
                "dirA (processed before dirB's failure) must have finalized",
                File(dirA, "photo.jpg").exists(),
            )
            assertTrue(
                "the flag must stay armed since dirB did not finalize (idempotent retry, D-01)",
                settings.mediaRestorePending.first(),
            )
        } finally {
            blockedParent.setWritable(true)
        }

        // Idempotent retry: dirA's staged sibling is already gone (skipped as a no-op), dirB's is
        // still present and now finalizes with the parent writable again.
        repo.reconcilePendingRestore()

        assertTrue("dirB must finalize on the retry", File(dirB, "clip.mp3").exists())
        assertFalse("the flag finally clears once every directory has finalized", settings.mediaRestorePending.first())
    }

    @Test
    fun `a media-leg failure does not prevent or undo the DB leg's reclaim`() = runBlocking {
        // Arm BOTH legs. The DB leg: restorePending true + marker ABSENT (== swap already applied at
        // cold start), with real .safety/.staged sibling files present to reclaim (mirrors the
        // existing DB-leg reconcile contract). The media leg: one directory whose PARENT is
        // unwritable, forcing the media leg's runCatching to catch an exception.
        val safetyFile = File(liveDb.path + RestoreSwapInitializer.SAFETY_SUFFIX).apply { writeText("safety") }
        val stagedFile = File(liveDb.path + RestoreSwapInitializer.STAGED_SUFFIX).apply { writeText("staged") }
        val markerFile = File(liveDb.path + RestoreSwapInitializer.MARKER_SUFFIX) // deliberately absent

        val blockedParent = File(scratch, "blocked_parent2").apply { mkdirs() }
        val mediaDir = File(blockedParent, "voice")
        val staged = File(blockedParent, "voice.media-staged").apply { mkdirs() }
        File(staged, "clip.mp3").writeText("clip-bytes")

        val settings = BackupSettingsStore(InMemoryDataStore())
        settings.setPendingRestore(stagedFile.path, safetyFile.path)
        settings.setPendingMediaRestore()
        val repo = repoWith(FakeRestoreSource(scratch), settings, mediaDirs = listOf(mediaDir))

        blockedParent.setWritable(false)
        try {
            assertFalse("marker must be absent to simulate a completed cold-start swap", markerFile.exists())
            repo.reconcilePendingRestore()

            assertFalse("the DB leg must still reclaim .safety despite the media leg failing", safetyFile.exists())
            assertFalse("the DB leg must still reclaim .staged despite the media leg failing", stagedFile.exists())
            assertFalse("the DB leg's own flag must still clear (D-01 independence)", settings.restorePending.first())
            assertTrue(
                "the media leg's flag must stay armed since it failed (D-01 independence)",
                settings.mediaRestorePending.first(),
            )
        } finally {
            blockedParent.setWritable(true)
        }
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

package io.github.ygaray.backupengine.source

import android.content.Context
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.documentfile.provider.DocumentFile
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.ygaray.backupengine.settings.BackupSettingsStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * On-device SAF round-trip suite for [LocalBackupSource] (BAK-01, BAK-02). Runs in the `:backup`
 * androidTest suite (Gate-1 / connectedAndroidTest).
 *
 * The real SAF picker can't be driven in an instrumented test, so the tree-resolution seams
 * ([LocalBackupSource.hasPersistableGrant] / [LocalBackupSource.resolveTree]) are overridden to point
 * at a real `file://` scratch tree — the rest of the class (createFile/openOutputStream/listFiles/
 * openInputStream/delete + the write-size verify) runs unmodified. A SECOND source with no overrides
 * is used to assert the folder-unavailable path fires on an un-granted URI (Pitfall 13).
 */
@RunWith(AndroidJUnit4::class)
class LocalBackupSourceTest {

    private lateinit var ctx: Context
    private lateinit var scratch: File
    private lateinit var treeDir: File
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var settings: BackupSettingsStore

    /** A source whose tree seams point at a real `file://` scratch dir (bypasses the SAF picker). */
    private lateinit var source: LocalBackupSource

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        scratch = File(ctx.cacheDir, "lbs-${System.nanoTime()}").apply { mkdirs() }
        treeDir = File(scratch, "tree").apply { mkdirs() }
        dataStore = PreferenceDataStoreFactory.create(
            produceFile = { File(scratch, "prefs.preferences_pb") },
        )
        settings = BackupSettingsStore(dataStore)

        source = object : LocalBackupSource(ctx, settings) {
            override fun hasPersistableGrant(uri: Uri): Boolean = true
            override fun resolveTree(uri: Uri): DocumentFile? = DocumentFile.fromFile(treeDir)
        }
    }

    @After
    fun tearDown() {
        scratch.deleteRecursively()
    }

    @Test
    fun putListGet_roundTripsBytesAndListsFile() = runBlocking {
        // Persist a (placeholder) tree URI so requireWritableTree() reads a non-null value; the
        // overridden resolveTree ignores its content and returns the file:// scratch tree.
        settings.setLocalSafTreeUri(Uri.fromFile(treeDir).toString())

        val payload = "caltracker-db-bytes-${System.nanoTime()}".toByteArray()
        val src = File(scratch, "snapshot.db").apply { writeBytes(payload) }

        val name = "caltracker-2026-07-01_120000.db"
        val ref = source.put(src, name)
        // SAF createFile may append a MIME-derived extension, so assert the stable prefix + .db
        // marker rather than exact equality (Pitfall 14).
        assertTrue("ref name keeps the caltracker- prefix", ref.name.startsWith("caltracker-"))
        assertTrue("ref name keeps the .db marker", ref.name.contains(".db"))
        assertEquals(payload.size.toLong(), ref.sizeBytes)

        val listed = source.list()
        assertTrue(
            "listing must contain the written backup",
            listed.any { it.ref == ref.ref },
        )
        // Only caltracker-*.db backups are listed.
        assertTrue(listed.all { it.name.startsWith("caltracker-") && it.name.contains(".db") })

        val fetched = source.get(ref)
        assertArrayEquals("round-tripped bytes must match", payload, fetched.readBytes())
    }

    @Test
    fun list_ignoresNonBackupFiles() = runBlocking {
        settings.setLocalSafTreeUri(Uri.fromFile(treeDir).toString())
        // A foreign file in the same tree must be excluded by the caltracker-*.db filter.
        File(treeDir, "notes.txt").writeText("unrelated")
        File(treeDir, "other.db").writeText("wrong prefix")

        val ok = File(scratch, "s.db").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val ref = source.put(ok, "caltracker-2026-07-01_130000.db")

        val listed = source.list()
        assertEquals(1, listed.size)
        assertEquals(ref.ref, listed.single().ref)
        assertTrue(listed.single().name.startsWith("caltracker-"))
    }

    @Test
    fun delete_removesTheBackup() = runBlocking {
        settings.setLocalSafTreeUri(Uri.fromFile(treeDir).toString())
        val src = File(scratch, "s.db").apply { writeBytes(byteArrayOf(9, 9)) }
        val ref = source.put(src, "caltracker-2026-07-01_140000.db")
        assertEquals(1, source.list().size)

        source.delete(ref)
        assertTrue("backup must be gone after delete", source.list().isEmpty())
    }

    @Test
    fun invalidUri_yieldsFolderUnavailable() = runBlocking {
        // A source with NO seam overrides: the real persistedUriPermissions check runs. The persisted
        // URI was never granted, so requireWritableTree must throw FolderUnavailableException
        // rather than silently no-op (Pitfall 13, T-15-06).
        val ungranted = LocalBackupSource(ctx, settings)
        settings.setLocalSafTreeUri("content://com.example.bogus/tree/deadbeef")
        val src = File(scratch, "s.db").apply { writeBytes(byteArrayOf(1)) }

        try {
            ungranted.put(src, "caltracker-x.db")
            fail("expected FolderUnavailableException for an un-granted URI")
        } catch (e: FolderUnavailableException) {
            assertNotNull(e.message)
        }
    }

    @Test
    fun noFolderPicked_yieldsFolderUnavailable() = runBlocking {
        // No URI persisted at all → the "no SAF folder has been picked" folder-unavailable path.
        val fresh = LocalBackupSource(ctx, settings)
        try {
            fresh.list()
            fail("expected FolderUnavailableException when no folder is picked")
        } catch (e: FolderUnavailableException) {
            assertNotNull(e.message)
        }
    }
}

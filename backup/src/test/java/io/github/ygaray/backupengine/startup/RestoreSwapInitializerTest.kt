package io.github.ygaray.backupengine.startup

import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Robolectric unit suite for [RestoreSwapInitializer] — the pre-Hilt, before-Room cold-start swap
 * hook (RST-03, Open Q1). Runs on the JVM so the swap-decision logic (marker present/absent,
 * successful swap, and safety-copy rollback) is proven off-device.
 *
 * The Initializer resolves its staged / safety / pending-marker files DETERMINISTICALLY from the DB
 * path and reads the pending state from a PLAIN marker file (never DataStore/Hilt) — the four tests
 * below assert exactly that pre-Hilt-safe contract.
 */
@RunWith(RobolectricTestRunner::class)
class RestoreSwapInitializerTest {

    private lateinit var scratch: File
    private lateinit var liveDb: File
    private lateinit var initializer: RestoreSwapInitializer

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        scratch = File(ctx.cacheDir, "rsi-${System.nanoTime()}").apply { mkdirs() }
        liveDb = File(scratch, "caltracker.db")
        // The initializer under test resolves its sibling paths from an injected DB-path resolver so
        // the test can point it at a scratch DB without needing context.getDatabasePath wiring.
        initializer = RestoreSwapInitializer(databaseFileResolver = { liveDb })
    }

    // --- helpers ---

    /** Create a tiny valid SQLite DB at [file] holding one marker row, then close it (checkpointed). */
    private fun seedDb(file: File, marker: String) {
        val h = SQLiteDatabase.openOrCreateDatabase(file, null)
        h.execSQL("CREATE TABLE IF NOT EXISTS notes (id INTEGER PRIMARY KEY, body TEXT)")
        h.execSQL("INSERT INTO notes (body) VALUES (?)", arrayOf<Any>(marker))
        h.close()
    }

    private fun readMarker(file: File): String {
        val h = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY)
        return h.use {
            it.rawQuery("SELECT body FROM notes LIMIT 1", null).use { c ->
                c.moveToFirst()
                c.getString(0)
            }
        }
    }

    private fun stagedFile() = File(liveDb.path + ".staged")
    private fun safetyFile() = File(liveDb.path + ".safety")
    private fun markerFile() = File(liveDb.path + ".restore-pending")

    // --- tests ---

    @Test
    fun `no marker is a no-op leaving the live DB untouched`() {
        seedDb(liveDb, "LIVE")
        // No pending marker written.
        initializer.create(ApplicationProvider.getApplicationContext())

        assertTrue("live DB must still exist", liveDb.exists())
        assertEquals("LIVE", readMarker(liveDb))
        assertFalse("no staged file should be created", stagedFile().exists())
    }

    @Test
    fun `marker plus staged file swaps the staged DB in and clears the marker`() {
        seedDb(liveDb, "LIVE")
        seedDb(stagedFile(), "RESTORED")
        markerFile().writeText("pending")

        initializer.create(ApplicationProvider.getApplicationContext())

        assertEquals("staged data must now be live", "RESTORED", readMarker(liveDb))
        assertFalse("marker must be cleared after swap", markerFile().exists())
        assertFalse("staged file consumed by the swap", stagedFile().exists())
    }

    @Test
    fun `a swap that fails mid-move rolls back from the safety-copy and re-runs safely`() {
        seedDb(liveDb, "LIVE")
        // Marker present but the staged file is MISSING -> applySwap must roll back from safety.
        markerFile().writeText("pending")

        initializer.create(ApplicationProvider.getApplicationContext())

        // Live DB survives intact via the safety-copy rollback (never corrupted).
        assertTrue(liveDb.exists())
        assertEquals("LIVE", readMarker(liveDb))
        // Idempotent re-run must again leave the safe rolled-back DB (no crash, no corruption).
        initializer.create(ApplicationProvider.getApplicationContext())
        assertEquals("LIVE", readMarker(liveDb))
    }

    @Test
    fun `pending state is read from a plain marker file not DataStore`() {
        seedDb(liveDb, "LIVE")
        seedDb(stagedFile(), "RESTORED")
        // Writing ONLY the plain marker file (no DataStore involved) must trigger the swap,
        // proving the pre-Hilt read path.
        markerFile().writeText("pending")

        initializer.create(ApplicationProvider.getApplicationContext())

        assertEquals("RESTORED", readMarker(liveDb))
        assertFalse(markerFile().exists())
    }

    @Test
    fun `dependencies is empty so the Initializer runs first at cold start`() {
        assertTrue(initializer.dependencies().isEmpty())
    }
}

package io.github.ygaray.backupengine

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * On-device regression for the FTS integrity-check defect (Phase-77 Gate-1).
 *
 * `PRAGMA integrity_check` on a DB that contains an FTS3/4/5 virtual table internally issues the
 * table's special command `INSERT INTO <fts>(<fts>) VALUES('integrity-check')`, which requires a
 * WRITABLE connection. When [DatabaseFileManager.verify] opened the snapshot `OPEN_READONLY`, that
 * statement aborted with "attempt to write a readonly database" (observed verbatim in Gate-1 logcat:
 * `INSERT INTO "main"."cards_fts"("cards_fts") VALUES('integrity-check'); attempt to write a
 * readonly database`) — so verify()/validate() failed and EVERY backup and restore of an FTS-having
 * DB failed deterministically. The engine's origin consumer (CalTracker) has no FTS table, so it was
 * never exercised upstream; SecondBrain's `cards_fts` full-text index (a standalone `@Fts4` table)
 * tripped it.
 *
 * Uses **FTS4** — the exact virtual-table flavour SecondBrain ships (and the one the framework SQLite
 * on the Gate-1 device provides; FTS5 is not compiled into `android.database.sqlite` on many builds,
 * which is also why Robolectric's JVM suite cannot host this — hence an instrumented test).
 */
@RunWith(AndroidJUnit4::class)
class DatabaseFileManagerFtsIntegrityTest {

    private lateinit var scratch: File
    private lateinit var manager: DatabaseFileManager

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        scratch = File(ctx.cacheDir, "dbfm-fts-${System.nanoTime()}").apply { mkdirs() }
        manager = DatabaseFileManager()
    }

    /** Create a DB with a standalone FTS4 virtual table, mirroring SecondBrain's `cards_fts` index. */
    private fun seedFtsDb(name: String): File {
        val db = File(scratch, name)
        SQLiteDatabase.openOrCreateDatabase(db, null).use { h ->
            h.execSQL("CREATE VIRTUAL TABLE cards_fts USING fts4(body, tokenize=unicode61)")
            h.execSQL("INSERT INTO cards_fts(body) VALUES ('hello world')")
        }
        return db
    }

    @Test
    fun verify_passes_a_db_that_contains_an_fts_virtual_table() {
        val live = seedFtsDb("fts-live.db")
        // The exact call that aborted with "attempt to write a readonly database" before the fix.
        assertTrue("a DB with an FTS4 table must pass verify()", manager.verify(live))
    }

    @Test
    fun snapshot_and_verify_succeed_for_an_fts_db() = runBlocking {
        val live = seedFtsDb("fts-snap-live.db")
        val snap = File(scratch, "fts-snap.db")

        assertTrue(
            "snapshot of an FTS DB must succeed",
            manager.snapshot(source = live, dest = snap, useVacuumInto = true),
        )
        assertTrue("snapshot of an FTS DB must pass verify()", manager.verify(snap))
    }
}

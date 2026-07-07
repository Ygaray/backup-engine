package io.github.ygaray.backupengine

import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import io.github.ygaray.backupengine.model.BackupResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Robolectric unit suite for [DatabaseFileManager] — the byte-identical backup/restore engine core
 * (BAK-04, RST-03). Runs on the JVM so BOTH snapshot branches (VACUUM INTO and the API 26-29
 * `wal_checkpoint(TRUNCATE)`+copy fallback) are exercised on one machine — the Gate-1 device only
 * ever hits the VACUUM INTO branch, so the fallback would otherwise ship untested (RESEARCH Pitfall 1).
 */
@RunWith(RobolectricTestRunner::class)
class DatabaseFileManagerTest {

    private lateinit var scratch: File
    private lateinit var manager: DatabaseFileManager

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        scratch = File(ctx.cacheDir, "dbfm-${System.nanoTime()}").apply { mkdirs() }
        manager = DatabaseFileManager()
    }

    // --- helpers ---

    /**
     * Seed a WAL-mode SQLite DB with one row, deliberately leaving that row uncheckpointed in the
     * `-wal` sidecar (WAL autocheckpoint disabled + no explicit checkpoint). A copy WITHOUT a prior
     * checkpoint would therefore miss the row — proving the snapshot checkpoints before copy.
     */
    private fun seedWalDb(name: String, marker: String): File {
        val db = File(scratch, name)
        val handle = SQLiteDatabase.openOrCreateDatabase(db, null)
        // journal_mode/wal_autocheckpoint PRAGMAs return a result row -> must use rawQuery, not execSQL.
        handle.rawQuery("PRAGMA journal_mode=WAL", null).use { it.moveToFirst() }
        handle.rawQuery("PRAGMA wal_autocheckpoint=0", null).use { it.moveToFirst() }
        handle.execSQL("CREATE TABLE notes (id INTEGER PRIMARY KEY, body TEXT)")
        handle.execSQL("INSERT INTO notes (body) VALUES (?)", arrayOf<Any>(marker))
        // Do NOT close (closing would checkpoint). Keep the handle open so the row lives in -wal.
        openHandles += handle
        return db
    }

    private val openHandles = mutableListOf<SQLiteDatabase>()

    private fun readMarkers(db: File): List<String> {
        val h = SQLiteDatabase.openDatabase(db.path, null, SQLiteDatabase.OPEN_READONLY)
        return h.use { conn ->
            val out = mutableListOf<String>()
            conn.rawQuery("SELECT body FROM notes", null).use { c ->
                while (c.moveToNext()) out += c.getString(0)
            }
            out
        }
    }

    // --- Task 1: snapshot ---

    @Test
    fun `VACUUM INTO branch snapshots a checkpointed sidecar-free integrity-clean db`() = runBlocking {
        val live = seedWalDb("live-vac.db", "row-in-wal-vac")
        val out = File(scratch, "snap-vac.db")

        val ok = manager.snapshot(source = live, dest = out, useVacuumInto = true)

        assertTrue("snapshot must succeed", ok)
        assertTrue("output exists and non-empty", out.exists() && out.length() > 0)
        // The uncheckpointed row must be present — proving a checkpoint happened before copy.
        assertEquals(listOf("row-in-wal-vac"), readMarkers(out))
        // A self-contained snapshot must have no companion sidecars.
        assertFalse("no -wal sidecar", File(out.path + "-wal").exists())
        assertFalse("no -shm sidecar", File(out.path + "-shm").exists())
        assertTrue("integrity clean", manager.verify(out))
    }

    @Test
    fun `fallback branch (checkpoint+copy) yields a valid complete integrity-clean snapshot`() = runBlocking {
        val live = seedWalDb("live-fb.db", "row-in-wal-fb")
        val out = File(scratch, "snap-fb.db")

        val ok = manager.snapshot(source = live, dest = out, useVacuumInto = false)

        assertTrue("fallback snapshot must succeed", ok)
        assertTrue("output exists and non-empty", out.exists() && out.length() > 0)
        assertEquals(listOf("row-in-wal-fb"), readMarkers(out))
        assertFalse("no -wal sidecar on the snapshot", File(out.path + "-wal").exists())
        assertFalse("no -shm sidecar on the snapshot", File(out.path + "-shm").exists())
        assertTrue("integrity clean", manager.verify(out))
    }

    @Test
    fun `verify rejects a truncated or corrupt output`() {
        val corrupt = File(scratch, "corrupt.db")
        corrupt.writeText("this is not a sqlite database at all")
        assertFalse("corrupt file must fail verify", manager.verify(corrupt))

        val empty = File(scratch, "empty.db")
        empty.createNewFile()
        assertFalse("zero-size file must fail verify", manager.verify(empty))
    }

    // --- Task 2: user_version guard, integrity refusal, atomic swap + rollback ---

    /** Create a plain (non-WAL) SQLite DB stamped with [userVersion] and one marker row. */
    private fun seedVersionedDb(name: String, userVersion: Int, marker: String): File {
        val db = File(scratch, name)
        SQLiteDatabase.openOrCreateDatabase(db, null).use { h ->
            h.execSQL("CREATE TABLE notes (id INTEGER PRIMARY KEY, body TEXT)")
            h.execSQL("INSERT INTO notes (body) VALUES (?)", arrayOf<Any>(marker))
            h.version = userVersion
        }
        return db
    }

    @Test
    fun `readUserVersion returns the stamped version`() {
        val db = seedVersionedDb("versioned.db", userVersion = 5, marker = "x")
        assertEquals(5, manager.readUserVersion(db))
    }

    @Test
    fun `validate refuses a newer-schema candidate and allows equal or older`() {
        val newer = seedVersionedDb("newer.db", userVersion = 6, marker = "n")
        val equal = seedVersionedDb("equal.db", userVersion = 5, marker = "e")
        val older = seedVersionedDb("older.db", userVersion = 3, marker = "o")

        assertNotNull("newer must be refused", manager.validate(newer, currentSchemaVersion = 5))
        assertEquals(
            BackupResult.Reason.SchemaTooNew,
            (manager.validate(newer, 5) as BackupResult.Failure).reason,
        )
        assertNull("equal schema allowed", manager.validate(equal, currentSchemaVersion = 5))
        assertNull("older schema allowed", manager.validate(older, currentSchemaVersion = 5))
    }

    @Test
    fun `validate refuses a non-SQLite or corrupt file as NotAValidBackup`() {
        val junk = File(scratch, "junk.db")
        junk.writeText("definitely not sqlite")
        val result = manager.validate(junk, currentSchemaVersion = 5)
        assertNotNull("corrupt file must be refused", result)
        assertEquals(
            BackupResult.Reason.NotAValidBackup,
            (result as BackupResult.Failure).reason,
        )
    }

    @Test
    fun `applySwap deletes target sidecars and moves the staged file into place`() = runBlocking {
        val target = seedVersionedDb("live.db", userVersion = 5, marker = "old-live")
        // Simulate stale sidecars on the live target that MUST be removed before the swap.
        File(target.path + "-wal").writeText("stale-wal")
        File(target.path + "-shm").writeText("stale-shm")
        val staged = seedVersionedDb("staged.db", userVersion = 5, marker = "restored")
        val safety = File(scratch, "live.db.safety")

        val ok = manager.applySwap(target = target, staged = staged, safetyCopy = safety)

        assertTrue("swap succeeds", ok)
        assertEquals(listOf("restored"), readMarkers(target))
        assertFalse("stale -wal gone", File(target.path + "-wal").exists())
        assertFalse("stale -shm gone", File(target.path + "-shm").exists())
    }

    @Test
    fun `failed swap rolls back from the safety-copy leaving live data intact`() = runBlocking {
        val target = seedVersionedDb("live2.db", userVersion = 5, marker = "original-live")
        val safety = File(scratch, "live2.db.safety")
        // A staged path that does not exist forces the move to fail mid-swap -> rollback.
        val missingStaged = File(scratch, "does-not-exist.db")

        val ok = manager.applySwap(target = target, staged = missingStaged, safetyCopy = safety)

        assertFalse("swap must report failure", ok)
        // Live data must be intact (restored from the safety-copy).
        assertTrue("live target still present", target.exists())
        assertEquals(listOf("original-live"), readMarkers(target))
        // CR-02: a swap that rolled back cleanly must pass verify() on the restored live DB.
        assertTrue("rolled-back target is integrity-clean", manager.verify(target))
    }

    // --- CR-02: guarded, verified rollback (RED-first failure-injection) ---

    /**
     * A [DatabaseFileManager] whose safety-restore step is forced to fail, so we can exercise the
     * doubly-failing swap (move fails, THEN the rollback also fails) without needing a full disk.
     */
    private class RollbackFailingManager : DatabaseFileManager() {
        override fun restoreFromSafety(target: File, safetyCopy: File): Boolean = false
    }

    /**
     * When the staged→target move fails AND the safety-copy rollback ALSO fails to produce a
     * verified-good live DB, applySwap must NOT silently return: it throws a hard failure and
     * preserves the safety copy (our only remaining good copy) — never a silent boot with no live DB.
     */
    @Test
    fun `doubly-failing swap preserves the safety copy and signals loudly`() = runBlocking {
        val failing = RollbackFailingManager()
        val target = seedVersionedDb("live3.db", userVersion = 5, marker = "original-live")
        // A staged path that does not exist forces the move to fail -> rollback path.
        val missingStaged = File(scratch, "missing-staged.db")
        val safety = File(scratch, "live3.db.safety")

        val threw = try {
            failing.applySwap(target = target, staged = missingStaged, safetyCopy = safety)
            false
        } catch (_: Exception) {
            true
        }

        assertTrue("a doubly-failing swap must signal a hard failure (throw), not return silently", threw)
        assertTrue("the safety copy is preserved as the only remaining recovery source", safety.exists())
        assertTrue("preserved safety copy is a valid DB", manager.verify(safety))
    }

    /** A doubly-failing swap is distinguishable from a clean rollback: clean rollback returns false, does not throw. */
    @Test
    fun `clean rollback returns false without throwing (distinct from the hard-failure path)`() = runBlocking {
        val target = seedVersionedDb("live4.db", userVersion = 5, marker = "original-live")
        val safety = File(scratch, "live4.db.safety")
        val missingStaged = File(scratch, "missing-staged4.db")

        // safety copy is taken from the (valid) live target inside applySwap, so rollback verifies good.
        val ok = manager.applySwap(target = target, staged = missingStaged, safetyCopy = safety)

        assertFalse("clean rollback returns false", ok)
        assertTrue("target restored and valid", manager.verify(target))
        assertEquals(listOf("original-live"), readMarkers(target))
    }
}

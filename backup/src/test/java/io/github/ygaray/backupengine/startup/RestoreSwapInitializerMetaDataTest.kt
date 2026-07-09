package io.github.ygaray.backupengine.startup

import android.content.ComponentName
import android.content.Context
import android.content.pm.ProviderInfo
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.File

/**
 * Robolectric suite proving the H-01 fix (v1.1.1): [RestoreSwapInitializer]'s DEFAULT
 * [RestoreSwapInitializer.databaseFileResolver] actually HONORS a consumer-declared
 * `backupengine.databaseName` `<meta-data>` value that is scoped to the androidx.startup
 * `InitializationProvider` (`ProviderInfo.metaData`) — not the app-level `ApplicationInfo.metaData`.
 *
 * The regression this guards against: the prior `readDatabaseName()` read `getApplicationInfo(...)`,
 * which never sees provider-nested meta-data, so it silently fell through to
 * [RestoreSwapInitializer.DEFAULT_DB_NAME] (`"caltracker.db"`). For CalTracker that fallback was
 * byte-identical to the intended value, masking the inert read from any default-only test. These
 * tests therefore assert a NON-DEFAULT value (`"othertestapp.db"`) is resolved end-to-end, so the
 * fallback identity can no longer hide the defect — plus the D-02a fallback-when-absent case.
 *
 * Mechanism: register a [ProviderInfo] for the androidx.startup provider on Robolectric's
 * [org.robolectric.shadows.ShadowPackageManager] with a metaData [Bundle] carrying the key, then
 * drive the Initializer's cold-start swap and assert it resolves the DB path for that name (by
 * observing which sibling `.staged`/`.restore-pending` files it consumes).
 */
@RunWith(RobolectricTestRunner::class)
class RestoreSwapInitializerMetaDataTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()

    private val startupProvider =
        ComponentName(ctx.packageName, RestoreSwapInitializer.STARTUP_PROVIDER_CLASS)

    /**
     * Register the androidx.startup [ProviderInfo] with (optionally) the DB-name meta-data so the
     * DEFAULT resolver reads it via `getProviderInfo(...).metaData` exactly as it does on device.
     */
    private fun registerStartupProvider(dbNameMeta: String?) {
        val pi = ProviderInfo().apply {
            name = RestoreSwapInitializer.STARTUP_PROVIDER_CLASS
            packageName = ctx.packageName
            authority = "${ctx.packageName}.androidx-startup"
            if (dbNameMeta != null) {
                metaData = Bundle().apply {
                    putString(RestoreSwapInitializer.META_DATA_DB_NAME, dbNameMeta)
                }
            }
        }
        shadowOf(ctx.packageManager).addOrUpdateProvider(pi)
    }

    /**
     * Arm a pending restore for the DB named [dbName] and run the DEFAULT-resolver Initializer.
     * Returns the live DB [File] the resolver targeted so the caller can assert WHICH db was chosen.
     * A staged DB is provided so a correctly-resolved swap consumes the marker (observable success).
     */
    private fun runDefaultResolverSwap(dbName: String): File {
        val liveDb = File(ctx.getDatabasePath(dbName).path).apply { parentFile?.mkdirs() }
        val staged = File(liveDb.path + RestoreSwapInitializer.STAGED_SUFFIX)
        val marker = File(liveDb.path + RestoreSwapInitializer.MARKER_SUFFIX)
        // Seed a live + staged DB so a resolver that picks THIS name performs a real, observable swap.
        seedDb(liveDb, "LIVE")
        seedDb(staged, "RESTORED")
        marker.writeText("pending")

        // DEFAULT resolver (no override) — exercises the production readDatabaseName() path.
        RestoreSwapInitializer().create(ctx)
        return liveDb
    }

    private fun seedDb(file: File, marker: String) {
        val h = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(file, null)
        h.execSQL("CREATE TABLE IF NOT EXISTS notes (id INTEGER PRIMARY KEY, body TEXT)")
        h.execSQL("INSERT INTO notes (body) VALUES (?)", arrayOf<Any>(marker))
        h.close()
    }

    private fun readMarker(file: File): String {
        val h = android.database.sqlite.SQLiteDatabase.openDatabase(
            file.path, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY,
        )
        return h.use {
            it.rawQuery("SELECT body FROM notes LIMIT 1", null).use { c ->
                c.moveToFirst()
                c.getString(0)
            }
        }
    }

    // --- tests ---

    @Test
    fun `provider-scoped meta-data with a NON-DEFAULT name is actually honored (H-01)`() {
        // Consumer declares a DB name that is NOT the caltracker.db fallback.
        registerStartupProvider(dbNameMeta = "othertestapp.db")

        val nonDefaultDb = runDefaultResolverSwap("othertestapp.db")
        // If the resolver honored the provider meta-data, it swapped the othertestapp.db and
        // consumed its marker (RESTORED now live, marker gone).
        assertEquals(
            "resolver must target the consumer-declared othertestapp.db, not the fallback",
            "RESTORED",
            readMarker(nonDefaultDb),
        )
        assertFalse(
            "othertestapp.db marker must be cleared -> its swap ran",
            File(nonDefaultDb.path + RestoreSwapInitializer.MARKER_SUFFIX).exists(),
        )

        // Proof the fallback was NOT chosen: an armed caltracker.db (the old always-wrong target)
        // is left completely untouched because the resolver never pointed at it.
        val fallbackDb = File(ctx.getDatabasePath("caltracker.db").path).apply { parentFile?.mkdirs() }
        val fallbackMarker = File(fallbackDb.path + RestoreSwapInitializer.MARKER_SUFFIX)
        seedDb(fallbackDb, "FALLBACK_LIVE")
        seedDb(File(fallbackDb.path + RestoreSwapInitializer.STAGED_SUFFIX), "FALLBACK_STAGED")
        fallbackMarker.writeText("pending")
        // Re-declare the non-default provider meta-data and re-run: resolver must STILL pick
        // othertestapp.db, leaving the caltracker.db marker armed and untouched.
        registerStartupProvider(dbNameMeta = "othertestapp.db")
        RestoreSwapInitializer().create(ctx)
        assertTrue(
            "caltracker.db must be untouched — the resolver honored othertestapp.db, not the fallback",
            fallbackMarker.exists(),
        )
        assertEquals("FALLBACK_LIVE", readMarker(fallbackDb))
    }

    @Test
    fun `absent meta-data falls back to the caltracker-db default (D-02a safety net)`() {
        // Provider registered but WITHOUT the meta-data -> readDatabaseName() returns null.
        registerStartupProvider(dbNameMeta = null)

        val fallbackDb = runDefaultResolverSwap("caltracker.db")
        assertEquals(
            "with no meta-data the resolver must fall back to caltracker.db and swap it",
            "RESTORED",
            readMarker(fallbackDb),
        )
        assertFalse(
            "caltracker.db marker cleared -> fallback resolved correctly",
            File(fallbackDb.path + RestoreSwapInitializer.MARKER_SUFFIX).exists(),
        )
    }
}

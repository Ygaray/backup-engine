package io.github.ygaray.backupengine

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import io.github.ygaray.backupengine.settings.BackupSettingsStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Wave-0 Robolectric suite for the Phase-18 schedule keys on [BackupSettingsStore] (SCH-03/SCH-05,
 * D-04/D-05/D-07). Uses the same real temp-file DataStore scaffold as [BackupSettingsStoreTest]:
 *  - a frequency round-trips per destination (stored as the migration-safe enum name),
 *  - an absent OR corrupt frequency key reads as Off (untrusted-input posture, T-15-08),
 *  - local and drive frequencies are independent,
 *  - needsReauth round-trips true/false and is cleared on connect + disconnect (D-07c).
 */
@RunWith(RobolectricTestRunner::class)
class BackupSettingsStoreScheduleTest {

    private lateinit var scratch: File
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var store: BackupSettingsStore

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        scratch = File(ctx.cacheDir, "bss-sched-${System.nanoTime()}").apply { mkdirs() }
        dataStore = PreferenceDataStoreFactory.create(
            produceFile = { File(scratch, "backup_prefs.preferences_pb") },
        )
        store = BackupSettingsStore(dataStore)
    }

    @After
    fun tearDown() {
        scratch.deleteRecursively()
    }

    @Test
    fun localFrequency_defaultsToOff_thenRoundTripsWeekly() = runBlocking {
        assertEquals(BackupFrequency.Off, store.localBackupFrequency.first())
        store.setLocalBackupFrequency(BackupFrequency.Weekly)
        assertEquals(BackupFrequency.Weekly, store.localBackupFrequency.first())
    }

    @Test
    fun driveFrequency_isIndependentOfLocal() = runBlocking {
        store.setLocalBackupFrequency(BackupFrequency.Daily)
        assertEquals("drive stays Off while only local was set", BackupFrequency.Off, store.driveBackupFrequency.first())
        store.setDriveBackupFrequency(BackupFrequency.Weekly)
        assertEquals(BackupFrequency.Weekly, store.driveBackupFrequency.first())
        assertEquals("local unchanged by the drive write", BackupFrequency.Daily, store.localBackupFrequency.first())
    }

    @Test
    fun corruptFrequencyValue_fallsBackToOff_neverThrows() = runBlocking {
        // Poison the local frequency key with an unknown enum name — valueOf would throw; the
        // runCatching fallback must yield Off rather than crash the flow.
        dataStore.edit { it[stringPreferencesKey("local_backup_frequency")] = "NotAFrequency" }
        assertEquals(BackupFrequency.Off, store.localBackupFrequency.first())
    }

    @Test
    fun needsReauth_roundTrips() = runBlocking {
        assertFalse("unset needs-reauth reads as false", store.needsReauth.first())
        store.setNeedsReauth(true)
        assertTrue(store.needsReauth.first())
        store.setNeedsReauth(false)
        assertFalse(store.needsReauth.first())
    }

    @Test
    fun connect_clearsNeedsReauth() = runBlocking {
        store.setNeedsReauth(true)
        assertTrue(store.needsReauth.first())
        store.setConnected("me@example.com")
        assertFalse("a fresh grant supersedes a stale needs-reauth flag (D-07c)", store.needsReauth.first())
    }

    @Test
    fun disconnect_clearsNeedsReauth() = runBlocking {
        store.setNeedsReauth(true)
        store.clearConnected()
        assertFalse("a disconnected account has no grant to re-auth (D-07c)", store.needsReauth.first())
    }
}

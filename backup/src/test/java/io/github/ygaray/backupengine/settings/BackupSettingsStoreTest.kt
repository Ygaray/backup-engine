package io.github.ygaray.backupengine.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Robolectric JVM suite for [BackupSettingsStore] (BAK-05). Runs off-device against a real
 * temp-file Preferences DataStore so the round-trip, "never backed up" default, and the
 * corrupt-value fallback (T-15-08) are all exercised on one machine.
 *
 * The store reuses the DataStore handed in via `BackupConfig` — here we build a throwaway one over a
 * temp file to stand in for `caltracker_prefs`; no second production store is created.
 */
@RunWith(RobolectricTestRunner::class)
class BackupSettingsStoreTest {

    private lateinit var scratch: File
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var store: BackupSettingsStore

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        scratch = File(ctx.cacheDir, "bss-${System.nanoTime()}").apply { mkdirs() }
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
    fun safTreeUri_roundTrips() = runBlocking {
        assertNull("unset URI reads as null", store.localSafTreeUri.first())
        store.setLocalSafTreeUri("content://tree/primary%3ABackups")
        assertEquals("content://tree/primary%3ABackups", store.localSafTreeUri.first())
    }

    @Test
    fun lastBackupResult_roundTripsTimestampAndSuccess() = runBlocking {
        store.recordLocalBackupResult(timestampMillis = 1_700_000_000_000L, ok = true)
        val status = store.lastLocalBackupStatus.first()
        assertTrue(status is LocalBackupStatus.Success)
        assertEquals(1_700_000_000_000L, (status as LocalBackupStatus.Success).timestampMillis)
    }

    @Test
    fun lastBackupResult_roundTripsFailure() = runBlocking {
        store.recordLocalBackupResult(timestampMillis = 42L, ok = false)
        val status = store.lastLocalBackupStatus.first()
        assertTrue(status is LocalBackupStatus.Failure)
        assertEquals(42L, (status as LocalBackupStatus.Failure).timestampMillis)
    }

    @Test
    fun absentStatus_readsAsNeverBackedUp_notACrash() = runBlocking {
        assertEquals(LocalBackupStatus.NeverBackedUp, store.lastLocalBackupStatus.first())
    }

    @Test
    fun corruptResultFlag_fallsBackToDefault_neverThrows() = runBlocking {
        // Poison the OK key with a wrong-typed value: a String where a Boolean is expected.
        // Reading it back would throw a ClassCastException inside the map — runCatching must swallow
        // it and yield the NeverBackedUp default rather than crashing the flow.
        dataStore.edit { it[stringPreferencesKey("last_local_backup_ok")] = "not-a-boolean" }
        assertEquals(LocalBackupStatus.NeverBackedUp, store.lastLocalBackupStatus.first())
    }

    // ---- Phase 17 (BAK-05 → Drive): per-destination Drive status surface ----

    @Test
    fun driveStatus_absent_readsAsNeverBackedUp() = runBlocking {
        assertEquals(DriveBackupStatus.NeverBackedUp, store.lastDriveBackupStatus.first())
    }

    @Test
    fun driveResult_roundTripsTimestampAndSuccess() = runBlocking {
        store.recordDriveBackupResult(timestampMillis = 1_700_000_000_000L, ok = true)
        val status = store.lastDriveBackupStatus.first()
        assertTrue(status is DriveBackupStatus.Success)
        assertEquals(1_700_000_000_000L, (status as DriveBackupStatus.Success).timestampMillis)
    }

    @Test
    fun driveResult_roundTripsFailure() = runBlocking {
        store.recordDriveBackupResult(timestampMillis = 42L, ok = false)
        val status = store.lastDriveBackupStatus.first()
        assertTrue(status is DriveBackupStatus.Failure)
        assertEquals(42L, (status as DriveBackupStatus.Failure).timestampMillis)
    }

    @Test
    fun corruptDriveResultFlag_fallsBackToNeverBackedUp_neverThrows() = runBlocking {
        // Poison the Drive OK key with a wrong-typed value: a String where a Boolean is expected.
        // Reading it back would throw a ClassCastException inside the map — runCatching must swallow
        // it and yield the NeverBackedUp default rather than crashing the flow (T-15-08 / T-17-04).
        dataStore.edit { it[stringPreferencesKey("last_drive_backup_ok")] = "not-a-boolean" }
        assertEquals(DriveBackupStatus.NeverBackedUp, store.lastDriveBackupStatus.first())
    }

    @Test
    fun driveAndLocalStatus_areIndependent() = runBlocking {
        // A recorded Drive result must not disturb the local status surface, and vice versa —
        // they share the single store but occupy distinct keys.
        store.recordLocalBackupResult(timestampMillis = 100L, ok = true)
        store.recordDriveBackupResult(timestampMillis = 200L, ok = false)

        val local = store.lastLocalBackupStatus.first()
        val drive = store.lastDriveBackupStatus.first()
        assertTrue(local is LocalBackupStatus.Success)
        assertEquals(100L, (local as LocalBackupStatus.Success).timestampMillis)
        assertTrue(drive is DriveBackupStatus.Failure)
        assertEquals(200L, (drive as DriveBackupStatus.Failure).timestampMillis)
    }

    @Test
    fun pendingRestore_flagAndPathsSetAndClear() = runBlocking {
        // Defaults before arming.
        assertFalse(store.restorePending.first())
        assertNull(store.restoreStagedPath.first())
        assertNull(store.restoreSafetyPath.first())

        store.setPendingRestore(stagedPath = "/data/staged.db", safetyPath = "/data/safety.db")
        assertTrue(store.restorePending.first())
        assertEquals("/data/staged.db", store.restoreStagedPath.first())
        assertEquals("/data/safety.db", store.restoreSafetyPath.first())

        store.clearPendingRestore()
        assertFalse(store.restorePending.first())
        assertNull(store.restoreStagedPath.first())
        assertNull(store.restoreSafetyPath.first())
    }
}

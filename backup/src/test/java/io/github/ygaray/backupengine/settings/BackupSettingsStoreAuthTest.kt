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
 * Robolectric JVM suite for the Phase-16 auth keys on [BackupSettingsStore] (ACC-01/ACC-03, D-01).
 * Mirrors [BackupSettingsStoreTest]'s real temp-file DataStore scaffold so the three non-secret keys
 * round-trip and the corrupt-value fallback (T-15-08) are exercised off-device.
 *
 * The load-bearing test is [connect_persistsNoTokenMaterial] — it proves SC#2 ("no token in the
 * store") at the unit level *by construction*: the store simply has no token key, so no persisted
 * value can carry token material. The byte-level device grep is the Gate-1 complement.
 */
@RunWith(RobolectricTestRunner::class)
class BackupSettingsStoreAuthTest {

    private lateinit var scratch: File
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var store: BackupSettingsStore

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        scratch = File(ctx.cacheDir, "bss-auth-${System.nanoTime()}").apply { mkdirs() }
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
    fun googleAccountEmail_roundTrips() = runBlocking {
        assertNull("unset email reads as null", store.googleAccountEmail.first())
        store.setConnected("me@example.com")
        assertEquals("me@example.com", store.googleAccountEmail.first())
    }

    @Test
    fun googleAuthGranted_roundTrips() = runBlocking {
        assertFalse("unset granted reads as false", store.googleAuthGranted.first())
        store.setConnected("me@example.com")
        assertTrue(store.googleAuthGranted.first())
    }

    @Test
    fun revokePending_roundTrips() = runBlocking {
        assertFalse("unset revoke-pending reads as false", store.revokePending.first())
        store.setRevokePending(true)
        assertTrue(store.revokePending.first())
        store.setRevokePending(false)
        assertFalse(store.revokePending.first())
    }

    @Test
    fun setConnected_clearsRevokePending() = runBlocking {
        // Offline disconnect armed a pending revoke; a fresh successful connect clears it (chosen
        // simplest-robust scheme — full retry-on-launch deferred to Phase 18).
        store.setRevokePending(true)
        assertTrue(store.revokePending.first())
        store.setConnected("me@example.com")
        assertFalse("successful connect clears revoke-pending", store.revokePending.first())
    }

    @Test
    fun clearConnected_removesEmailAndGranted() = runBlocking {
        store.setConnected("me@example.com")
        store.clearConnected()
        assertNull("email cleared", store.googleAccountEmail.first())
        assertFalse("granted cleared", store.googleAuthGranted.first())
    }

    @Test
    fun connect_persistsNoTokenMaterial() = runBlocking {
        // SC#2 (the load-bearing one): after a connect, iterate EVERY persisted preference and assert
        // nothing holds token material — no "ya29." access-token, no bearer substring, and no key
        // named like an access token. Passes by construction: there is no token key.
        store.setConnected("me@example.com")
        store.setRevokePending(true)

        val prefs = dataStore.data.first()
        prefs.asMap().forEach { (key, value) ->
            val keyName = key.name.lowercase()
            assertFalse(
                "no persisted key should be named like an access token (found '$keyName')",
                keyName.contains("token") || keyName.contains("access_token") ||
                    keyName.contains("bearer"),
            )
            val text = value.toString().lowercase()
            assertFalse(
                "no persisted value should carry token material (key '$keyName')",
                text.contains("ya29.") || text.contains("bearer "),
            )
        }
    }

    @Test
    fun corruptGrantedFlag_fallsBackToFalse_neverThrows() = runBlocking {
        // Poison the granted key with a wrong-typed value: a String where a Boolean is expected.
        // Reading it back would throw a ClassCastException inside the map — runCatching must swallow
        // it and yield the false default (T-15-08), mirroring BackupSettingsStoreTest's test.
        dataStore.edit { it[stringPreferencesKey("google_auth_granted")] = "not-a-boolean" }
        assertFalse(store.googleAuthGranted.first())
    }
}

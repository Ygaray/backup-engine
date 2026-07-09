package io.github.ygaray.backupengine.source

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.test.core.app.ApplicationProvider
import io.github.ygaray.backupengine.BackupConfig
import io.github.ygaray.backupengine.settings.BackupSettingsStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Wave-0 RED spec for ENG-02 / D-02a — the byte-identity naming de-hardcode (Phase 22, plan 22-04).
 *
 * The v1.0.0 [LocalBackupSource] carries its OWN hardcoded `companion object` constants
 * (`PREFIX = "caltracker-"`, `isBackupName` as a static `internal fun`). This suite pins the invariant
 * that after the Wave-2 de-hardcode — where the prefix is derived from `config.appName + "-"` per
 * D-02b, aligning to [io.github.ygaray.backupengine.BackupRepository.backupName]'s source of truth —
 * the CONFIG-DRIVEN recognition of a backup name is BYTE-IDENTICAL to the old hardcoded behavior for
 * `appName = "caltracker"`.
 *
 * D-02a is the single most important safety proof in the phase: existing on-device / Drive
 * `caltracker-*.db` backups must stay recognized, listed, and restorable after the de-hardcode.
 *
 * ### RED-by-design (Wave 0)
 * These assertions reference symbols that DO NOT EXIST YET at v1.0.0 and are added by plan 22-04
 * (RESEARCH Pattern 2, Option A):
 *  - a `config: BackupConfig` constructor param on [LocalBackupSource] (v1.0.0 ctor is `(context, settings)`),
 *  - `isBackupName` becoming an INSTANCE method (v1.0.0 has it only as a `companion object internal fun`).
 *
 * Until 22-04 lands, `:backup:compileDebugUnitTestKotlin` FAILS with "unresolved reference" on the
 * config ctor param and the instance `isBackupName` call — that RED compile failure IS the correct,
 * expected Wave-0 outcome; this file is the executable spec 22-04 turns GREEN. Do NOT edit
 * LocalBackupSource.kt to make it compile — Wave 2 owns that change.
 */
@RunWith(RobolectricTestRunner::class)
class LocalBackupSourceNamingTest {

    private lateinit var settings: BackupSettingsStore
    private lateinit var config: FakeBackupConfig
    private lateinit var source: LocalBackupSource

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        settings = BackupSettingsStore(InMemoryDataStore())
        // appName="caltracker" is the CalTracker host value — the de-hardcoded prefix
        // "$appName-" == "caltracker-" MUST be byte-identical to the v1.0.0 hardcoded PREFIX.
        config = FakeBackupConfig()
        // RED: v1.0.0 LocalBackupSource ctor is (context, settings) with NO BackupConfig param.
        // Plan 22-04 (RESEARCH Pattern 2 Option A) adds the config param so the prefix is
        // config-driven. This construction does not compile until that lands.
        source = LocalBackupSource(ctx, settings, config)
    }

    @Test
    fun `canonical caltracker backup name is recognized (config-driven == hardcoded)`() {
        // The exact v1.0.0 `caltracker-<yyyy-MM-dd>_<HHmm>.db` scheme (BAK-04). The config-driven
        // prefix ("caltracker-") must recognize it byte-identically to the old hardcoded PREFIX.
        // RED: `isBackupName` is an instance method only after 22-04 (v1.0.0 has it on the companion).
        assertTrue(
            "a canonical caltracker-<ts>.db backup must be recognized under the config-driven prefix",
            source.isBackupName("caltracker-2026-07-01_1430.db"),
        )
    }

    @Test
    fun `SAF MIME-suffixed backup name is recognized (contains-db marker, not exact suffix)`() {
        // SAF's DocumentFile.createFile can append a MIME-derived extension, producing
        // "caltracker-….db.bin" (documented SAF quirk, Pitfall 14). v1.0.0 matches on the prefix
        // + the presence of ".db" (NOT an exact ".db" suffix), so this must still be recognized —
        // the config-driven predicate must preserve that contains-".db" behavior byte-identically.
        assertTrue(
            "a SAF MIME-suffixed caltracker-x.db.bin must still be recognized (contains .db marker)",
            source.isBackupName("caltracker-x.db.bin"),
        )
    }

    @Test
    fun `foreign-app backup name is NOT recognized`() {
        // A name from another app's prefix must be rejected exactly as the hardcoded "caltracker-"
        // PREFIX rejected it — the config-driven prefix must not widen the match surface.
        assertFalse(
            "a foreign-app name must not be recognized under the caltracker-derived prefix",
            source.isBackupName("other-app-1.db"),
        )
    }

    // --- fakes (mirrors RetentionPruneTest's scaffold; do NOT reinvent) ---

    private class FakeBackupConfig : BackupConfig {
        override val appName: String = "caltracker"
        override val databaseFile: File = File("/dev/null")
        override val currentSchemaVersion: Int = 5
        override val dataStore: DataStore<Preferences> = InMemoryDataStore()
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

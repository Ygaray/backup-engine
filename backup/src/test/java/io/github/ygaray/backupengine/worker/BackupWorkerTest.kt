package io.github.ygaray.backupengine.worker

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import io.github.ygaray.backupengine.BackupConfig
import io.github.ygaray.backupengine.BackupRepository
import io.github.ygaray.backupengine.DatabaseFileManager
import io.github.ygaray.backupengine.ScheduledOutcome
import io.github.ygaray.backupengine.settings.BackupSettingsStore
import io.github.ygaray.backupengine.source.BackupSource
import io.github.ygaray.backupengine.model.BackupRef
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Wave-0 JVM suite for [BackupWorker]'s outcome→Result mapping (SCH-05, D-07b, T-18-03/T-18-04).
 *
 * Drives the REAL [BackupWorker.doWork] via [TestListenableWorkerBuilder], overriding only the
 * repository-resolution seam with a fake [BackupRepository] subclass that returns a canned
 * [ScheduledOutcome]. Asserts the full D-07b table plus the runAttemptCount boundary for the bounded
 * transient retry and the blank-destination guard.
 */
@RunWith(RobolectricTestRunner::class)
class BackupWorkerTest {

    private lateinit var ctx: Context

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
    }

    private fun buildWorker(
        outcome: ScheduledOutcome?,
        destination: String? = BackupRepository.DESTINATION_DRIVE,
        runAttempt: Int = 0,
    ): BackupWorker {
        val data = if (destination != null) workDataOf(BackupWorker.KEY_DESTINATION to destination) else workDataOf()
        val worker = TestListenableWorkerBuilder<BackupWorker>(ctx)
            .setInputData(data)
            .setRunAttemptCount(runAttempt)
            .build()
        if (outcome != null) {
            worker.repositoryProvider = { FakeRepository(ctx, outcome) }
        }
        return worker
    }

    @Test
    fun success_mapsToSuccess() = runBlocking {
        val result = buildWorker(ScheduledOutcome.Success).doWork()
        assertEquals(ListenableWorker.Result.success(), result)
    }

    @Test
    fun needsReauth_mapsToTerminalFailure() = runBlocking {
        val result = buildWorker(ScheduledOutcome.NeedsReauth).doWork()
        assertEquals("NeedsReauth is terminal — no retry", ListenableWorker.Result.failure(), result)
    }

    @Test
    fun noNetwork_mapsToRetry() = runBlocking {
        val result = buildWorker(ScheduledOutcome.NoNetwork).doWork()
        assertEquals(ListenableWorker.Result.retry(), result)
    }

    @Test
    fun transient_belowMaxAttempts_retries() = runBlocking {
        val result = buildWorker(ScheduledOutcome.Transient, runAttempt = 0).doWork()
        assertEquals("transient retries while runAttemptCount < MAX_ATTEMPTS", ListenableWorker.Result.retry(), result)
    }

    @Test
    fun transient_atMaxAttempts_failsTerminally() = runBlocking {
        val result = buildWorker(ScheduledOutcome.Transient, runAttempt = BackupWorker.MAX_ATTEMPTS).doWork()
        assertEquals("transient gives up at MAX_ATTEMPTS", ListenableWorker.Result.failure(), result)
    }

    @Test
    fun blankDestination_mapsToFailure() = runBlocking {
        // No repo provider needed — the guard fires before repository resolution.
        val result = buildWorker(outcome = null, destination = null).doWork()
        assertEquals(ListenableWorker.Result.failure(), result)
    }

    // --- fakes ---

    /** A [BackupRepository] whose [runScheduledBackup] returns a fixed [ScheduledOutcome]. */
    private class FakeRepository(
        ctx: Context,
        private val outcome: ScheduledOutcome,
    ) : BackupRepository(
        ctx,
        FakeConfig(File(ctx.cacheDir, "worker-fake.db")),
        DatabaseFileManager(),
        NoopSource(),
        NoopSource(),
        BackupSettingsStore(InMemoryDataStore()),
    ) {
        override suspend fun runScheduledBackup(destination: String): ScheduledOutcome = outcome
    }

    private class FakeConfig(override val databaseFile: File) : BackupConfig {
        override val appName: String = "caltracker"
        override val currentSchemaVersion: Int = 5
        override val dataStore: DataStore<Preferences> = InMemoryDataStore()
    }

    private class NoopSource : BackupSource {
        override suspend fun put(file: File, name: String): BackupRef = error("unused")
        override suspend fun list(): List<BackupRef> = emptyList()
        override suspend fun get(ref: BackupRef): File = error("unused")
        override suspend fun delete(ref: BackupRef) {}
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

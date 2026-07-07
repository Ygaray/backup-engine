package io.github.ygaray.backupengine.startup

import android.content.Context
import androidx.startup.Initializer
import io.github.ygaray.backupengine.DatabaseFileManager
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * The cold-start, pre-Hilt, before-Room restore-swap hook (RST-03, resolves Open Q1).
 *
 * Registered under `androidx.startup`'s `InitializationProvider` (see the `:backup`
 * `AndroidManifest.xml`), so its [create] runs during content-provider installation — BEFORE
 * `Application.onCreate` and therefore before Room ever opens the live DB. That is the single moment
 * the database file is provably closed, which is exactly when a durable file swap is safe (RESEARCH
 * Pattern 3, step 6).
 *
 * **Pre-Hilt read (Open Q1 / prohibition):** the Initializer runs before Hilt/DataStore exist, so it
 * MUST NOT read the pending-restore state through [io.github.ygaray.backupengine.settings.BackupSettingsStore]
 * (DataStore). Instead it reads a PLAIN marker FILE sitting next to the live DB. All three working
 * paths — the staged candidate, the pre-swap safety copy, and the pending marker — are DETERMINISTIC
 * siblings of the live DB path, so the Initializer needs no injected [io.github.ygaray.backupengine.BackupConfig].
 *
 * **Idempotent (T-15-10):** a process death mid-swap re-runs safely on the next boot. [applySwap]
 * (reused from [DatabaseFileManager]) takes a safety-copy FIRST and rolls the live DB back from it on
 * ANY failure, so the live DB is never left corrupt; the marker is only cleared once the swap has
 * been attempted, and a re-run with a missing staged file simply rolls back again.
 *
 * @param databaseFileResolver resolves the live DB [File] from a [Context]. Defaults to
 *   `context.getDatabasePath("caltracker.db")` (production); overridable so the JVM test can point the
 *   deterministic sibling paths at a scratch DB without Room/Hilt.
 */
class RestoreSwapInitializer(
    private val databaseFileResolver: (Context) -> File = { it.getDatabasePath(DB_NAME) },
) : Initializer<Unit> {

    private val fileManager = DatabaseFileManager()

    override fun create(context: Context) {
        val liveDb = databaseFileResolver(context)
        val marker = markerFile(liveDb)

        // Pre-Hilt read: a plain marker file, NOT DataStore (the DataStore-backed store isn't up yet).
        if (!marker.exists()) return

        val staged = stagedFile(liveDb)
        val safety = safetyFile(liveDb)

        // Cold start => no coroutine scope exists yet; a blocking call is correct here because the
        // swap MUST finish before Room opens the DB in Application.onCreate. Reuse the engine's
        // safety-copy-first / roll-back-on-failure applySwap so a crash mid-move stays recoverable.
        runBlocking {
            fileManager.applySwap(target = liveDb, staged = staged, safetyCopy = safety)
        }

        // Clear the marker after the swap attempt. Even on a rolled-back failure this is safe: the
        // live DB is intact (restored from the safety-copy) and a stale marker would otherwise loop.
        marker.delete()
    }

    /** No dependencies: this must run first at cold start, before any other Initializer. */
    override fun dependencies(): List<Class<out Initializer<*>>> = emptyList()

    private fun stagedFile(liveDb: File) = File(liveDb.path + STAGED_SUFFIX)
    private fun safetyFile(liveDb: File) = File(liveDb.path + SAFETY_SUFFIX)
    private fun markerFile(liveDb: File) = File(liveDb.path + MARKER_SUFFIX)

    companion object {
        private const val DB_NAME = "caltracker.db"

        // Deterministic sibling suffixes derived from the live DB path (Open Q1). BackupRepository
        // (Plan 04) writes the staged file, safety copy, and marker at these exact paths before it
        // requests the restart, and this Initializer reads them back with no shared config object.
        const val STAGED_SUFFIX = ".staged"
        const val SAFETY_SUFFIX = ".safety"
        const val MARKER_SUFFIX = ".restore-pending"
    }
}

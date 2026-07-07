# backup-engine

A reusable, host-agnostic Android backup engine: snapshot / restore a live SQLite (Room)
database to local Storage Access Framework destinations and to Google Drive (`drive.file`
scope, hand-rolled OkHttp Drive REST — no Google Drive SDK), plus WorkManager-driven scheduled
backups. Extracted from [CalTracker](https://github.com/Ygaray) as a self-contained library.

The engine owns the backup/restore/scheduling logic and consumes the host application only
through a small set of interfaces (`BackupConfig`, `ScheduledBackupScheduler`) — it never
imports host code, holds no secrets, and creates no DataStore of its own.

**Docs for agents & integrators:**
- **[`INTEGRATION.md`](INTEGRATION.md)** — step-by-step checklist to adopt the engine in a new app.
- **[`API.md`](API.md)** — the full public surface (`BackupConfig`, `BackupRepository`, result types) and extension points.
- **[`CLAUDE.md`](CLAUDE.md)** — the reuse invariants and the (human-gated) tag → JitPack → repin flow.
- Reference wiring: [`CalTracker`](https://github.com/Ygaray/CalTracker_Android) is the first consumer.

## Install (JitPack)

Add the JitPack repository in your **`settings.gradle.kts`** (inside
`dependencyResolutionManagement { repositories { … } }`):

```kotlin
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
```

Then depend on it in your module's **`build.gradle.kts`**. Pin an immutable commit-SHA (or a
release tag once published) — never `main-SNAPSHOT`:

```kotlin
implementation("com.github.Ygaray:backup-engine:<commit-sha-or-tag>")
```

## Usage

The host wires the engine by implementing `BackupConfig` and binding it into Hilt's
`SingletonComponent`. `BackupConfig` hands the engine the live database file, a filename
prefix, and the app's existing `DataStore<Preferences>` (the engine reuses this store — it
never creates its own):

```kotlin
class MyBackupConfig @Inject constructor(
    @ApplicationContext private val context: Context,
    override val dataStore: DataStore<Preferences>,   // the host's EXISTING store — reused, not recreated
) : BackupConfig {
    override val appName: String = "myapp"                                 // filename prefix
    override val databaseFile: File = context.getDatabasePath("myapp.db")  // the live Room DB
    override val currentSchemaVersion: Int = 1                             // Room schema guard ceiling
    // Drive folder name, marker key, retention count, restartApp, and driveAccessToken() are all
    // DEFAULTED — override driveAccessToken() only if you support Drive backups. See API.md.
}
```

> Only **four** `BackupConfig` members are required (`appName`, `databaseFile`,
> `currentSchemaVersion`, `dataStore`). The full member table and every entry point is in
> **[`API.md`](API.md)**; the end-to-end wiring (Hilt bind, manifest Initializer, scheduling) is in
> **[`INTEGRATION.md`](INTEGRATION.md)**.

For scheduled backups, implement `ScheduledBackupScheduler` (a WorkManager-backed
implementation lives in the consuming app; the engine ships the `BackupWorker`
`CoroutineWorker` it schedules).

## Requirements

- Android `minSdk 26`
- Gradle 8.13 / AGP 8.13.0 / Kotlin 2.3.20 / JDK 17

## License

Apache License 2.0 — see [LICENSE](LICENSE).

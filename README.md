# backup-engine

A reusable, host-agnostic Android backup engine: snapshot / restore a live SQLite (Room)
database to local Storage Access Framework destinations and to Google Drive (`drive.file`
scope, hand-rolled OkHttp Drive REST — no Google Drive SDK), plus WorkManager-driven scheduled
backups. Extracted from [CalTracker](https://github.com/Ygaray) as a self-contained library.

The engine owns the backup/restore/scheduling logic and consumes the host application only
through a small set of interfaces (`BackupConfig`, `ScheduledBackupScheduler`) — it never
imports host code, holds no secrets, and creates no DataStore of its own.

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
    private val dataStore: DataStore<Preferences>,
) : BackupConfig {
    override val databaseFile: File get() = context.getDatabasePath("myapp.db")
    override val backupNamePrefix: String get() = "myapp"
    override val dataStore: DataStore<Preferences> get() = dataStore
    // …remaining BackupConfig members (Drive folder name, prefs keys, etc.)
}
```

For scheduled backups, implement `ScheduledBackupScheduler` (a WorkManager-backed
implementation lives in the consuming app; the engine ships the `BackupWorker`
`CoroutineWorker` it schedules).

## Requirements

- Android `minSdk 26`
- Gradle 8.13 / AGP 8.13.0 / Kotlin 2.3.20 / JDK 17

## License

Apache License 2.0 — see [LICENSE](LICENSE).

# INTEGRATION.md — adopt `backup-engine` in a new Android app

Operational checklist for wiring the engine into a **consumer app**. The public surface it references
is in **`API.md`**; the deeper reuse doctrine is in **`CLAUDE.md`**. The **worked reference** is
CalTracker — every step below points at the exact file that implements it in
`github.com/Ygaray/CalTracker_Android`.

**Prerequisites (the host must already have):**
- Hilt (`SingletonComponent`), a Room database, and a `DataStore<Preferences>` provided in the graph
- `minSdk 26`, JDK 17
- For Drive backups: a Google auth integration that can hand out a `drive.file` access token
  (Credential Manager + `AuthorizationClient`) — the engine never does auth itself

---

## 1. Add the JitPack repository

In **`settings.gradle.kts`** → `dependencyResolutionManagement { repositories { … } }`:

```kotlin
maven { url = uri("https://jitpack.io") }
```

## 2. Depend on the engine (pin an immutable tag)

In your app module's **`build.gradle.kts`**:

```kotlin
implementation("com.github.Ygaray:backup-engine:v1.0.0")   // immutable tag — never main-SNAPSHOT
```

## 3. Implement `BackupConfig` — the whole app-specific surface

Only **four members are required**; the rest default (see `API.md`). Reference:
`app/src/main/java/com/caltracker/app/backup/CalTrackerBackupConfig.kt`.

```kotlin
class MyBackupConfig @Inject constructor(
    @ApplicationContext private val context: Context,
    override val dataStore: DataStore<Preferences>,   // the host's EXISTING store — engine reuses it
    private val authManager: AuthManager,             // only if you support Drive
) : BackupConfig {
    override val appName: String = "myapp"                                   // filename prefix
    override val databaseFile: File = context.getDatabasePath("myapp.db")    // the live Room DB
    override val currentSchemaVersion: Int = 5                               // Room schema guard ceiling

    // Optional — override only for Drive support:
    override suspend fun driveAccessToken(): String? = authManager.currentDriveAccessToken()
    // driveFolderName / driveMarkerKey / retentionCount / restartApp all have sane defaults.
}
```

## 4. Bind it into Hilt

Reference: `app/src/main/java/com/caltracker/app/di/AppBackupModule.kt`.

```kotlin
@Module @InstallIn(SingletonComponent::class)
abstract class AppBackupModule {
    @Binds abstract fun bindBackupConfig(impl: MyBackupConfig): BackupConfig
    // + bindScheduledBackupScheduler if you do scheduling (step 6)
}
```

The engine's own `BackupModule` provides everything else (`DatabaseFileManager`, `BackupSettingsStore`,
local + Drive `BackupSource`, `DriveClient`, and the `@Inject`-constructed `BackupRepository`) from
`BackupConfig` **alone** — you do not wire those.

## 5. Register the cold-start restore swap (required for restore)

Restore is applied at **cold start**, before Room opens, by `RestoreSwapInitializer` (an
`androidx.startup` Initializer). Register it on the `androidx.startup` provider in your
**`AndroidManifest.xml`**. Reference: `app/src/main/AndroidManifest.xml`.

```xml
<provider
    android:name="androidx.startup.InitializationProvider"
    android:authorities="${applicationId}.androidx-startup"
    android:exported="false"
    tools:node="merge">
    <meta-data
        android:name="io.github.ygaray.backupengine.startup.RestoreSwapInitializer"
        android:value="androidx.startup" />
</provider>
```

## 6. (Optional) Scheduling — provide a `ScheduledBackupScheduler`

The engine ships the `BackupWorker` (`CoroutineWorker`) but **not** the WorkManager registration — the
host owns that so it controls its own WorkManager config. Implement `ScheduledBackupScheduler`
(`reconcileSchedules(local, drive)` + `cancelDriveBackups()`) and bind it. Reference:
`WorkManagerScheduledBackupScheduler.kt`. Because `BackupWorker` is Hilt-injected, make your
`Application` a `Configuration.Provider` and remove the default `WorkManagerInitializer` from the
manifest (see CalTracker's `CalTrackerApplication.kt` + manifest `tools:node="remove"`).

## 7. Call the engine from your UI layer

Inject `BackupRepository` into your ViewModel and call its suspend entry points (full list in
`API.md`):

```kotlin
val result: BackupResult = repo.backup()                 // local backup
val drive:  DriveBackupResult = repo.backupToDrive()     // Drive backup
val list:   List<BackupRef> = repo.listDriveBackups()
repo.restore(ref)                                        // local restore (restarts the app)
repo.restoreFromDrive(ref)
// On app launch, call repo.reconcilePendingRestore() once to reclaim any staged/safety files.
```

Every result is a sealed type — branch it exhaustively and map each failure `Reason` to your own UI
copy. The engine surfaces **no exception text and no token** across the seam.

---

## Notes & gotchas

- **The Drive token is provided, never stored.** The engine calls `BackupConfig.driveAccessToken()`
  per request; your auth layer owns re-obtaining it silently. Nothing token-shaped is ever persisted by
  the engine.
- **Restore restarts the app.** `restore()` stages + safety-copies, then relaunches; the swap lands at
  the next cold start. Design your UI for a "reopening…" interstitial.
- **Backups are plaintext `.db` files** (single-file `VACUUM INTO`, checkpoint+copy fallback). If your
  app needs encryption, that's a host concern layered on top — the engine does not encrypt.
- **Bumping to a new engine version** is human-gated (see `CLAUDE.md`): change the coordinate, sync,
  rebuild, re-verify on-device before shipping.

# API.md — `backup-engine` public surface & extension points

Everything a consumer touches, and everything you can extend. Package root:
`io.github.ygaray.backupengine`. To wire it, see `INTEGRATION.md`; for the reuse rules, `CLAUDE.md`.

## Surface at a glance

| Type | Kind | Who implements | Purpose |
|------|------|----------------|---------|
| `BackupConfig` | interface (seam) | **host** | the entire app-specific surface (4 required + 5 defaulted) |
| `ScheduledBackupScheduler` | interface (seam) | **host** (for scheduling) | register/cancel periodic WorkManager backups |
| `BackupRepository` | class (`@Inject`) | engine | the orchestrator — call its suspend entry points |
| `BackupSource` | interface | engine (Local + Drive) | a backup destination (`put`/`list`/`get`/`delete`) |
| `BackupWorker` | `CoroutineWorker` | engine | the scheduled-backup body the host's scheduler enqueues |
| `RestoreSwapInitializer` | `androidx.startup` Initializer | engine (host registers) | applies the restore swap at cold start |
| `BackupModule` | Hilt `@Module` | engine | provides everything from `BackupConfig` alone |
| `DatabaseFileManager` | class | engine | WAL-checkpoint / snapshot / integrity+schema guard / atomic swap |

## `BackupConfig` — the reuse surface

**Required (4):**

| Member | Type | Meaning |
|--------|------|---------|
| `appName` | `String` | filename prefix, e.g. `"caltracker"` (also the default for folder/marker/retention derivations) |
| `databaseFile` | `File` | the live Room DB the engine snapshots/restores |
| `currentSchemaVersion` | `Int` | schema guard ceiling — a backup whose `user_version` exceeds this is refused (`SchemaTooNew`) |
| `dataStore` | `DataStore<Preferences>` | the host's **existing** store — the engine reuses it, never creates its own |

**Defaulted (override only if needed):**

| Member | Default | Override when |
|--------|---------|---------------|
| `driveAccessToken()` | `null` (disconnected) | you support Drive — delegate to your auth layer |
| `restartApp(context)` | generic launcher relaunch + `exit(0)` | your app needs a custom relaunch |
| `driveFolderName` | `"$appName Backups"` | you want different Drive folder copy |
| `driveMarkerKey` | `"${appName}_backup_folder"` | rarely — the private folder-identity marker |
| `retentionCount` | `5` | you want a different keep-N (floor of 1 enforced in `prune`) |

## `BackupRepository` — the orchestrator (inject it)

| Method | Returns | Notes |
|--------|---------|-------|
| `backup()` | `BackupResult` | local snapshot → put → verify → record |
| `backupToDrive()` | `DriveBackupResult` | find-or-create folder → upload → md5+size verify |
| `listDriveBackups()` | `List<BackupRef>` | available Drive backups |
| `restore(ref)` | `BackupResult` | validate → safety-copy → stage → **restart** (swap at cold start) |
| `restoreFromDrive(ref)` | `BackupResult` | same safe path, Drive source |
| `runScheduledBackup(destination)` | `ScheduledOutcome` | the worker body (`"local"` / `"drive"`); also prunes |
| `prune(source, keepN, justWritten?)` | `Unit` | keep newest N; `require(keepN >= 1)` floor |
| `reconcilePendingRestore()` | `Unit` | call once on launch — clears the pending flag, reclaims `.safety`/`.staged` |

**Result types (all sealed — branch exhaustively, no `else`):**
- `BackupResult` = `Success` | `Failure(reason)` where `Reason ∈ {SchemaTooNew, NotAValidBackup, FolderUnavailable, WriteFailed}`
- `DriveBackupResult` = `Success` | `Failure(reason)` where `Reason ∈ {NeedsReauth, NoNetwork, VerifyFailed, Failed}`
- `ScheduledOutcome` = `Success` | `Transient` | `NeedsReauth` | `NoNetwork`
- `BackupRef(ref, name, timestampMillis, sizeBytes)` — an opaque handle to one backup
- `BackupFrequency` = `OFF` | `DAILY` | `WEEKLY`

## The safety model (what `restore` guarantees)

`DatabaseFileManager` makes restore non-destructive: **WAL-checkpoint before copy → integrity check →
`user_version` schema-version guard (refuse a newer-than-app backup) → pre-restore safety copy →
stage + durable pending flag → app restart → atomic swap applied at cold start by
`RestoreSwapInitializer` (before Room opens) → safety-copy rollback if the swap double-faults**. A
failed or refused restore leaves the live DB byte-intact. Backups are clean single-file `.db`
(`VACUUM INTO`, checkpoint+copy fallback on API < 30) — no `-wal`/`-shm`.

## Extension points

- **A new backup destination** — implement `BackupSource` (`put`/`list`/`get`/`delete`) and provide it
  behind a Hilt qualifier (see `LocalBackupSource`, `DriveBackupSource`, and `di/SourceQualifiers.kt`).
  The whole `BackupRepository` restore-safety path works over it unchanged.
- **Scheduling on your terms** — implement `ScheduledBackupScheduler`; the engine owns `BackupWorker`,
  you own the WorkManager registration/constraints (local unconstrained, Drive Wi-Fi-only in CalTracker).
- **Custom relaunch / retention / Drive copy** — override the defaulted `BackupConfig` members.

Adding a **new required** `BackupConfig` member, or changing a result type, is a **breaking change** for
every consumer — bump the major and coordinate the human-gated repin (see `CLAUDE.md`).

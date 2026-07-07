# CLAUDE.md — `backup-engine` (the reusable Android backup library)

You are working in a **reusable, host-agnostic Android library**: the backup/restore + Google Drive
+ WorkManager-scheduling engine that independent consumer apps (CalTracker, and future apps) import
via **JitPack**. This repo names **no app-specific concepts** — it is pure, reusable mechanism.

- **Import root:** `io.github.ygaray.backupengine`
- **JitPack coordinate:** `com.github.Ygaray:backup-engine:<tag>`
- **Public repo:** `github.com/Ygaray/backup-engine`
- **First consumer (reference wiring):** `github.com/Ygaray/CalTracker_Android`

**Read `API.md` for the public surface and `INTEGRATION.md` to wire a new app.** The essentials of
how this library relates to its consumers:

## The invariants (what keeps it reusable — do not break these)

- **One-way dependency.** The engine imports **no host code** (`com.caltracker.app`, any consumer
  package), holds **no secrets**, and stands up **no `DataStore` of its own** — it reuses the host's,
  handed in through `BackupConfig.dataStore`. This is exactly what makes it drop-in. Anything you add
  must keep this litmus clean: engine → (Android SDK, AndroidX, OkHttp, Hilt) only, never → a consumer.
- **The entire reuse surface is two small seams.** `BackupConfig` (4 required + 5 defaulted members —
  the whole app-specific surface) and `ScheduledBackupScheduler` (host provides a WorkManager impl for
  scheduling). A new host implements those and gets the full engine. Keep this surface minimal — every
  new required member is a breaking change for every consumer.
- **No app-specific literals.** Folder names, retention counts, prefs keys, schema versions all come
  from `BackupConfig` (defaults derive from `appName`). Never hardcode a CalTracker-ism.

## Changes here ripple to every consumer — and shipping is human-gated

A change in this repo is **not live in any consumer** until: **new tag → JitPack builds it → the
consumer bumps its coordinate** (`implementation("com.github.Ygaray:backup-engine:<newtag>")` →
Gradle sync → rebuild → re-verify on-device). That tag/bump/deploy step is **human-gated**: make the
fix + tests autonomously here, then **surface the tag + consumer-bump for confirmation** — do not tag
or bump a consumer without the owner's go-ahead.

- **Tags are immutable.** Consumers pin an immutable tag (or a commit-SHA); **never `main-SNAPSHOT`**
  and never a moving branch ref (supply-chain integrity).
- JitPack builds from GitHub, not any local clone — so this repo's directory location is irrelevant to
  consumers. Local edits don't reach a consumer until pushed + tagged + the coordinate is bumped.

## Toolchain

- Gradle **8.13** / AGP **8.13.0** / Kotlin **2.3.20** / JDK **17**, `minSdk 26`. Android **library**
  module (no `applicationId`, no Compose, no `versionName`).
- Build: `./gradlew :backup:assembleRelease`
- Unit tests: `./gradlew :backup:testDebugUnitTest` (JUnit4 + Robolectric + MockK + MockWebServer for
  the Drive REST surface)
- Publish locally (what JitPack runs, see `jitpack.yml`):
  `./gradlew :backup:publishReleasePublicationToMavenLocal`
- The module ships **no app**, **no UI**, **no `Application`** — it is imported and wired by each
  consumer's Hilt composition root, never run on its own.

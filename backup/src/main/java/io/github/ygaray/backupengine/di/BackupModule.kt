package io.github.ygaray.backupengine.di

import android.content.Context
import io.github.ygaray.backupengine.BackupConfig
import io.github.ygaray.backupengine.DatabaseFileManager
import io.github.ygaray.backupengine.drive.DriveClient
import io.github.ygaray.backupengine.settings.BackupSettingsStore
import io.github.ygaray.backupengine.source.BackupSource
import io.github.ygaray.backupengine.source.DriveBackupSource
import io.github.ygaray.backupengine.source.LocalBackupSource
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

/**
 * Hilt wiring for the isolated `:backup` engine (LIB-02). Mirrors the app's `DatabaseModule` idiom:
 * a `@Provides @Singleton object` module installed into [SingletonComponent].
 *
 * The engine's ENTIRE dependency on the host is the [BackupConfig] interface — this module consumes
 * only that (plus the Hilt-supplied application [Context]) and never references `com.caltracker.app`
 * (LIB-01, enforced by the one-directional build graph). The host binds the concrete [BackupConfig]
 * in Plan 05; every engine singleton below is derived from it, so dropping the engine into another app
 * needs nothing more than a new [BackupConfig] implementation.
 *
 * [BackupSettingsStore] is built from `config.dataStore` — the existing `caltracker_prefs` store —
 * so NO second DataStore instance is created (prohibition). [LocalBackupSource] is bound as the
 * v1.2 [BackupSource]; Phase 17 will add a Drive source behind the same interface.
 */
@Module
@InstallIn(SingletonComponent::class)
object BackupModule {

    @Provides
    @Singleton
    fun provideDatabaseFileManager(): DatabaseFileManager = DatabaseFileManager()

    @Provides
    @Singleton
    fun provideBackupSettingsStore(config: BackupConfig): BackupSettingsStore =
        BackupSettingsStore(config.dataStore)

    @Provides
    @Singleton
    fun provideBackupSource(
        @ApplicationContext context: Context,
        settings: BackupSettingsStore,
        config: BackupConfig,
    ): BackupSource = LocalBackupSource(context, settings, config)

    // ---- Phase 17: Drive networking + Drive source (LIB-01 — from BackupConfig alone) ----

    /**
     * ONE shared [OkHttpClient] for the whole engine (RESEARCH Pattern 2) — a client-per-call would
     * leak the connection pool/threads. This is the PRODUCTION-WIRED client (D-04, Pitfall 6): the
     * call/connect timeout reads [BackupConfig.driveCallTimeoutSeconds] (120s default) instead of a
     * hardcoded 30s, so a legitimate multi-hundred-MB media sidecar upload no longer aborts early
     * while a genuinely hung network still becomes a mapped [DriveClient] failure, not an indefinite
     * spinner.
     */
    @Provides
    @Singleton
    fun provideOkHttpClient(config: BackupConfig): OkHttpClient = OkHttpClient.Builder()
        .callTimeout(config.driveCallTimeoutSeconds, TimeUnit.SECONDS)
        .connectTimeout(config.driveCallTimeoutSeconds, TimeUnit.SECONDS)
        .build()

    /** The hand-rolled Drive REST client (Plan 01), built from the shared client + [BackupConfig]. */
    @Provides
    @Singleton
    fun provideDriveClient(
        @ApplicationContext context: Context,
        config: BackupConfig,
        okHttp: OkHttpClient,
    ): DriveClient = DriveClient(config = config, cacheDir = context.cacheDir, client = okHttp)

    /**
     * The Drive [BackupSource], bound with [DriveSource] so it is DISTINCT from the unqualified local
     * [provideBackupSource] binding — nothing else re-wires and the local path stays byte-identical.
     * The token lambda delegates to `config.driveAccessToken()` (LIB-01: the engine never imports the
     * auth vendor deps; it consumes only the token string the host provides).
     */
    @Provides
    @Singleton
    @DriveSource
    fun provideDriveBackupSource(
        client: DriveClient,
        config: BackupConfig,
    ): BackupSource = DriveBackupSource(
        client = client,
        tokenProvider = { config.driveAccessToken() },
        config = config,
    )

    // BackupRepository has an @Inject constructor and is derived entirely from the bindings above
    // (Context, BackupConfig, DatabaseFileManager, the unqualified local BackupSource, the @DriveSource
    // Drive BackupSource, BackupSettingsStore), so Hilt constructs it directly — no @Provides needed.
}

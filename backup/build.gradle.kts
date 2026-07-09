plugins {
    // Android LIBRARY (not application): no applicationId, no Compose, no versionName.
    // This is the isolated reusable :backup engine (LIB-01).
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    `maven-publish`
}

android {
    namespace = "io.github.ygaray.backupengine"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        // No targetSdk / applicationId — a library inherits the consuming app's targetSdk.

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    testOptions {
        // Robolectric needs merged Android resources on the unit-test classpath so the
        // API 26-29 checkpoint-fallback branch is JVM-testable off-device (downstream engine plans).
        unitTests.isIncludeAndroidResources = true
    }

    // AGP-8 opt-in: declare the single publishable variant + its sources jar. MANDATORY —
    // without this, `from(components["release"])` below fails ("'release' property cannot be
    // found"), the #1 JitPack-Android publish failure.
    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

dependencies {
    // SAF DocumentFile tree-URI I/O (LocalBackupSource, plan 03). findFile is O(children) —
    // list-once/cache downstream, never per-file (RESEARCH Pitfall 14).
    implementation(libs.documentfile)
    // Pre-Hilt cold-start Initializer for the restore swap (RestoreSwapInitializer, plan 04).
    implementation(libs.startup.runtime)

    // The existing prefs store is handed in via BackupConfig — no second DataStore instance is
    // created here (prohibition). `api` (not implementation): BackupConfig.dataStore is a public
    // `DataStore<Preferences>` member, so the consumer must reference the Preferences types.
    api(libs.datastore.prefs)

    // Hilt — :backup provides its own engine singletons into SingletonComponent (plan 04+).
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // Coroutines — engine I/O runs on Dispatchers.IO.
    implementation(libs.coroutines.android)

    // Phase 17 — the SOLE networking dependency (D-01/D-01a): a hand-rolled OkHttp DriveClient
    // speaks Drive REST v3 directly (NO google-api-services-drive SDK). No JSON codec is added —
    // org.json (SDK-bundled) builds/parses the tiny fixed-shape Drive JSON. OkHttp lives in the
    // engine that makes the calls, NOT in :app (LIB-01). okio arrives transitively.
    implementation(libs.okhttp)

    // Phase 18 — WorkManager (SCH-01/02/05): the OS-driven scheduler that wakes BackupWorker in the
    // background. The reusable engine owns the plain CoroutineWorker; the WorkManager scheduling impl
    // lives in the consumer app (D-08). Deliberately NO androidx.hilt:hilt-work (D-08a) — the worker
    // resolves its deps via EntryPointAccessors, so the engine stays free of the hilt-work dependency.
    // `api` (not implementation): BackupWorker (a CoroutineWorker) is public engine surface the
    // consumer's WorkManager scheduler references (RESEARCH §4 library-design call).
    api(libs.work.runtime.ktx)

    // Room runtime is intentionally NOT a dependency: :backup never opens the DB through Room;
    // it manipulates the raw file + uses android.database.sqlite for the user_version read.

    // JVM unit tests
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    // Robolectric — JVM host runner for the API 26-29 fallback branch (downstream engine plans).
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    // Phase 17 — MockWebServer drives the DriveClient REST suite entirely on the JVM (no device,
    // no real Drive/network): each test enqueues canned Drive JSON and points the client at its
    // local base URL. Same Square family as okhttp; test scope only.
    testImplementation(libs.okhttp.mockwebserver)
    // Phase 18 — WorkManager test harness so the JVM BackupWorkerTest can drive the worker via
    // TestListenableWorkerBuilder without a device (Result-mapping suite).
    testImplementation(libs.work.testing)

    // Instrumented tests — the on-device SAF round-trip (LocalBackupSourceTest, plan 03).
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.coroutines.test)
    androidTestImplementation(libs.work.testing)
}

// JitPack android-example publishing form. `from(components["release"])` MUST be inside
// afterEvaluate — the "release" software component does not exist at configuration time (it is
// created by the AGP singleVariant("release") opt-in above). groupId/version are what
// publishToMavenLocal writes; JitPack overrides groupId from the repo owner (com.github.Ygaray)
// and version from the resolved ref.
publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = "com.github.Ygaray"
            artifactId = "backup-engine"
            version = "1.1.0"
            afterEvaluate {
                from(components["release"])
            }
        }
    }
}

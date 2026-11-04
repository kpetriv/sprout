plugins {
    alias(libs.plugins.sprout.android.application)
    alias(libs.plugins.sprout.hilt)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.baselineprofile)
}

// A real Firebase project's config (never committed, D-M3-1). Without it, debug builds initialise Firebase
// programmatically for the `demo-sprout` emulator project (core/sync-firebase FirebaseBootstrap).
if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
    // Debug builds always run against the Emulator Suite's `demo-sprout` project, never the real one: without the
    // generated values FirebaseBootstrap initialises the demo project programmatically.
    // A disabled task leaves an earlier output behind, so that is deleted before debug resources are merged.
    val dropDebugGoogleServices = tasks.register<Delete>("dropDebugGoogleServices") {
        delete(layout.buildDirectory.dir("generated/res/processDebugGoogleServices"))
    }
    tasks.configureEach {
        if (name == "processDebugGoogleServices") enabled = false
        if (name.endsWith("DebugResources") && name != "dropDebugGoogleServices") dependsOn(dropDebugGoogleServices)
    }
}

/** Host of the Firebase Emulator Suite as seen from the device: `10.0.2.2` is the Android emulator's host loopback. */
val firebaseEmulatorHost: String = providers.gradleProperty("sprout.firebaseEmulatorHost").orElse("10.0.2.2").get()

/**
 * Base URL of the household callables (`server/`, D-M3-29) as seen from the device. Debug default: the host's
 * port 8787 through the Android emulator's loopback alias; release: only when the property is set.
 */
val functionsBaseUrl: String? = providers.gradleProperty("sprout.functionsBaseUrl").orNull

/** OAuth web client id Google ID tokens are issued for; only exists with a real Firebase project (D-M3-2). */
val googleServerClientId: String? = providers.gradleProperty("sprout.googleServerClientId").orNull

/**
 * Release builds must be shippable (audit Low 5): an https callable base URL (`sprout.functionsBaseUrl`; release never
 * falls back to Cloud Functions) and `app/google-services.json`. `bundleRelease` (the Play artifact) fails without
 * them unless `-Psprout.allowUnconfiguredRelease=true`; `assembleRelease` (part of `build`, never shipped) does not.
 */
val allowUnconfiguredRelease: Boolean =
    providers.gradleProperty("sprout.allowUnconfiguredRelease").map(String::toBoolean).orElse(false).get()
val releaseConfigurationProblems: List<String> = buildList {
    if (functionsBaseUrl?.startsWith("https://") != true) {
        add("sprout.functionsBaseUrl must be set to the server's https:// base URL")
    }
    if (!file("google-services.json").exists()) add("app/google-services.json is missing (docs/FIREBASE.md)")
}
val checkReleaseConfiguration = tasks.register("checkReleaseConfiguration") {
    description = "Fails a release build that is not configured for the real backend."
    val problems = releaseConfigurationProblems
    val allowed = allowUnconfiguredRelease
    doLast {
        if (problems.isNotEmpty() && !allowed) {
            throw GradleException(
                "Release build is not configured: " + problems.joinToString("; ") +
                    ". Pass -Psprout.allowUnconfiguredRelease=true only for builds that are never shipped.",
            )
        }
    }
}
tasks.configureEach {
    if (name == "bundleRelease") dependsOn(checkReleaseConfiguration)
}

fun String?.asBuildConfigString(): String = this?.let { "\"" + it.replace("\"", "") + "\"" } ?: "null"

android {
    namespace = "dev.kpetriv.sprout"

    defaultConfig {
        applicationId = "dev.kpetriv.sprout"
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("String", "GOOGLE_SERVER_CLIENT_ID", googleServerClientId.asBuildConfigString())
    }

    // BuildConfig.DEBUG gates the TEMPORARY demo-household seeding (HouseholdBootstrap) to debug builds; the Firebase
    // fields below configure core/sync-firebase (FirebaseModule).
    buildFeatures {
        buildConfig = true
    }

    // Robolectric (PlanScheduledActionsWorkerTest) reads the merged resources.
    testOptions.unitTests.isIncludeAndroidResources = true

    buildTypes {
        debug {
            // Debug builds talk to the local Firebase Emulator Suite only (cleartext allowed to it, src/debug).
            buildConfigField("String", "FIREBASE_EMULATOR_HOST", firebaseEmulatorHost.asBuildConfigString())
            buildConfigField(
                "String",
                "FUNCTIONS_BASE_URL",
                (functionsBaseUrl ?: "http://$firebaseEmulatorHost:8787").asBuildConfigString(),
            )
        }
        release {
            buildConfigField("String", "FIREBASE_EMULATOR_HOST", "null")
            buildConfigField("String", "FUNCTIONS_BASE_URL", functionsBaseUrl.asBuildConfigString())
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
}

baselineProfile {
    // Profiles are generated on demand (./gradlew :app:generateBaselineProfile), not on every build.
    automaticGenerationDuringBuild = false
}

dependencies {
    // AiGateway (the AiRouter) and AiPreferences behind the M6 feature ports (chat, chores, dashboard, wallet, settings).
    implementation(projects.core.ai)
    implementation(projects.core.common)
    // ParentAuthorizer, ParentAuthUnavailableException and verifyParentSignature for the settings adapters.
    implementation(projects.core.crypto)
    implementation(projects.core.database)
    implementation(projects.core.designsystem)
    // ParentAuthorizationRequester (bound by feature/lock) for the parent dashboard's "Sign now".
    implementation(projects.core.domainLedger)
    // GameKind in the games nav keys; GameId and Score for the games adapters (M5).
    implementation(projects.core.games)
    implementation(projects.core.model)
    // SyncEngine and its ports, run by SyncController and SyncWorker.
    implementation(projects.core.sync)
    // FirebaseBootstrap, auth session, household callables and the Firestore transport (the only Firebase importer).
    implementation(projects.core.syncFirebase)
    implementation(projects.core.time)
    implementation(projects.core.ui)

    implementation(projects.feature.chat)
    implementation(projects.feature.chores)
    implementation(projects.feature.games)
    implementation(projects.feature.lock)
    implementation(projects.feature.onboarding)
    implementation(projects.feature.parentHome)
    implementation(projects.feature.settings)
    implementation(projects.feature.timebank)
    implementation(projects.feature.wallet)
    implementation(projects.widget)

    implementation(libs.androidx.activity.compose)
    // Window theme parent (Theme.AppCompat.DayNight): BiometricPrompt's fingerprint dialog fallback needs it.
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    // ProcessLifecycleOwner: the @AppForeground signal for the Firestore listeners and the sync trigger.
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.profileinstaller)
    // Room's invalidation tracker on the outbox table is the sync trigger (OutboxSyncTrigger).
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)
    implementation(libs.kotlinx.serialization.core)
    // A DebugTree in debug builds only (D-M6-18); release plants no tree, so nothing is logged there.
    implementation(libs.timber)
    // Callable payloads (device proofs, the export bundle).
    implementation(libs.kotlinx.serialization.json)

    testImplementation(testFixtures(projects.core.crypto))
    // TestHousehold: a real Room household for the local rows clear (chat tables and blob cache, M4).
    testImplementation(testFixtures(projects.core.database))
    testImplementation(testFixtures(projects.core.sync))
    testImplementation(testFixtures(projects.core.time))
    testImplementation(libs.androidx.work.testing)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.turbine)

    baselineProfile(projects.benchmark)
}

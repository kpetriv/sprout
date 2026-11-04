import com.android.build.api.artifact.SingleArtifact
import com.github.triplet.gradle.androidpublisher.ReleaseStatus
import java.net.URI
import javax.xml.parsers.DocumentBuilderFactory

plugins {
    alias(libs.plugins.sprout.android.application)
    alias(libs.plugins.sprout.hilt)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.baselineprofile)
    // Robolectric, Compose UI test and Roborazzi for the shell's goldens (M7 §7) and SecureAreaTest.
    alias(libs.plugins.sprout.android.screenshot)
    // The release workflow's optional upload to the internal track (D-M8-5); off without Play credentials (below).
    alias(libs.plugins.play.publisher)
}

/**
 * The benchmark build types (D-M7-3, D-M7-41). They carry the names the Baseline Profile plugin derives from `release`
 * (`nonMinifiedRelease` generates the profile, `benchmarkRelease` is measured), declared here so the plugin keeps them
 * and they are benchmark builds rather than plain release copies: `BuildConfig.BENCHMARK`, the demo household seeded,
 * a placeholder backend, no Firebase (so no sync and no App Check), debug signing.
 */
val benchmarkBuildTypes = listOf("nonMinifiedRelease", "benchmarkRelease")

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
    // The benchmark build types (below) never talk to any Firebase project either (D-M7-3).
    benchmarkBuildTypes.forEach { buildType ->
        val variant = buildType.replaceFirstChar(Char::titlecase)
        val drop = tasks.register<Delete>("drop${variant}GoogleServices") {
            delete(layout.buildDirectory.dir("generated/res/process${variant}GoogleServices"))
        }
        tasks.configureEach {
            if (name == "process${variant}GoogleServices") enabled = false
            if (name.endsWith("${variant}Resources") && !name.startsWith("drop")) dependsOn(drop)
        }
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
 * The hosted privacy policy (About → "Privacy policy", M8 §1): `sprout.privacyUrl`, by default the Sprout server's
 * `/privacy` page (`<sprout.functionsBaseUrl>/privacy`, D-M8-1). Debug builds fall back to the local server's.
 */
val privacyUrl: String? = providers.gradleProperty("sprout.privacyUrl").orNull
    ?: functionsBaseUrl?.let { it.trimEnd('/') + "/privacy" }

/**
 * The disclosure policy (About → "Security and disclosure"): `sprout.securityUrl`, by default the `/security` page on
 * the privacy page's host (the Sprout server serves it next to `/privacy` and `/.well-known/security.txt`).
 */
val securityUrl: String? = providers.gradleProperty("sprout.securityUrl").orNull
    ?: privacyUrl?.let { url -> runCatching { URI(url) }.getOrNull()?.let { "${it.scheme}://${it.rawAuthority}" } }
        ?.let { "$it/security" }

/**
 * The upload key that signs release bundles (D-M8-3). Each value comes from its environment variable, else from its
 * Gradle property (for example in `~/.gradle/gradle.properties`); nothing is committed. The keystore path may be
 * absolute, start with `~/`, or be relative to the repository root. The owner creates the keystore with
 * `tools/release/create-upload-keystore.sh`; the release workflow writes it from a secret to the runner.
 */
val uploadSigningVariables = listOf(
    "SPROUT_UPLOAD_KEYSTORE" to "sprout.upload.keystore",
    "SPROUT_UPLOAD_KEYSTORE_PASSWORD" to "sprout.upload.keystorePassword",
    "SPROUT_UPLOAD_KEY_ALIAS" to "sprout.upload.keyAlias",
    "SPROUT_UPLOAD_KEY_PASSWORD" to "sprout.upload.keyPassword",
)
val uploadSigning: Map<String, String?> = uploadSigningVariables.associate { (variable, property) ->
    variable to (providers.environmentVariable(variable).orNull ?: providers.gradleProperty(property).orNull)
        ?.takeIf(String::isNotBlank)
}
val uploadKeystore: File? = uploadSigning["SPROUT_UPLOAD_KEYSTORE"]?.let { path ->
    if (path.startsWith("~/")) File(System.getProperty("user.home"), path.removePrefix("~/")) else rootDir.resolve(path)
}
val uploadSigningReady: Boolean = uploadSigning.values.all { it != null } && uploadKeystore?.isFile == true

/**
 * Release builds must be shippable (audit Low 5, D-M8-3): an https callable base URL (`sprout.functionsBaseUrl`;
 * release never falls back to Cloud Functions), `app/google-services.json`, the OAuth web client id, https privacy and
 * security URLs, and the upload key. `bundleRelease` (the Play artifact) fails without them unless
 * `-Psprout.allowUnconfiguredRelease=true` (CI, never shipped); `assembleRelease` (part of `build`) does not check.
 * Problems name the missing variable or property, never a value.
 */
val allowUnconfiguredRelease: Boolean =
    providers.gradleProperty("sprout.allowUnconfiguredRelease").map(String::toBoolean).orElse(false).get()
val releaseConfigurationProblems: List<String> = buildList {
    if (functionsBaseUrl?.startsWith("https://") != true) {
        add("sprout.functionsBaseUrl must be set to the server's https:// base URL")
    }
    if (!file("google-services.json").exists()) add("app/google-services.json is missing (docs/FIREBASE.md)")
    if (googleServerClientId.isNullOrBlank()) {
        add("sprout.googleServerClientId must be set to the OAuth web client id (docs/FIREBASE.md)")
    }
    if (privacyUrl?.startsWith("https://") != true) {
        add("sprout.privacyUrl must be an https:// URL (default: <sprout.functionsBaseUrl>/privacy)")
    }
    if (securityUrl?.startsWith("https://") != true) {
        add("sprout.securityUrl must be an https:// URL (default: /security on the privacy page's host)")
    }
    uploadSigningVariables.forEach { (variable, property) ->
        if (uploadSigning[variable] == null) add("$variable (or -P$property) is not set")
    }
    if (uploadSigning["SPROUT_UPLOAD_KEYSTORE"] != null && uploadKeystore?.isFile != true) {
        add("SPROUT_UPLOAD_KEYSTORE does not name an existing keystore file")
    }
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
    // A local Play bundle runs the release checks too, not only CI's `check` (audit 2 I-a).
    // checkReleaseMapping and checkReleaseAppCheck are wired by the application convention plugin (ReleaseChecks.kt).
    if (name == "bundleRelease") dependsOn(checkReleaseConfiguration, "checkMergedManifestForTelemetryRelease")
}

/**
 * D-M6-33 (audit H1): ML Kit pulls in Google's usage-metrics uploader (`transport-backend-cct`), whose only network
 * backend is registered as a `backend:...CctBackendFactory` meta-data of `TransportBackendDiscovery`. The app manifest
 * removes it; this fails the build if a merged manifest still registers it, or any other datatransport backend (any
 * `backend:` meta-data under `TransportBackendDiscovery`, or a `backend:com.google.android.datatransport` one anywhere;
 * audit 2 I-a), for example after a dependency update or a new SDK. Only attributes are read, so the manifest's
 * comments do not count. CI runs it through `check`, and `bundleRelease` depends on the release one.
 */
abstract class CheckMergedManifestForTelemetry : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val mergedManifest: RegularFileProperty

    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun verify() {
        val factory = DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }
        val document = factory.newDocumentBuilder().parse(mergedManifest.get().asFile)
        val elements = document.getElementsByTagName("*")
        val datatransportBackend = "backend:com.google.android.datatransport"
        fun values(node: org.w3c.dom.Node?): List<String> = node?.attributes?.let { attributes ->
            (0 until attributes.length).map { attributes.item(it).nodeValue }
        }.orEmpty()
        val found = (0 until elements.length).flatMap { index ->
            val element = elements.item(index)
            val isMetaData = element.nodeName == "meta-data"
            val inDiscovery = isMetaData && values(element.parentNode).any { "TransportBackendDiscovery" in it }
            values(element).filter { value ->
                "CctBackendFactory" in value ||
                    (isMetaData && value.startsWith(datatransportBackend)) ||
                    (inDiscovery && value.startsWith("backend:"))
            }
        }
        if (found.isNotEmpty()) {
            throw GradleException(
                "The merged manifest registers a Google datatransport uploader backend (D-M6-33): " +
                    found.distinct().joinToString() + ". Remove it in app/src/main/AndroidManifest.xml.",
            )
        }
        report.get().asFile.writeText("No datatransport backend in the merged manifest.\n")
    }
}

androidComponents {
    onVariants { variant ->
        if (variant.name == "debug" || variant.name == "release") {
            tasks.register<CheckMergedManifestForTelemetry>(
                "checkMergedManifestForTelemetry" + variant.name.replaceFirstChar(Char::titlecase),
            ) {
                description = "Fails if the ${variant.name} merged manifest registers ML Kit's metrics uploader."
                mergedManifest.set(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
                report.set(layout.buildDirectory.file("reports/telemetry-manifest/${variant.name}.txt"))
            }
        }
    }
}
val checkMergedManifestForTelemetry = tasks.register("checkMergedManifestForTelemetry") {
    group = "verification"
    description = "Fails if a debug or release merged manifest registers ML Kit's metrics uploader (D-M6-33)."
    dependsOn("checkMergedManifestForTelemetryDebug", "checkMergedManifestForTelemetryRelease")
}
tasks.named("check") { dependsOn(checkMergedManifestForTelemetry) }

fun String?.asBuildConfigString(): String = this?.let { "\"" + it.replace("\"", "") + "\"" } ?: "null"

android {
    namespace = "dev.kpetriv.sprout"

    defaultConfig {
        applicationId = "dev.kpetriv.sprout"
        // versionName and versionCode come from git (build-logic Versioning.kt, D-M8-4).
        buildConfigField("String", "GOOGLE_SERVER_CLIENT_ID", googleServerClientId.asBuildConfigString())
        // True only in the benchmark build types (D-M7-3): BenchmarkSeed reads it.
        buildConfigField("boolean", "BENCHMARK", "false")
    }

    // BuildConfig.DEBUG gates the TEMPORARY demo-household seeding (HouseholdBootstrap) to debug builds; the Firebase
    // fields below configure core/sync-firebase (FirebaseModule).
    buildFeatures {
        buildConfig = true
    }

    // Robolectric (PlanScheduledActionsWorkerTest) reads the merged resources.
    testOptions.unitTests.isIncludeAndroidResources = true

    // Only when every upload-key value is present (D-M8-3); otherwise release bundles are unsigned and
    // checkReleaseConfiguration stops bundleRelease.
    if (uploadSigningReady) {
        signingConfigs.create("release") {
            storeFile = uploadKeystore
            storePassword = uploadSigning["SPROUT_UPLOAD_KEYSTORE_PASSWORD"]
            keyAlias = uploadSigning["SPROUT_UPLOAD_KEY_ALIAS"]
            keyPassword = uploadSigning["SPROUT_UPLOAD_KEY_PASSWORD"]
        }
    }

    buildTypes {
        debug {
            // Debug builds talk to the local Firebase Emulator Suite only (cleartext allowed to it, src/debug).
            buildConfigField("String", "FIREBASE_EMULATOR_HOST", firebaseEmulatorHost.asBuildConfigString())
            buildConfigField(
                "String",
                "FUNCTIONS_BASE_URL",
                (functionsBaseUrl ?: "http://$firebaseEmulatorHost:8787").asBuildConfigString(),
            )
            val localServer = "http://$firebaseEmulatorHost:8787"
            buildConfigField("String", "PRIVACY_URL", (privacyUrl ?: "$localServer/privacy").asBuildConfigString())
            buildConfigField(
                "String",
                "SECURITY_URL",
                (securityUrl ?: "$localServer/security").asBuildConfigString(),
            )
        }
        release {
            buildConfigField("String", "FIREBASE_EMULATOR_HOST", "null")
            buildConfigField("String", "FUNCTIONS_BASE_URL", functionsBaseUrl.asBuildConfigString())
            buildConfigField("String", "PRIVACY_URL", privacyUrl.asBuildConfigString())
            buildConfigField("String", "SECURITY_URL", securityUrl.asBuildConfigString())
            if (uploadSigningReady) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        // Release code and R8 setup (initWith), debug-signed and profileable; the plugin turns R8 off for
        // nonMinifiedRelease. The functions URL is a placeholder that passes FirebaseModule's https check: nothing
        // calls it, since the benchmark build never initialises Firebase and the trial household never syncs.
        // checkReleaseConfiguration only guards bundleRelease and is unaffected.
        benchmarkBuildTypes.forEach { name ->
            create(name) {
                initWith(getByName("release"))
                isDebuggable = false
                isProfileable = true
                signingConfig = signingConfigs.getByName("debug")
                matchingFallbacks += "release"
                buildConfigField("boolean", "BENCHMARK", "true")
                buildConfigField("String", "FUNCTIONS_BASE_URL", "\"https://benchmark.invalid\"")
            }
        }
    }
}

// Gradle Play Publisher (D-M8-5): only the release workflow uses it, to upload the bundle it built to the internal
// track as a draft (`publishReleaseBundle --artifact-dir`). Its tasks exist only when the Play service account's JSON
// is in `ANDROID_PUBLISHER_CREDENTIALS`. Play takes the first upload of a new app only through the console.
play {
    enabled.set(providers.environmentVariable("ANDROID_PUBLISHER_CREDENTIALS").map(String::isNotBlank).orElse(false))
    track.set("internal")
    defaultToAppBundles.set(true)
    releaseStatus.set(ReleaseStatus.DRAFT)
}

baselineProfile {
    // Profiles are generated on demand on an emulator (README "Performance"), not on every build, and committed under
    // src/main/generated/baselineProfiles so every variant, release included, ships them.
    automaticGenerationDuringBuild = false
    mergeIntoMain = true
    // startup-prof.txt drives R8's dex layout: startup classes go into the primary dex.
    dexLayoutOptimization = true
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
    // ListDetailSceneStrategy: chat and chores list-detail on an Expanded window (M7 §5, D-M7-2).
    implementation(libs.androidx.compose.material3.adaptive.navigation3)
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

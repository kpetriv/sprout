plugins {
    alias(libs.plugins.sprout.android.application)
    alias(libs.plugins.sprout.hilt)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.baselineprofile)
}

android {
    namespace = "dev.kpetriv.sprout"

    defaultConfig {
        applicationId = "dev.kpetriv.sprout"
        versionCode = 1
        versionName = "0.1.0"
    }

    // BuildConfig.DEBUG gates the TEMPORARY demo-household seeding (HouseholdBootstrap) to debug builds.
    buildFeatures {
        buildConfig = true
    }

    // Robolectric (PlanScheduledActionsWorkerTest) reads the merged resources.
    testOptions.unitTests.isIncludeAndroidResources = true

    buildTypes {
        release {
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
    implementation(projects.core.common)
    // ParentAuthorizer, ParentAuthUnavailableException and verifyParentSignature for the settings adapters.
    implementation(projects.core.crypto)
    implementation(projects.core.database)
    implementation(projects.core.designsystem)
    // ParentAuthorizationRequester (bound by feature/lock) for the parent dashboard's "Sign now".
    implementation(projects.core.domainLedger)
    implementation(projects.core.model)
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
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.profileinstaller)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)
    implementation(libs.kotlinx.serialization.core)

    testImplementation(testFixtures(projects.core.crypto))
    testImplementation(testFixtures(projects.core.time))
    testImplementation(libs.androidx.work.testing)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.turbine)

    baselineProfile(projects.benchmark)
}

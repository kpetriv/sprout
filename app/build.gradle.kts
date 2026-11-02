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
    implementation(projects.core.designsystem)
    implementation(projects.core.model)
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
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.profileinstaller)
    implementation(libs.kotlinx.serialization.core)

    baselineProfile(projects.benchmark)
}

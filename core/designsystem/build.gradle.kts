plugins {
    alias(libs.plugins.sprout.android.library.compose)
    // Component goldens (M9 §3). :core:testing depends on this module's main code; that is fine at the task level.
    alias(libs.plugins.sprout.android.screenshot)
}

android {
    namespace = "dev.kpetriv.sprout.core.designsystem"
}

dependencies {
    api(projects.core.model)
    api(libs.androidx.compose.foundation)
    api(libs.androidx.compose.material3)
    api(libs.androidx.compose.runtime)
    api(libs.androidx.compose.ui)
}

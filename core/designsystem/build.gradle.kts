plugins {
    alias(libs.plugins.sprout.android.library.compose)
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

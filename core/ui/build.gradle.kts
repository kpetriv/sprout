plugins {
    alias(libs.plugins.sprout.android.library.compose)
}

android {
    namespace = "dev.kpetriv.sprout.core.ui"
}

dependencies {
    api(projects.core.designsystem)
}

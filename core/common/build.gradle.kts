plugins {
    alias(libs.plugins.sprout.android.library)
    alias(libs.plugins.sprout.hilt)
}

android {
    namespace = "dev.kpetriv.sprout.core.common"
}

dependencies {
    api(projects.core.time)
    api(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
}

plugins {
    alias(libs.plugins.sprout.android.library)
}

android {
    namespace = "dev.kpetriv.sprout.core.sync"
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
}

plugins {
    alias(libs.plugins.sprout.android.library)
}

android {
    namespace = "dev.kpetriv.sprout.core.syncfirebase"
}

dependencies {
    implementation(projects.core.sync)
    // Firebase (libs.firebase.*) is added in M3 with the google-services plugin.
}

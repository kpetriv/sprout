plugins {
    alias(libs.plugins.sprout.android.library.compose)
}

android {
    namespace = "dev.kpetriv.sprout.widget"
}

dependencies {
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)
}

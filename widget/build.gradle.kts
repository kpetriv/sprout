plugins {
    alias(libs.plugins.sprout.android.library.compose)
    // Robolectric and Roborazzi for the widget golden (rendered through Glance's RemoteViews in a Compose host).
    alias(libs.plugins.sprout.android.screenshot)
}

android {
    namespace = "dev.kpetriv.sprout.widget"
}

dependencies {
    // api: the app's receiver and refresher use GlanceAppWidgetReceiver, GlanceAppWidgetManager and updateAll.
    api(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)
    // lightColorScheme/darkColorScheme for the widget's Material 3 colours (WidgetColors.kt).
    implementation(libs.androidx.compose.material3)

    // Glance's unit-test harness (runGlanceAppWidgetUnitTest) for the three states. Same version as glance-appwidget;
    // referenced by coordinates because the catalog is not this slice's to edit.
    testImplementation("androidx.glance:glance-appwidget-testing:${libs.versions.androidxGlance.get()}")
    testImplementation(libs.kotlinx.coroutines.test)
}

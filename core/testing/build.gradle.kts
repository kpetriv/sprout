// Android test helpers (Android fakes, screenshot infra). Pure-JVM fakes such as FakeClock live in the
// owning module's test fixtures instead, e.g. testImplementation(testFixtures(projects.core.time)).
plugins {
    alias(libs.plugins.sprout.android.library)
}

android {
    namespace = "dev.kpetriv.sprout.core.testing"
}

dependencies {
    api(projects.core.common)
    api(libs.junit4)
    api(libs.kotlin.test.junit)
    api(libs.kotlinx.coroutines.test)
}

plugins {
    alias(libs.plugins.sprout.android.test)
    alias(libs.plugins.baselineprofile)
}

android {
    namespace = "dev.kpetriv.sprout.benchmark"
    targetProjectPath = ":app"
    // Macrobenchmark drives the target app from a separate process.
    experimentalProperties["android.experimental.self-instrumenting"] = true
}

baselineProfile {
    useConnectedDevices = true
}

dependencies {
    implementation(libs.androidx.benchmark.macro.junit4)
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.test.runner)
    implementation(libs.androidx.test.uiautomator)
}

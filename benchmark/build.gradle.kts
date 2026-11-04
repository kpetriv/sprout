import com.android.build.api.dsl.ManagedVirtualDevice

plugins {
    alias(libs.plugins.sprout.android.test)
    alias(libs.plugins.baselineprofile)
}

/** The Gradle Managed Device of the manual benchmark workflow (task `pixel6Api33BenchmarkReleaseAndroidTest`). */
val managedDevice = "pixel6Api33"

android {
    namespace = "dev.kpetriv.sprout.benchmark"
    targetProjectPath = ":app"
    // Macrobenchmark drives the target app from a separate process.
    experimentalProperties["android.experimental.self-instrumenting"] = true

    // Only for the optional manual workflow (.github/workflows/benchmark.yml, D-M7-3): a Gradle Managed Device, so a
    // runner without a connected device can run the benchmarks. Local runs use a connected emulator instead.
    testOptions.managedDevices.allDevices {
        create<ManagedVirtualDevice>(managedDevice) {
            device = "Pixel 6"
            apiLevel = 33
            systemImageSource = "aosp"
        }
    }
}

baselineProfile {
    // Generated on a connected emulator (README "Performance"); the managed device is never used for generation.
    useConnectedDevices = true
}

dependencies {
    implementation(libs.androidx.benchmark.macro.junit4)
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.test.runner)
    implementation(libs.androidx.test.uiautomator)
}

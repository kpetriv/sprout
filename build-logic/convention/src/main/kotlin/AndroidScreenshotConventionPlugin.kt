import com.android.build.api.dsl.CommonExtension
import dev.kpetriv.sprout.buildlogic.libs
import dev.kpetriv.sprout.buildlogic.library
import dev.kpetriv.sprout.buildlogic.pluginId
import io.github.takahirom.roborazzi.RoborazziExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

/**
 * Roborazzi screenshot tests on Robolectric for a Compose module. Apply after `sprout.android.library.compose`
 * or `sprout.android.feature`. Goldens live in `src/test/screenshots` and are committed.
 *
 * - `./gradlew :module:recordRoborazziDebug` re-records the goldens.
 * - `./gradlew :module:verifyRoborazziDebug` compares against them and fails on any diff (CI runs this);
 *   diffs land in `build/outputs/roborazzi`.
 * - Plain `test` runs the screenshot tests as ordinary tests without capturing or comparing anything. When `test`
 *   and a Roborazzi task are in one invocation, the unit-test task still runs once, in the Roborazzi mode.
 *
 * Determinism rules (Robolectric SDK, device, locale, time zone, animations) live in `:core:testing`'s
 * `ScreenshotTest`; see docs/SCREENSHOT_TESTS.md.
 */
class AndroidScreenshotConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply(libs.pluginId("roborazzi"))

        extensions.configure<CommonExtension> {
            testOptions.unitTests.isIncludeAndroidResources = true
            testOptions.unitTests.isReturnDefaultValues = true
        }

        extensions.configure<RoborazziExtension> {
            outputDir.set(layout.projectDirectory.dir("src/test/screenshots"))
        }

        dependencies {
            add("testImplementation", project(":core:testing"))
            add("testImplementation", libs.library("robolectric"))
            add("testImplementation", libs.library("roborazzi"))
            add("testImplementation", libs.library("roborazzi-compose"))
            add("testImplementation", libs.library("roborazzi-junit-rule"))
            add("testImplementation", platform(libs.library("androidx-compose-bom")))
            add("testImplementation", libs.library("androidx-compose-ui-test-junit4"))
            // Registers the empty ComponentActivity that createComposeRule launches.
            add("debugImplementation", libs.library("androidx-compose-ui-test-manifest"))
        }
    }
}

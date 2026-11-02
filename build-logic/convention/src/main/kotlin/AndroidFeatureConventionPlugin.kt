import dev.kpetriv.sprout.buildlogic.libs
import dev.kpetriv.sprout.buildlogic.library
import dev.kpetriv.sprout.buildlogic.pluginId
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

/**
 * A screen-level feature module: Compose + Hilt + the core modules every feature uses.
 * Feature modules never depend on each other; the app wires them together.
 */
class AndroidFeatureConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply(libs.pluginId("sprout-android-library-compose"))
        pluginManager.apply(libs.pluginId("sprout-hilt"))

        dependencies {
            add("implementation", project(":core:common"))
            add("implementation", project(":core:designsystem"))
            add("implementation", project(":core:model"))
            add("implementation", project(":core:ui"))

            add("implementation", libs.library("androidx-compose-material3"))
            add("implementation", libs.library("androidx-lifecycle-runtime-compose"))
            add("implementation", libs.library("androidx-lifecycle-viewmodel-compose"))
            add("implementation", libs.library("androidx-hilt-lifecycle-viewmodel-compose"))

            add("testImplementation", project(":core:testing"))
            add("testImplementation", testFixtures(project(":core:time")))
            add("testImplementation", libs.library("kotlinx-coroutines-test"))
            add("testImplementation", libs.library("turbine"))
        }
    }
}

import dev.kpetriv.sprout.buildlogic.libs
import dev.kpetriv.sprout.buildlogic.library
import dev.kpetriv.sprout.buildlogic.pluginId
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

/** Hilt for an Android module, with the annotation processor running on KSP. */
class HiltConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply(libs.pluginId("ksp"))
        pluginManager.apply(libs.pluginId("hilt"))

        dependencies {
            add("implementation", libs.library("hilt-android"))
            add("ksp", libs.library("hilt-compiler"))
        }

        // KSP outputs restored from the Gradle build cache have come back empty (no generated sources), which leaves
        // Hilt's aggregated deps and the Hilt_* base classes missing and fails hiltJavaCompile* or R8 (D-M3-21). KSP
        // therefore always runs; its inputs are small and the compile tasks downstream stay cacheable.
        tasks.matching { it.name.startsWith("ksp") }.configureEach {
            outputs.cacheIf("KSP outputs restored from the build cache can be empty (D-M3-21)") { false }
        }
    }
}

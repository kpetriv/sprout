import dev.kpetriv.sprout.buildlogic.configureDependencyCheck
import dev.kpetriv.sprout.buildlogic.configureKotlinJvm
import dev.kpetriv.sprout.buildlogic.configureStaticAnalysis
import dev.kpetriv.sprout.buildlogic.configureUnitTests
import dev.kpetriv.sprout.buildlogic.libs
import dev.kpetriv.sprout.buildlogic.pluginId
import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * Pure Kotlin/JVM module with no Android dependencies (KMP-ready domain code).
 * Android Lint is not applied: there are no Android APIs to check, and Detekt + ktlint cover the Kotlin.
 */
class JvmLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply(libs.pluginId("kotlin-jvm"))

        configureKotlinJvm()
        configureStaticAnalysis()
        configureDependencyCheck()
        configureUnitTests()
    }
}

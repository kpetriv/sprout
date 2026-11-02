import com.android.build.api.dsl.LibraryExtension
import dev.kpetriv.sprout.buildlogic.configureAndroidLint
import dev.kpetriv.sprout.buildlogic.configureDependencyCheck
import dev.kpetriv.sprout.buildlogic.configureKotlinAndroid
import dev.kpetriv.sprout.buildlogic.configureStaticAnalysis
import dev.kpetriv.sprout.buildlogic.configureUnitTests
import dev.kpetriv.sprout.buildlogic.libs
import dev.kpetriv.sprout.buildlogic.pluginId
import dev.kpetriv.sprout.buildlogic.version
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/** Android library without Compose (data, crypto, sync modules). */
class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply(libs.pluginId("android-library"))

        extensions.configure<LibraryExtension> {
            configureKotlinAndroid(this)
            configureAndroidLint(lint)
            defaultConfig.testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
            testOptions.targetSdk = libs.version("androidTargetSdk").toInt()
            lint.targetSdk = libs.version("androidTargetSdk").toInt()
        }
        configureStaticAnalysis()
        configureDependencyCheck()
        configureUnitTests()
    }
}

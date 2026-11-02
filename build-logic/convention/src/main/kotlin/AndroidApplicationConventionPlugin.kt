import com.android.build.api.dsl.ApplicationExtension
import dev.kpetriv.sprout.buildlogic.configureAndroidLint
import dev.kpetriv.sprout.buildlogic.configureCompose
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

/** The app shell: Android application + Compose (the app is Compose-only). */
class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply(libs.pluginId("android-application"))

        extensions.configure<ApplicationExtension> {
            configureKotlinAndroid(this)
            configureCompose(this)
            configureAndroidLint(lint)
            defaultConfig.targetSdk = libs.version("androidTargetSdk").toInt()
            defaultConfig.testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }
        configureStaticAnalysis()
        configureDependencyCheck()
        configureUnitTests()
    }
}

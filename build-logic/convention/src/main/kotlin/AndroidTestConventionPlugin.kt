import com.android.build.api.dsl.TestExtension
import dev.kpetriv.sprout.buildlogic.configureAndroidLint
import dev.kpetriv.sprout.buildlogic.configureKotlinAndroid
import dev.kpetriv.sprout.buildlogic.configureStaticAnalysis
import dev.kpetriv.sprout.buildlogic.libs
import dev.kpetriv.sprout.buildlogic.pluginId
import dev.kpetriv.sprout.buildlogic.version
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/** A com.android.test module (Macrobenchmark / Baseline Profile generator). */
class AndroidTestConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply(libs.pluginId("android-test"))

        extensions.configure<TestExtension> {
            configureKotlinAndroid(this)
            configureAndroidLint(lint)
            defaultConfig.targetSdk = libs.version("androidTargetSdk").toInt()
            defaultConfig.testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }
        configureStaticAnalysis()
    }
}

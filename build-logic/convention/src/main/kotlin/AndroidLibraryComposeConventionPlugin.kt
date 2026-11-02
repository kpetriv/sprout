import com.android.build.api.dsl.LibraryExtension
import dev.kpetriv.sprout.buildlogic.configureCompose
import dev.kpetriv.sprout.buildlogic.libs
import dev.kpetriv.sprout.buildlogic.pluginId
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/** Android library with Compose (design system, shared UI, widget). */
class AndroidLibraryComposeConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply(libs.pluginId("sprout-android-library"))
        extensions.configure<LibraryExtension> {
            configureCompose(this)
        }
    }
}

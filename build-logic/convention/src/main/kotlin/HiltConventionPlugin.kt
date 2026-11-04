import dev.kpetriv.sprout.buildlogic.libs
import dev.kpetriv.sprout.buildlogic.library
import dev.kpetriv.sprout.buildlogic.pluginId
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
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

        // KSP2 has produced empty output for a module while other modules' KSP tasks ran in parallel, and then
        // reported UP-TO-DATE (or cached the empty result), which leaves Hilt's aggregated deps and the Hilt_* base
        // classes missing and fails hiltJavaCompile* or R8 (D-M3-21, google/ksp#3286). Three guards: KSP tasks
        // run one at a time through a shared build service (everything else stays parallel), their outputs are
        // never restored from the build cache, and a Hilt module whose KSP generated nothing fails loudly instead
        // of poisoning downstream tasks.
        val kspSerial = gradle.sharedServices.registerIfAbsent("kspSerial", KspSerialService::class.java) {
            maxParallelUsages.set(1)
        }
        val generatedKsp = layout.buildDirectory.dir("generated/ksp")
        val hasHiltSources = provider {
            fileTree("src/main").matching { include("**/*.kt") }.files.any { file ->
                file.readText().let { it.contains("@Module") || it.contains("@HiltViewModel") || it.contains("@AndroidEntryPoint") }
            }
        }
        tasks.matching { it.name.startsWith("ksp") }.configureEach {
            usesService(kspSerial)
            outputs.cacheIf("KSP outputs restored from the build cache can be empty (D-M3-21)") { false }
            val variantDir = name.removePrefix("ksp").removeSuffix("Kotlin").replaceFirstChar { it.lowercase() }
            val isMainVariant = !variantDir.contains("UnitTest") && !variantDir.contains("TestFixtures") &&
                !variantDir.contains("AndroidTest")
            doLast {
                if (isMainVariant && hasHiltSources.get()) {
                    val javaDir = generatedKsp.get().dir("$variantDir/java").asFile
                    val generated = javaDir.walkTopDown().any { it.isFile && it.extension == "java" }
                    if (!generated) {
                        throw GradleException(
                            "$path generated no Java sources in $javaDir although this module has Hilt " +
                                "annotations; rerun the build (D-M3-21, KSP2 empty output).",
                        )
                    }
                }
            }
        }

        // Lint's analysis tasks read the module's generated sources, including the Hilt sources KSP writes for the
        // other variants: in one build `lintAnalyzeDebugUnitTest` has read `generated/ksp/release` while
        // `kspReleaseKotlin` was deleting and rewriting it, and failed on a vanished file (M7 fix pass 2, D-V1-21).
        // Lint never feeds KSP or Hilt, so every lint task of a Hilt module runs after every KSP and Hilt task of
        // the same module that is in the task graph (all variants; ordering only, nothing is added to the graph).
        val generators = tasks.matching { task ->
            task.name.startsWith("ksp") || task.name.startsWith("hiltAggregateDeps") ||
                task.name.startsWith("hiltJavaCompile")
        }
        tasks.matching { it.name.startsWith("lint") && it.name != "lint" }.configureEach {
            mustRunAfter(generators)
        }
    }
}

/** Serialises KSP tasks across the build; see [HiltConventionPlugin]. */
abstract class KspSerialService : BuildService<BuildServiceParameters.None>

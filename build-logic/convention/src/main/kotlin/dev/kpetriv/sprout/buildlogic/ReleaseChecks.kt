package dev.kpetriv.sprout.buildlogic

import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.register
import java.io.File

/**
 * Checks on the shrunk release build (M8 §3, §4), both part of `check` and of every task that builds or ships the
 * Play bundle (`bundleRelease`, `signReleaseBundle`, Play Publisher's tasks; app/build.gradle.kts):
 *
 * - `checkReleaseMapping`: R8 ran for release and wrote a non-empty `mapping.txt` (uploaded with the bundle so Play
 *   can de-obfuscate crashes), and reported no missing classes: R8 writes the `-dontwarn` rules it would need to
 *   `missing_rules.txt` next to the mapping, so a non-empty one means the keep rules (Hilt, Room, Glance, …) reference
 *   classes that are not there. Robolectric cannot start the obfuscated APK; this is the release smoke check instead.
 * - `checkReleaseAppCheck`: the debug App Check provider never ships (ADR-0003, D-M3-6). No
 *   `com.google.firebase:firebase-appcheck-debug*` module may be on the release runtime classpath, and no
 *   `com.google.firebase.appcheck.debug` class may be in the shrunk output (R8's mapping lists every class it kept).
 */
internal fun Project.configureReleaseChecks() {
    extensions.getByType<ApplicationAndroidComponentsExtension>().onVariants(
        extensions.getByType<ApplicationAndroidComponentsExtension>().selector().withName("release"),
    ) { variant ->
        val mapping = variant.artifacts.get(SingleArtifact.OBFUSCATION_MAPPING_FILE)
        val checkMapping = tasks.register<CheckReleaseMapping>("checkReleaseMapping") {
            group = "verification"
            description = "Fails unless R8 wrote the release mapping file and reported no missing classes."
            mappingFile.from(mapping)
            // Where AGP tells you to find them ("Missing classes rules are in …"), and next to the mapping artifact.
            missingRules.from(
                layout.buildDirectory.file("outputs/mapping/${variant.name}/missing_rules.txt"),
                mapping.map { it.asFile.resolveSibling("missing_rules.txt") },
            )
            report.set(layout.buildDirectory.file("reports/release-checks/mapping.txt"))
        }
        val checkAppCheck = tasks.register<CheckReleaseAppCheck>("checkReleaseAppCheck") {
            group = "verification"
            description = "Fails if the debug App Check provider is on the release classpath or in the shrunk output."
            modules.set(variant.runtimeConfiguration.incoming.resolutionResult.rootComponent.map(::moduleCoordinates))
            mappingFile.from(mapping)
            report.set(layout.buildDirectory.file("reports/release-checks/app-check.txt"))
        }
        // The tasks that build or ship the Play bundle depend on both as well, wired in app/build.gradle.kts with the
        // app's own release checks (audit L1).
        tasks.named("check") { dependsOn(checkMapping, checkAppCheck) }
    }
}

private fun moduleCoordinates(root: ResolvedComponentResult): List<String> {
    val seen = mutableSetOf<ResolvedComponentResult>()
    val queue = ArrayDeque(listOf(root))
    while (queue.isNotEmpty()) {
        val component = queue.removeFirst()
        if (seen.add(component)) {
            component.dependencies.filterIsInstance<ResolvedDependencyResult>().forEach { queue += it.selected }
        }
    }
    return seen.mapNotNull { (it.id as? ModuleComponentIdentifier)?.run { "$group:$module:$version" } }.sorted()
}

@CacheableTask
abstract class CheckReleaseMapping : DefaultTask() {
    /** R8's `mapping.txt` for release; empty when the release build is not minified. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val mappingFile: ConfigurableFileCollection

    /** R8's `missing_rules.txt` wherever it may be; absent when nothing is missing. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val missingRules: ConfigurableFileCollection

    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun verify() {
        val mapping = mappingFile.files.firstOrNull { it.isFile && it.length() > 0 }
            ?: throw GradleException("R8 wrote no release mapping file: is minification on for release?")
        val missing = missingRules.files.filter(File::isFile).flatMap(File::readLines)
            .map(String::trim).filter { it.isNotEmpty() && !it.startsWith("#") }.distinct()
        if (missing.isNotEmpty()) {
            throw GradleException(
                "R8 reported missing classes for release (${missing.size} rules in missing_rules.txt), for example " +
                    missing.take(MISSING_SHOWN).joinToString() + ". Add the dependency or a reviewed -dontwarn rule.",
            )
        }
        report.get().asFile.writeText("Release mapping present (${mapping.length()} bytes); no missing classes.\n")
    }
}

@CacheableTask
abstract class CheckReleaseAppCheck : DefaultTask() {
    /** `group:name:version` of every module on the release runtime classpath. */
    @get:Input
    abstract val modules: ListProperty<String>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val mappingFile: ConfigurableFileCollection

    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun verify() {
        val debugModules = modules.get().filter { it.startsWith("com.google.firebase:firebase-appcheck-debug") }
        if (debugModules.isNotEmpty()) {
            throw GradleException(
                "The release runtime classpath contains the debug App Check provider (ADR-0003): " +
                    debugModules.joinToString() + ". Keep it a debugImplementation dependency.",
            )
        }
        val mapping = mappingFile.files.firstOrNull(File::isFile)
            ?: throw GradleException("R8 wrote no release mapping file to check for the debug App Check provider.")
        val shipped = mapping.useLines { lines ->
            lines.filter { it.startsWith(DEBUG_PROVIDER_PACKAGE) }.map { it.substringBefore(" ->") }.take(MISSING_SHOWN)
                .toList()
        }
        if (shipped.isNotEmpty()) {
            throw GradleException(
                "The shrunk release build contains debug App Check classes (ADR-0003): " + shipped.joinToString(),
            )
        }
        report.get().asFile.writeText(
            "No firebase-appcheck-debug module among ${modules.get().size} release modules; " +
                "no $DEBUG_PROVIDER_PACKAGE class in the release mapping.\n",
        )
    }
}

private const val DEBUG_PROVIDER_PACKAGE = "com.google.firebase.appcheck.debug."
private const val MISSING_SHOWN = 5

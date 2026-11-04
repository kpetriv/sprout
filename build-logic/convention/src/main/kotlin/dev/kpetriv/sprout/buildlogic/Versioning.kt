package dev.kpetriv.sprout.buildlogic

import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.provider.ValueSource
import org.gradle.api.provider.ValueSourceParameters
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.of
import org.gradle.process.ExecOperations
import java.io.ByteArrayOutputStream
import java.io.File
import javax.inject.Inject

/**
 * The app's version, from git (D-M8-4):
 *
 * - `versionName` is the latest tag matching `v*` reachable from `HEAD` (`git describe --tags --match 'v*'
 *   --abbrev=0`) without its leading `v`, so tagging `v1.2.0` builds `1.2.0`. With no such tag, or no git (a source
 *   archive), it is [FALLBACK_VERSION_NAME].
 * - `versionCode` is `-PversionCode=<n>` when given (a positive integer up to Play's 2,100,000,000), otherwise the
 *   number of commits on `HEAD` (`git rev-list --count HEAD`), otherwise 1. The commit count only grows along one
 *   branch, so release from `main` (or a tag on it); a rebuild of an older tag needs a higher `-PversionCode`.
 *   A shallow clone counts only the fetched commits: the release workflow checks out with the full history.
 *
 * Both are set on every variant's outputs through the Variant API, so `BuildConfig.VERSION_NAME`/`VERSION_CODE`
 * and the manifest carry them. git runs in a [ValueSource] that the providers read when a task needs the value, so the
 * configuration cache stays valid across commits and git is not run for builds that never read the version.
 */
internal fun Project.configureGitVersioning() {
    val versionName = gitOutput("describe", "--tags", "--match", "v*", "--abbrev=0")
        .map { it.removePrefix("v") }
        .orElse(FALLBACK_VERSION_NAME)
    val versionCode = providers.gradleProperty("versionCode")
        .map(::parseVersionCode)
        .orElse(gitOutput("rev-list", "--count", "HEAD").map(String::toInt))
        .orElse(1)
    extensions.getByType<ApplicationAndroidComponentsExtension>().onVariants { variant ->
        variant.outputs.forEach { output ->
            output.versionName.set(versionName)
            output.versionCode.set(versionCode)
        }
    }
}

/** `-PversionCode` must be what Play accepts; anything else fails the build instead of shipping a wrong code. */
private fun parseVersionCode(value: String): Int {
    val code = value.trim().toIntOrNull()
    if (code == null || code !in 1..MAX_VERSION_CODE) {
        throw GradleException("-PversionCode must be an integer from 1 to $MAX_VERSION_CODE, got \"$value\".")
    }
    return code
}

private fun Project.gitOutput(vararg args: String): Provider<String> = providers.of(GitOutput::class) {
    parameters.arguments.set(args.toList())
    parameters.workingDirectory.set(rootDir.absolutePath)
}

/** One git command's trimmed standard output; absent when git is missing, fails, or prints nothing. */
abstract class GitOutput : ValueSource<String, GitOutput.Parameters> {
    interface Parameters : ValueSourceParameters {
        val arguments: ListProperty<String>
        val workingDirectory: Property<String>
    }

    @get:Inject
    abstract val execOperations: ExecOperations

    override fun obtain(): String? {
        val output = ByteArrayOutputStream()
        val result = runCatching {
            execOperations.exec {
                commandLine(listOf("git") + parameters.arguments.get())
                workingDir = File(parameters.workingDirectory.get())
                standardOutput = output
                errorOutput = ByteArrayOutputStream()
                isIgnoreExitValue = true
            }
        }.getOrNull()
        return output.toString(Charsets.UTF_8).trim().takeIf { result?.exitValue == 0 && it.isNotEmpty() }
    }
}

/** The version name without a `v*` tag; app `checkReleaseConfiguration` refuses it for a Play bundle. */
const val FALLBACK_VERSION_NAME = "0.1.0-dev"

/** Google Play's largest `versionCode`. */
private const val MAX_VERSION_CODE = 2_100_000_000

package dev.kpetriv.sprout.buildlogic

import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.owasp.dependencycheck.gradle.extension.DependencyCheckExtension

/**
 * OWASP Dependency-Check for a module: `./gradlew dependencyCheckAnalyze` scans every module's shipped
 * (runtime) dependencies. Not part of `build`; .github/workflows/dependency-check.yml runs it weekly.
 *
 * Applied per module rather than once at the root because the plugin's root-level `dependencyCheckAggregate`
 * resolves other projects' configurations, which Gradle 9 rejects.
 */
internal fun Project.configureDependencyCheck() {
    pluginManager.apply(libs.pluginId("dependency-check"))

    extensions.configure<DependencyCheckExtension> {
        // Fail on any High or Critical finding.
        failBuildOnCVSS.set(FAIL_ON_CVSS)
        formats.set(listOf("HTML", "SARIF"))
        suppressionFile.set(rootProject.file("config/dependency-check/suppressions.xml").path)
        // What ships in the APK: Android release and JVM runtime classpaths. Build tooling (AGP, KSP
        // processors, Detekt, Lint) is left to Renovate and dependency verification.
        scanConfigurations.set(listOf("releaseRuntimeClasspath", "runtimeClasspath"))
        // From the environment only, never a file. Dependency-Check 13 cannot download NVD data without a
        // key (the NVD client rejects the request), so a run without NVD_API_KEY fails at the update step.
        nvd.apiKey.set(providers.environmentVariable("NVD_API_KEY"))
        analyzers.apply {
            // OSS Index now requires a Sonatype account; NVD plus CISA KEV are the sources we rely on.
            ossIndex.enabled.set(false)
            // Nothing in the Android build is .NET or Node; these analyzers only add noise and network calls.
            assemblyEnabled.set(false)
            nodeAudit.enabled.set(false)
            retirejs.enabled.set(false)
        }
    }
}

private const val FAIL_ON_CVSS = 7f

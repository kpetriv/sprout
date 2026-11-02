package dev.kpetriv.sprout.buildlogic

import io.gitlab.arturbosch.detekt.Detekt
import io.gitlab.arturbosch.detekt.DetektCreateBaselineTask
import io.gitlab.arturbosch.detekt.extensions.DetektExtension
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.withType
import org.jlleitschuh.gradle.ktlint.KtlintExtension

/** Detekt (code smells) and ktlint (formatting) for every Kotlin module. */
internal fun Project.configureStaticAnalysis() {
    pluginManager.apply(libs.pluginId("detekt"))
    pluginManager.apply(libs.pluginId("ktlint"))

    extensions.configure<DetektExtension> {
        config.setFrom(rootProject.file("config/detekt/detekt.yml"))
        buildUponDefaultConfig = true
        parallel = true
    }

    // Detekt otherwise targets the Gradle daemon's JVM, which can be newer than its compiler accepts.
    tasks.withType<Detekt>().configureEach { jvmTarget = JVM_TARGET.target }
    tasks.withType<DetektCreateBaselineTask>().configureEach { jvmTarget = JVM_TARGET.target }

    // Detekt 1.23 ships its own Kotlin compiler; keep the project's newer Kotlin off its classpath.
    val detektKotlin = libs.version("detektKotlin")
    configurations.matching { it.name == "detekt" }.configureEach {
        resolutionStrategy.eachDependency {
            if (requested.group == "org.jetbrains.kotlin") useVersion(detektKotlin)
        }
    }

    extensions.configure<KtlintExtension> {
        // The ktlint-cli catalog entry exists so Renovate tracks this version.
        // Code style (android_studio) comes from .editorconfig, not the deprecated `android` flag.
        version.set(libs.library("ktlint-cli").get().version)
        filter {
            exclude { element -> element.file.path.contains("/build/") }
        }
    }
}

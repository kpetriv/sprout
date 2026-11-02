package dev.kpetriv.sprout.buildlogic

import com.android.build.api.dsl.CommonExtension
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.jetbrains.kotlin.compose.compiler.gradle.ComposeCompilerGradlePluginExtension

/** Enables Compose for an Android module. The Compose compiler comes from the Kotlin compose plugin. */
internal fun Project.configureCompose(android: CommonExtension) {
    pluginManager.apply(libs.pluginId("kotlin-compose"))
    android.buildFeatures.compose = true

    extensions.configure<ComposeCompilerGradlePluginExtension> {
        // Marks the pure-Kotlin model/domain types (no Compose dependency, so no @Immutable) as stable.
        stabilityConfigurationFiles.add(rootProject.layout.projectDirectory.file("compose_compiler_config.conf"))
    }

    dependencies {
        val bom = platform(libs.library("androidx-compose-bom"))
        add("implementation", bom)
        add("androidTestImplementation", bom)
        add("implementation", libs.library("androidx-compose-ui-tooling-preview"))
        add("debugImplementation", libs.library("androidx-compose-ui-tooling"))
    }
}

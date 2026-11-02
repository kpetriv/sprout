package dev.kpetriv.sprout.buildlogic

import com.android.build.api.dsl.CommonExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

private val JAVA_VERSION = JavaVersion.VERSION_17
internal val JVM_TARGET = JvmTarget.JVM_17

/** Shared Android settings. Kotlin is compiled by AGP's built-in Kotlin support (AGP 9+). */
internal fun Project.configureKotlinAndroid(android: CommonExtension) {
    android.apply {
        compileSdk = libs.version("androidCompileSdk").toInt()
        defaultConfig.minSdk = libs.version("androidMinSdk").toInt()
        compileOptions.sourceCompatibility = JAVA_VERSION
        compileOptions.targetCompatibility = JAVA_VERSION
    }
    configureKotlinCompilation()
}

/** Shared settings for pure Kotlin/JVM modules. */
internal fun Project.configureKotlinJvm() {
    extensions.configure<JavaPluginExtension> {
        sourceCompatibility = JAVA_VERSION
        targetCompatibility = JAVA_VERSION
    }
    configureKotlinCompilation()
}

private fun Project.configureKotlinCompilation() {
    tasks.withType<KotlinJvmCompile>().configureEach {
        compilerOptions {
            jvmTarget.set(JVM_TARGET)
            freeCompilerArgs.add("-Xjsr305=strict")
        }
    }
}

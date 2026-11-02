package dev.kpetriv.sprout.buildlogic

import org.gradle.api.Project
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.withType

/** JUnit 4 + kotlin.test for local (JVM) unit tests in every module. */
internal fun Project.configureUnitTests() {
    dependencies {
        add("testImplementation", libs.library("kotlin-test-junit"))
        add("testImplementation", libs.library("junit4"))
    }
    tasks.withType<Test>().configureEach {
        useJUnit()
        testLogging {
            events("failed")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }
}

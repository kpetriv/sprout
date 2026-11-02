package dev.kpetriv.sprout.buildlogic

import com.android.build.api.dsl.Lint
import org.gradle.api.Project

/**
 * Android Lint for every module. Severity overrides (security category as errors) live in
 * config/lint/lint.xml so Android Studio and Gradle read the same rules.
 */
internal fun Project.configureAndroidLint(lint: Lint) {
    lint.apply {
        lintConfig = rootProject.file("config/lint/lint.xml")
        abortOnError = true
        checkReleaseBuilds = true
        checkTestSources = false
    }
}

// Plugins are declared here (not applied) so every module shares one classpath and one version.
// Modules apply the sprout.* convention plugins from build-logic instead of these directly.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.test) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.baselineprofile) apply false
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.ktlint) apply false
    // Applied by the sprout.android.screenshot convention plugin to modules with screenshot tests.
    alias(libs.plugins.roborazzi) apply false
    // Applied to every module by build-logic (DependencyCheck.kt), not part of `build`.
    alias(libs.plugins.dependency.check) apply false
}

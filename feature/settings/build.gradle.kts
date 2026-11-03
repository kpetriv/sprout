plugins {
    alias(libs.plugins.sprout.android.feature)
    // Robolectric, Compose UI test and Roborazzi for the screen tests and screenshot goldens in src/test/screenshots.
    alias(libs.plugins.sprout.android.screenshot)
}

android {
    namespace = "dev.kpetriv.sprout.feature.settings"
}

dependencies {
    // Kid, allowance, savings goal, interest and household policy repositories; every write audits and outboxes.
    implementation(projects.core.database)
    // AllowanceSchedule, SavingsGoal and AllowancePlanner.nextDueDate.
    implementation(projects.core.domainHousehold)
    // Money, Currency, Account and InterestCalculator.
    implementation(projects.core.domainLedger)
    // ParentAuthorizer.availability() for the fingerprint signing status, LockoutDecision for PIN change feedback.
    implementation(projects.core.crypto)
    implementation(projects.core.time)
    // rememberLauncherForActivityResult + ActivityResultContracts.CreateDocument for the household export.
    implementation(libs.androidx.activity.compose)
    constraints {
        // activity 1.13 asks for navigationevent 1.0.0; the app resolves 1.1.1 (through navigation3), which is the
        // version in gradle/verification-metadata.xml. Align on it so this module verifies on its own too.
        implementation("androidx.navigationevent:navigationevent:1.1.1")
    }
    // activity 1.13 asks for androidx.core 1.18; align on the catalog's version, which the app resolves.
    implementation(libs.androidx.core.ktx)

    testImplementation(testFixtures(projects.core.crypto))
}

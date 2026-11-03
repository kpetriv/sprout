plugins {
    alias(libs.plugins.sprout.android.feature)
    // Robolectric, Compose UI test and Roborazzi for the screen tests and screenshot goldens in src/test/screenshots.
    alias(libs.plugins.sprout.android.screenshot)
}

android {
    namespace = "dev.kpetriv.sprout.feature.chores"
}

dependencies {
    // ChoreRepository, KidRepository, PhotoStore and ApproveSubmissionUseCase, behind internal seams: the data layer
    // is not part of this module's API.
    implementation(projects.core.database)
    // Chore, ChoreScheduler, ChoreValidation, ChoreProposalRules and ChoreRewardIntent.
    implementation(projects.core.domainHousehold)
    // Money, Currency and ParentAuthorization (ParentAuthorizationRequester's result type).
    implementation(projects.core.domainLedger)
    implementation(projects.core.time)
    // ParentAuthUnavailableException passes through the approval prompt untouched (D-M2-1 fallback in the use case).
    implementation(projects.core.crypto)
    // rememberLauncherForActivityResult + ActivityResultContracts.TakePicture for chore photos.
    implementation(libs.androidx.activity.compose)
    constraints {
        // activity 1.13 asks for navigationevent 1.0.0; the app resolves 1.1.1 (through navigation3), which is the
        // version in gradle/verification-metadata.xml. Align on it so this module verifies on its own too.
        implementation("androidx.navigationevent:navigationevent:1.1.1")
    }
    // FileProvider: the camera app writes the photo into this module's cache directory through a content URI.
    implementation(libs.androidx.core.ktx)
}

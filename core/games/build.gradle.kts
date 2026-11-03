plugins {
    alias(libs.plugins.sprout.jvm.library)
}

dependencies {
    // KidUiTier, so callers can map a kid's tier straight to a Difficulty and the game catalogue.
    api(projects.core.model)
}

plugins {
    alias(libs.plugins.sprout.jvm.library)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    // Ids and profile types are @Serializable so the ledger, Room and sync share one wire form.
    api(libs.kotlinx.serialization.core)

    testImplementation(libs.kotlinx.serialization.json)
}

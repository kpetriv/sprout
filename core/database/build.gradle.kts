plugins {
    alias(libs.plugins.sprout.android.library)
}

android {
    namespace = "dev.kpetriv.sprout.core.database"
}

// Room (KSP) + SQLCipher are added in M1 together with the first entities.

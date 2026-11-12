plugins {
    alias(libs.plugins.sprout.android.library.compose)
}

android {
    namespace = "dev.kpetriv.sprout.core.ui"
}

dependencies {
    api(projects.core.designsystem)
    // MoneyFormatter and ledgerAmountText take the ledger's Money and Currency.
    api(projects.core.domainLedger)
}

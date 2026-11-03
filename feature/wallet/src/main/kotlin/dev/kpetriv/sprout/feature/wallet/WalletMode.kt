package dev.kpetriv.sprout.feature.wallet

/**
 * Who is looking at the wallet. Only [PARENT] may make manual adjustments. M1 has no lock screen yet, so the
 * app passes [PARENT]; once `feature/lock` lands, kid mode passes [KID].
 */
enum class WalletMode {
    PARENT,
    KID,
}

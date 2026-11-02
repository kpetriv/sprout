package dev.kpetriv.sprout.feature.wallet

import dev.kpetriv.sprout.core.domainledger.Account
import dev.kpetriv.sprout.core.model.Kid
import dev.kpetriv.sprout.core.model.KidId
import kotlinx.coroutines.flow.Flow

/**
 * Kid profile and account reads the wallet needs. The app binds this to the `core/database` repositories;
 * tests and previews use an in-memory fake. Both flows re-emit on every change.
 */
interface WalletDataSource {
    /** The kid, or null when no such kid exists (deleted, or a stale navigation argument). */
    fun observeKid(kidId: KidId): Flow<Kid?>

    /** Every account of [kidId]: main money, minutes and savings. */
    fun accountsFor(kidId: KidId): Flow<List<Account>>
}

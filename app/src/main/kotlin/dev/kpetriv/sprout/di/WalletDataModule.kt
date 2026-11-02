package dev.kpetriv.sprout.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.kpetriv.sprout.core.database.AccountRepository
import dev.kpetriv.sprout.core.database.KidRepository
import dev.kpetriv.sprout.core.domainledger.Account
import dev.kpetriv.sprout.core.model.Kid
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.feature.wallet.WalletDataSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/** Binds the wallet's [WalletDataSource] to the `core/database` repositories. */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class WalletDataModule {
    @Binds
    abstract fun walletDataSource(impl: RepositoryWalletDataSource): WalletDataSource
}

/**
 * [WalletDataSource] over [KidRepository] and [AccountRepository]. The kid is looked up among the active kids
 * (which re-emit on every kid change); an archived kid falls back to [KidRepository.getKid], so a parent can still
 * open its wallet and history. Null only when the kid does not exist at all.
 */
internal class RepositoryWalletDataSource @Inject constructor(
    private val kids: KidRepository,
    private val accounts: AccountRepository,
) : WalletDataSource {
    override fun observeKid(kidId: KidId): Flow<Kid?> = kids.observeKids()
        .map { active -> active.firstOrNull { it.id == kidId } ?: kids.getKid(kidId) }
        .distinctUntilChanged()

    override fun accountsFor(kidId: KidId): Flow<List<Account>> = accounts.accountsFor(kidId)
}

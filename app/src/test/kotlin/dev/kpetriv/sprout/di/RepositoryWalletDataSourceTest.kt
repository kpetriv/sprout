package dev.kpetriv.sprout.di

import app.cash.turbine.test
import dev.kpetriv.sprout.FakeAccountRepository
import dev.kpetriv.sprout.FakeKidRepository
import dev.kpetriv.sprout.core.domainledger.Account
import dev.kpetriv.sprout.core.domainledger.AccountKind
import dev.kpetriv.sprout.core.domainledger.Currency
import dev.kpetriv.sprout.core.model.AccountId
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.KidUiTier
import dev.kpetriv.sprout.core.model.YearMonth
import dev.kpetriv.sprout.kid
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RepositoryWalletDataSourceTest {
    private val leo = kid("Leo", YearMonth(2017, 1), KidUiTier.SPROUT)

    @Test
    fun `observes an active kid and follows its changes`() = runTest {
        val kids = FakeKidRepository(listOf(leo))
        val source = RepositoryWalletDataSource(kids, FakeAccountRepository(emptyMap()))
        source.observeKid(leo.id).test {
            assertEquals(leo, awaitItem())
            val renamedTier = leo.copy(uiTier = KidUiTier.SAPLING)
            kids.upsert(renamedTier)
            assertEquals(renamedTier, awaitItem())
        }
    }

    @Test
    fun `an archived kid is still found`() = runTest {
        val kids = FakeKidRepository(listOf(leo.copy(archived = true)))
        val source = RepositoryWalletDataSource(kids, FakeAccountRepository(emptyMap()))
        assertEquals(leo.copy(archived = true), source.observeKid(leo.id).first())
    }

    @Test
    fun `an unknown kid is null`() = runTest {
        val source = RepositoryWalletDataSource(FakeKidRepository(listOf(leo)), FakeAccountRepository(emptyMap()))
        assertNull(source.observeKid(KidId("nobody")).first())
    }

    @Test
    fun `accounts come from the account repository`() = runTest {
        val account = Account(AccountId("a1"), leo.id, Currency.Minutes, AccountKind.MAIN)
        val source = RepositoryWalletDataSource(
            FakeKidRepository(listOf(leo)),
            FakeAccountRepository(mapOf(leo.id to listOf(account))),
        )
        assertEquals(listOf(account), source.accountsFor(leo.id).first())
    }
}

package dev.kpetriv.sprout.core.database

import dev.kpetriv.sprout.core.domainledger.Account
import dev.kpetriv.sprout.core.domainledger.LedgerPolicy
import dev.kpetriv.sprout.core.domainledger.LedgerRepository
import dev.kpetriv.sprout.core.domainledger.LedgerRepositoryContract
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals

/**
 * The domain's [LedgerRepositoryContract] against [RoomLedgerRepository] on in-memory Room. The contract writes from
 * several author devices, so it runs with [AcceptAnyEvent] in place of the local-origin check, [AnyPurchase] in place
 * of the reward catalogue, and the contract's [LedgerPolicy] with parent signatures verified against the domain
 * fixtures' `FakeParentAuthorization`.
 */
@RunWith(RobolectricTestRunner::class)
class RoomLedgerRepositoryContractTest : LedgerRepositoryContract() {
    private val databases = mutableListOf<SproutDatabase>()

    override suspend fun createRepository(accounts: List<Account>, policy: LedgerPolicy): LedgerRepository {
        val database = inMemoryDatabase().also(databases::add)
        database.register(accounts)
        return multiDeviceRepository(database, policy = policy)
    }

    @After
    fun close() = databases.forEach(SproutDatabase::close)

    @Test
    fun rejectionReasonsMatchTheContract() {
        assertEquals(CHAIN_REJECTION, RoomLedgerRepository.CHAIN_REJECTION)
        assertEquals(INVARIANT_REJECTION, RoomLedgerRepository.INVARIANT_REJECTION)
        assertEquals("unsigned or foreign event", RoomLedgerRepository.FOREIGN_REJECTION)
        assertEquals("parent signature invalid", RoomLedgerRepository.PARENT_SIGNATURE_REJECTION)
        assertEquals("reward does not match the catalogue", RoomLedgerRepository.CATALOGUE_REJECTION)
    }
}

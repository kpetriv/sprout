package dev.kpetriv.sprout.core.database

import dev.kpetriv.sprout.core.crypto.DeviceSigner
import dev.kpetriv.sprout.core.crypto.InMemoryDeviceKeyStore
import dev.kpetriv.sprout.core.crypto.JceEcdsaVerifier
import dev.kpetriv.sprout.core.domainledger.Account
import dev.kpetriv.sprout.core.domainledger.AccountKind
import dev.kpetriv.sprout.core.domainledger.AllowancePaid
import dev.kpetriv.sprout.core.domainledger.AppendResult
import dev.kpetriv.sprout.core.domainledger.ChainVerification
import dev.kpetriv.sprout.core.domainledger.ChoreRewarded
import dev.kpetriv.sprout.core.domainledger.Currency
import dev.kpetriv.sprout.core.domainledger.EventId
import dev.kpetriv.sprout.core.domainledger.HashChain
import dev.kpetriv.sprout.core.domainledger.LedgerAnomaly
import dev.kpetriv.sprout.core.domainledger.LedgerOrdering
import dev.kpetriv.sprout.core.domainledger.Money
import dev.kpetriv.sprout.core.domainledger.RewardPurchased
import dev.kpetriv.sprout.core.domainledger.signatureBytes
import dev.kpetriv.sprout.core.domainledger.signedPayload
import dev.kpetriv.sprout.core.domainledger.toHex
import dev.kpetriv.sprout.core.model.AccountId
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.Role
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class RoomLedgerWriterTest {
    private val clock = testClock()
    private val database = inMemoryDatabase()
    private val keyStore = InMemoryDeviceKeyStore(clock)
    private val signer = DeviceSigner(keyStore, Dispatchers.Unconfined)
    private val authors = LocalOnlyAuthorProvider(signer)
    private val repository = RoomLedgerRepository(database, clock, signer, JceEcdsaVerifier())

    private val kidId = KidId("kid-a")
    private val gbp = Currency.Fiat("GBP")
    private val main = Account(AccountId("kid-a-main"), kidId, gbp, AccountKind.MAIN)

    private fun writer() = RoomLedgerWriter(database, repository, signer, authors, clock)

    @After
    fun close() = database.close()

    @Test
    fun writesAVerifiableSignedChainAsTheLocalParent() = runTest {
        database.register(listOf(main))
        val writer = writer()

        assertEquals(AppendResult.Accepted, writer.append(kidId, main.id) { ChoreRewarded(it, gbp, Money(300), "c1") })
        assertEquals(AppendResult.Accepted, writer.append(kidId, main.id) { AllowancePaid(it, gbp, Money(200), "w") })
        assertEquals(AppendResult.Accepted, writer.append(kidId, main.id) { RewardPurchased(it, gbp, Money(50), "r") })

        val events = repository.observeEvents(kidId, limit = 10).first().sortedWith(LedgerOrdering)
        assertEquals(listOf(1L, 2L, 3L), events.map { it.lamport })
        assertIs<ChainVerification.Valid>(HashChain().verifyChain(events))

        val expectedDevice = keyStore.publicKey().fingerprint().toHex()
        val verifier = JceEcdsaVerifier()
        events.forEach { event ->
            assertEquals(expectedDevice, event.envelope.authorDeviceId.value)
            assertEquals(Role.PARENT, event.envelope.authorRole)
            assertEquals(LocalOnlyAuthorProvider.LOCAL_HOUSEHOLD, event.envelope.householdId)
            val signature = assertNotNull(event.signatureBytes())
            assertTrue(verifier.verify(keyStore.publicKey(), event.signedPayload(), signature))
        }
        assertEquals(3, database.deviceStateDao().get()?.lamport)
        assertEquals(events.last().envelope.hash, repository.headHash(events.last().envelope.authorDeviceId))
    }

    @Test
    fun lamportIsPersistedAndOnlyAdvancesForAcceptedEvents() = runTest {
        database.register(listOf(main))
        assertEquals(AppendResult.Accepted, writer().append(kidId, main.id) { ChoreRewarded(it, gbp, Money(10), "c") })

        val rejected = writer().append(kidId, main.id) { RewardPurchased(it, gbp, Money(500), "too much") }
        assertIs<AppendResult.Rejected>(rejected)
        assertEquals(1, database.deviceStateDao().get()?.lamport)

        // A fresh writer (as after a restart) continues from the stored clock and links to the stored head.
        assertEquals(AppendResult.Accepted, writer().append(kidId, main.id) { ChoreRewarded(it, gbp, Money(5), "c") })
        assertEquals(2, database.deviceStateDao().get()?.lamport)
        val events = repository.observeEvents(kidId, limit = 10).first().sortedWith(LedgerOrdering)
        assertIs<ChainVerification.Valid>(HashChain().verifyChain(events))
    }

    @Test
    fun aConflictingLocalCopyIsStoredAndUsesUpItsLamport() = runTest {
        database.register(listOf(main))
        val reusedId = EventId("00000000-0000-4000-8000-000000000001")
        val writer = RoomLedgerWriter(database, repository, signer, authors, clock) { reusedId }

        assertEquals(AppendResult.Accepted, writer.append(kidId, main.id) { ChoreRewarded(it, gbp, Money(10), "c") })
        val second = writer.append(kidId, main.id) { AllowancePaid(it, gbp, Money(20), "w") }

        assertIs<AppendResult.Conflict>(second)
        assertEquals(2, database.deviceStateDao().get()?.lamport)
        // Two parent copies of one id: neither applies, and the conflict is reported.
        assertEquals(emptyList(), repository.observeBalances(kidId).first().filter { it.amount != Money.ZERO })
        assertIs<LedgerAnomaly.ConflictingDuplicate>(repository.observeAnomalies(kidId).first().single())
    }
}

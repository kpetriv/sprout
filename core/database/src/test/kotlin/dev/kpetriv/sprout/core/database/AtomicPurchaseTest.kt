package dev.kpetriv.sprout.core.database

import dev.kpetriv.sprout.core.crypto.JceEcdsaVerifier
import dev.kpetriv.sprout.core.database.entity.OutboxKind
import dev.kpetriv.sprout.core.database.usecase.ManualAdjustmentResult
import dev.kpetriv.sprout.core.database.usecase.PurchaseResult
import dev.kpetriv.sprout.core.database.usecase.PurchaseRewardUseCase
import dev.kpetriv.sprout.core.database.usecase.PurchaseStep
import dev.kpetriv.sprout.core.domainhousehold.Reward
import dev.kpetriv.sprout.core.domainledger.AppendResult
import dev.kpetriv.sprout.core.domainledger.BatchAppendResult
import dev.kpetriv.sprout.core.domainledger.BatchEntry
import dev.kpetriv.sprout.core.domainledger.Currency
import dev.kpetriv.sprout.core.domainledger.LedgerEvent
import dev.kpetriv.sprout.core.domainledger.Money
import dev.kpetriv.sprout.core.domainledger.RewardPurchased
import dev.kpetriv.sprout.core.domainledger.TimeBankCredited
import dev.kpetriv.sprout.core.domainledger.Violation
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.RewardId
import dev.kpetriv.sprout.core.model.RewardKind
import dev.kpetriv.sprout.core.model.Role
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** R1: a screen-time purchase and its time credit are stored together or not at all, and owed grants get repaired. */
@RunWith(RobolectricTestRunner::class)
class AtomicPurchaseTest {
    private val household = TestHousehold()
    private val screen = Reward(
        RewardId("screen"),
        LocalOnlyAuthorProvider.LOCAL_HOUSEHOLD,
        "30 minutes",
        RewardKind.SCREEN_TIME,
        Money(100),
        Currency.Fiat("GBP"),
        minutesGranted = 30,
    )

    @After
    fun close() = household.close()

    private suspend fun fundedKid(): KidId {
        val (kid, _) = household.addKid()
        household.registerParentKey()
        household.rewards.upsert(screen)
        assertEquals(
            ManualAdjustmentResult.Signed(AppendResult.Accepted),
            household.adjust(kid.id, Money(500), "jar") { household.authorization() },
        )
        return kid.id
    }

    private suspend fun balances(kid: KidId): Map<Currency, Money> =
        household.ledger.observeBalances(kid).first().filter { it.amount != Money.ZERO }
            .associate { it.currency to it.amount }

    /**
     * A writer over the household's database and keys whose repository runs [onAppend] for every event it is asked to
     * store, inside the storing transaction, before the event's own checks: it can throw (a crash mid-batch) or refuse.
     */
    private fun writerWith(onAppend: (LedgerEvent) -> Boolean): RoomLedgerWriter {
        val device = DeviceKeyEventCheck(household.signer, JceEcdsaVerifier())
        val check = object : LocalEventCheck {
            override suspend fun bind(): LocalEventCheck.Bound {
                val inner = device.bind()
                return LocalEventCheck.Bound { event, payload -> onAppend(event) && inner.accepts(event, payload) }
            }
        }
        val repository = RoomLedgerRepository(
            household.database,
            household.clock,
            check,
            HouseholdPolicySource(household.database, JceEcdsaVerifier()),
            RewardTableCatalogue(household.database),
        )
        return RoomLedgerWriter(household.database, repository, household.signer, household.authors, household.clock)
    }

    private class Snapshot(val events: Int, val outbox: Int, val lamport: Long?)

    private suspend fun snapshot() = Snapshot(
        household.database.ledgerEventDao().count(),
        household.database.outboxDao().all().count { it.kind == OutboxKind.LEDGER_EVENT },
        household.database.deviceStateDao().get()?.lamport,
    )

    private suspend fun assertNothingChanged(before: Snapshot, kid: KidId) {
        val after = snapshot()
        assertEquals(before.events, after.events, "no event was stored")
        assertEquals(before.outbox, after.outbox, "nothing was queued for sync")
        assertEquals(before.lamport, after.lamport, "the device clock did not move")
        assertEquals(mapOf<Currency, Money>(household.gbp to Money(500)), balances(kid))
        assertTrue(household.ledger.observeEvents(kid, limit = 10).first().none { it is RewardPurchased })
    }

    @Test
    fun aCrashBetweenThePurchaseAndItsCreditPersistsNothing() = runTest {
        val kid = fundedKid()
        val before = snapshot()
        // The purchase is already inserted in the transaction when storing the credit blows up.
        val crashing = writerWith { event -> check(event !is TimeBankCredited) { "process died" }.let { true } }

        assertFailsWith<IllegalStateException> {
            PurchaseRewardUseCase(household.rewards, household.accounts, crashing)(kid, screen.id)
        }
        assertNothingChanged(before, kid)

        // The ledger is still consistent: the same purchase goes through normally afterwards.
        val purchased = assertIs<PurchaseResult.Purchased>(household.purchase(kid, screen.id))
        assertEquals(mapOf(household.gbp to Money(400), Currency.Minutes to Money(30)), balances(kid))
        assertEquals(purchased.timeCreditId, household.ledger.projection().rewardGrants.values.single().creditedBy)
    }

    @Test
    fun aRefusedCreditRollsThePurchaseBackAndSaysWhy() = runTest {
        val kid = fundedKid()
        val before = snapshot()
        val refusing = writerWith { event -> event !is TimeBankCredited }

        val refused = assertIs<PurchaseResult.Refused>(
            PurchaseRewardUseCase(household.rewards, household.accounts, refusing)(kid, screen.id),
        )
        assertEquals(PurchaseStep.TIME_CREDIT, refused.step)
        assertEquals(RoomLedgerRepository.FOREIGN_REJECTION, assertIs<AppendResult.Rejected>(refused.result).reason)
        assertNothingChanged(before, kid)
    }

    @Test
    fun aBatchIsRolledBackWhenALaterEntryBreaksARule() = runTest {
        val kid = fundedKid()
        val accounts = household.accounts.accountsFor(kid).first()
        val main = with(household) { accounts.money() }
        val minutes = with(household) { accounts.minutes() }
        val before = snapshot()

        val result = household.writer.appendAllAsKid(
            kid,
            listOf(
                BatchEntry(main.id) { envelope, _ ->
                    RewardPurchased(envelope, household.gbp, Money(100), screen.id.value, Money(30))
                },
                BatchEntry(minutes.id) { envelope, previous ->
                    TimeBankCredited(envelope, Money(999), previous.single().eventId, screen.id.value)
                },
            ),
        )

        val rejected = assertIs<BatchAppendResult.Rejected>(result)
        assertEquals(1, rejected.index)
        assertIs<Violation.GrantMinutesMismatch>(assertIs<AppendResult.Rejected>(rejected.result).violations.single())
        assertNothingChanged(before, kid)
    }

    @Test
    fun aBatchIsChainedSignedAndKidAuthored() = runTest {
        val kid = fundedKid()
        val purchased = assertIs<PurchaseResult.Purchased>(household.purchase(kid, screen.id))

        val events = household.ledger.observeEvents(kid, limit = 10).first()
        val purchase = events.filterIsInstance<RewardPurchased>().single()
        val credit = events.filterIsInstance<TimeBankCredited>().single()
        assertEquals(purchased.purchaseId, purchase.eventId)
        assertEquals(purchase.envelope.hash, credit.envelope.prevHash)
        assertEquals(purchase.lamport + 1, credit.lamport)
        assertEquals(listOf(Role.KID, Role.KID), listOf(purchase, credit).map { it.envelope.authorRole })
        assertEquals(credit.lamport, household.database.deviceStateDao().get()?.lamport)
    }

    @Test
    fun planningRedeemsAGrantThatWasNeverCredited() = runTest {
        val kid = fundedKid()
        val main = with(household) { household.accounts.accountsFor(kid).first().money() }
        // A purchase written alone (before R1, or by another path): its minutes are owed.
        val owed = household.writer.appendAsKid(kid, main.id) {
            RewardPurchased(it, household.gbp, Money(100), screen.id.value, minutesGranted = Money(30))
        }
        assertEquals(AppendResult.Accepted, owed)
        val grant = household.ledger.projection().rewardGrants.values.single()
        assertNull(grant.creditedBy)

        val planned = household.plan()
        assertEquals(listOf(grant.purchaseId), planned.redeemed.credited)
        assertEquals(mapOf(household.gbp to Money(400), Currency.Minutes to Money(30)), balances(kid))
        val credit = household.ledger.observeEvents(kid, limit = 10).first().filterIsInstance<TimeBankCredited>()
            .single()
        assertEquals(grant.purchaseId, credit.grantedBy)
        assertEquals(Role.KID, credit.envelope.authorRole)
        assertEquals(credit.eventId, household.ledger.projection().rewardGrants.getValue(grant.purchaseId).creditedBy)

        // Nothing is owed any more: the next run credits nothing.
        assertEquals(emptyList(), household.plan().redeemed.credited)
        assertEquals(Money(30), balances(kid)[Currency.Minutes])
    }
}

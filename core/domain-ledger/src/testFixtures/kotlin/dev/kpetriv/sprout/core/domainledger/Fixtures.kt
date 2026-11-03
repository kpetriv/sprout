package dev.kpetriv.sprout.core.domainledger

import dev.kpetriv.sprout.core.model.AccountId
import dev.kpetriv.sprout.core.model.CalendarDate
import dev.kpetriv.sprout.core.model.DeviceId
import dev.kpetriv.sprout.core.model.HouseholdId
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.Role
import dev.kpetriv.sprout.core.model.UserId
import dev.kpetriv.sprout.core.time.FakeClock
import kotlin.random.Random
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * Shared ledger test data: one household, one kid with GBP main and savings accounts and a minutes account, and
 * three author devices. Lives in testFixtures so storage modules can run [LedgerRepositoryContract].
 */
object Fixtures {
    val household = HouseholdId("house-1")
    val kid = KidId("kid-1")
    val gbp = Currency.Fiat("GBP")
    val main = Account(AccountId("main-1"), kid, gbp, AccountKind.MAIN)
    val savings = Account(AccountId("save-1"), kid, gbp, AccountKind.SAVINGS)
    val minutes = Account(AccountId("min-1"), kid, Currency.Minutes, AccountKind.MAIN)
    val accounts = listOf(main, savings, minutes)
    val today = CalendarDate(2026, 11, 2)

    val parentPhone = EventAuthor(household, UserId("parent-uid"), Role.PARENT, DeviceId("parent-phone"))
    val parentTablet = EventAuthor(household, UserId("parent-uid"), Role.PARENT, DeviceId("parent-tablet"))
    val kidTablet = EventAuthor(household, UserId("kid-uid"), Role.KID, DeviceId("kid-tablet"))

    val start: Instant = Instant.parse("2026-11-02T08:00:00Z")

    /** Deterministic event ids for reproducible tests. */
    fun seededIds(seed: Int): EventIdGenerator {
        val random = Random(seed)
        return UuidV4EventIdGenerator { size -> random.nextBytes(size) }
    }
}

/** One author device writing a correctly chained stream, as a real device would. */
class DeviceWriter(
    val author: EventAuthor,
    seed: Int = author.deviceId.value.hashCode(),
    val lamport: LamportClock = LamportClock(),
    private val clock: FakeClock = FakeClock(Fixtures.start),
) {
    private val factory = LedgerEventFactory(clock, lamport, Fixtures.seededIds(seed))
    var head: String? = null
        private set
    val written = mutableListOf<LedgerEvent>()

    fun <E : LedgerEvent> write(account: Account, body: (Envelope) -> E): E = write(factory, account, body)

    /**
     * Writes a correctly chained event that reuses [eventId] (for example another author's id): a conflicting
     * duplicate, as a buggy or malicious device would produce.
     */
    fun <E : LedgerEvent> writeWithId(eventId: EventId, account: Account, body: (Envelope) -> E): E =
        write(LedgerEventFactory(clock, lamport, { eventId }), account, body)

    private fun <E : LedgerEvent> write(factory: LedgerEventFactory, account: Account, body: (Envelope) -> E): E {
        clock.advanceBy(1.minutes)
        return factory.create(author, account.kidId, account.id, head, body).also {
            head = it.envelope.hash
            written += it
        }
    }

    /** Merge a pulled lamport, as a sync would. False if it was too far ahead and ignored. */
    fun observe(remote: Long) = lamport.observe(remote)

    fun chore(amount: Long, account: Account = Fixtures.main) =
        write(account) { ChoreRewarded(it, account.currency, Money(amount), "chore-1") }

    fun allowance(amount: Long) = write(Fixtures.main) { AllowancePaid(it, Fixtures.gbp, Money(amount), "weekly") }

    fun game(amount: Long, date: CalendarDate = Fixtures.today, account: Account = Fixtures.main) =
        write(account) { GamePlayed(it, account.currency, Money(amount), "memory", 10, date) }

    fun purchase(amount: Long, account: Account = Fixtures.main) =
        write(account) { RewardPurchased(it, account.currency, Money(amount), "sticker") }

    fun adjust(amount: Long, reason: String = "receipt") =
        write(Fixtures.main) { ManualAdjustment(it, Fixtures.gbp, Money(amount), reason) }

    fun transfer(amount: Long) =
        write(Fixtures.main) { TransferToSavings(it, Fixtures.gbp, Money(amount), Fixtures.savings.id) }

    /**
     * Interest of exactly [amount]: 5% for 365 days on a principal of 20 × [amount], so the recorded terms match
     * [InterestCalculator.accrue].
     */
    fun interest(amount: Long) = write(Fixtures.savings) {
        InterestAccrued(
            it,
            Fixtures.gbp,
            Money(amount),
            principal = Money(amount * INTEREST_PRINCIPAL_MULTIPLE),
            annualRateBasisPoints = 500,
            days = 365,
        )
    }

    fun timeStart(session: String) = write(Fixtures.minutes) { TimeBankStarted(it, session) }

    fun timeStop(session: String, minutes: Long) = write(Fixtures.minutes) {
        TimeBankStopped(it, session, Money(minutes))
    }

    fun compensate(amount: Long, causedBy: List<EventId>) =
        write(Fixtures.main) { OverdraftCompensated(it, Fixtures.gbp, Money(amount), causedBy, "covered") }
}

private const val INTEREST_PRINCIPAL_MULTIPLE = 20

/** Replaces the envelope of any event, keeping its stored hash (so the result fails verification). */
fun LedgerEvent.mutateEnvelope(change: (Envelope) -> Envelope): LedgerEvent = withEnvelope(change(envelope))

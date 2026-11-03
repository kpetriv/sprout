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

/**
 * One author device writing a correctly chained stream, as a real device would.
 *
 * Periodic events get a fresh claim on every call unless one is passed: chore ids are unique per device, and
 * allowance (weekly) and interest (monthly) period keys count up from 2026 per writer. Two writers that both pay
 * allowance or interest into one projection must pass their own keys, or the later one is a duplicate period.
 *
 * @param parentKey when set, every parent-only event written ([isParentOnly]) is parent-signed with it, as
 *   [LedgerWriter.appendAsParent] would. Events are never device-signed.
 */
class DeviceWriter(
    val author: EventAuthor,
    seed: Int = author.deviceId.value.hashCode(),
    val lamport: LamportClock = LamportClock(),
    private val clock: FakeClock = FakeClock(Fixtures.start),
    private val parentKey: ParentAuthorization? = null,
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

    private var choreCount = 0
    private var weekCount = 0
    private var monthCount = 0

    private fun <E : LedgerEvent> write(factory: LedgerEventFactory, account: Account, body: (Envelope) -> E): E {
        clock.advanceBy(1.minutes)
        val created = factory.create(author, account.kidId, account.id, head, body)
        val key = parentKey

        @Suppress("UNCHECKED_CAST") // withEnvelope is a data class copy, so the type is preserved.
        val event = if (key != null && created.isParentOnly()) created.parentSignWith(key) as E else created
        return event.also {
            head = it.envelope.hash
            written += it
        }
    }

    /** A chore id no other call (on any device) returns. */
    fun nextChoreId(): String = "${author.deviceId.value}-chore-${++choreCount}"

    /** The next weekly period key of this writer: 2026-W01, 2026-W02, ... */
    fun nextWeek(): String = weekCount++.let { PeriodKeys.weekly(FIRST_YEAR + it / WEEKS, it % WEEKS + 1) }

    /** The next monthly period key of this writer: 2026-01, 2026-02, ... */
    fun nextMonth(): String = monthCount++.let { PeriodKeys.monthly(FIRST_YEAR + it / MONTHS, it % MONTHS + 1) }

    /** Merge a pulled lamport, as a sync would. False if it was too far ahead and ignored. */
    fun observe(remote: Long) = lamport.observe(remote)

    fun chore(
        amount: Long,
        account: Account = Fixtures.main,
        choreId: String = nextChoreId(),
        dueOn: CalendarDate = Fixtures.today,
    ) = write(account) { ChoreRewarded(it, account.currency, Money(amount), choreId, dueOn) }

    fun allowance(amount: Long, periodKey: String = nextWeek()) =
        write(Fixtures.main) { AllowancePaid(it, Fixtures.gbp, Money(amount), "weekly", periodKey) }

    fun game(amount: Long, date: CalendarDate = Fixtures.today, account: Account = Fixtures.main) =
        write(account) { GamePlayed(it, account.currency, Money(amount), "memory", 10, date) }

    fun purchase(
        amount: Long,
        account: Account = Fixtures.main,
        rewardId: String = "sticker",
        minutesGranted: Long? = null,
    ) = write(account) { RewardPurchased(it, account.currency, Money(amount), rewardId, minutesGranted?.let(::Money)) }

    /** A screen-time reward costing [cost] that grants [minutes]. */
    fun screenTime(cost: Long, minutes: Long, rewardId: String = "screen-time") =
        purchase(cost, rewardId = rewardId, minutesGranted = minutes)

    /** Credits [minutes] to the minutes account, redeeming [grantedBy] (null: a manual parent grant). */
    fun timeCredit(
        minutes: Long,
        grantedBy: RewardPurchased? = null,
        rewardId: String = grantedBy?.rewardId ?: "screen-time",
    ) = write(Fixtures.minutes) { TimeBankCredited(it, Money(minutes), grantedBy?.eventId, rewardId) }

    fun adjust(amount: Long, reason: String = "receipt") =
        write(Fixtures.main) { ManualAdjustment(it, Fixtures.gbp, Money(amount), reason) }

    fun transfer(amount: Long) =
        write(Fixtures.main) { TransferToSavings(it, Fixtures.gbp, Money(amount), Fixtures.savings.id) }

    /**
     * Interest of exactly [amount]: 5% for 365 days on a principal of 20 × [amount], so the recorded terms match
     * [InterestCalculator.accrue].
     */
    fun interest(amount: Long, periodKey: String = nextMonth()) = write(Fixtures.savings) {
        InterestAccrued(
            it,
            Fixtures.gbp,
            Money(amount),
            principal = Money(amount * INTEREST_PRINCIPAL_MULTIPLE),
            annualRateBasisPoints = 500,
            days = 365,
            periodKey = periodKey,
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
private const val FIRST_YEAR = 2026
private const val WEEKS = 52
private const val MONTHS = 12

/**
 * A stand-in for the biometric-unlocked parent key: "signs" with SHA-256 over a key label and the payload, so a test
 * can [verify] what it signed. Records every payload; with [singleUse] a second [sign] throws, like a per-use
 * `CryptoObject`.
 */
class FakeParentAuthorization(private val label: String = "parent-key", private val singleUse: Boolean = false) :
    ParentAuthorization {
    val signed = mutableListOf<ByteArray>()

    override fun sign(payload: ByteArray): ByteArray {
        check(!singleUse || signed.isEmpty()) { "This authorisation was already used" }
        signed += payload
        return signatureOf(payload)
    }

    /** True if [signature] is this key's signature over [payload]. */
    fun verify(payload: ByteArray, signature: ByteArray): Boolean = signatureOf(payload).contentEquals(signature)

    private fun signatureOf(payload: ByteArray) = JvmSha256Hasher.sha256(label.encodeToByteArray() + payload)
}

/** Replaces the envelope of any event, keeping its stored hash (so the result fails verification). */
fun LedgerEvent.mutateEnvelope(change: (Envelope) -> Envelope): LedgerEvent = withEnvelope(change(envelope))

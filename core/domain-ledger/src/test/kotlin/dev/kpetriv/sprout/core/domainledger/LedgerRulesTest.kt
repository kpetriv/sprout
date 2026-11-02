package dev.kpetriv.sprout.core.domainledger

import dev.kpetriv.sprout.core.model.AccountId
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.Role
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LedgerRulesTest {
    private val parent = DeviceWriter(Fixtures.parentPhone)
    private val kid = DeviceWriter(Fixtures.kidTablet)

    private fun funded(vararg events: LedgerEvent): Projection =
        Projection.empty(Fixtures.accounts).apply(events.toList())

    private fun violations(event: LedgerEvent, before: Projection = funded()): List<Violation> =
        when (val result = LedgerRules.validate(event, before)) {
            ValidationResult.Valid -> emptyList()
            is ValidationResult.Invalid -> result.violations
        }

    @Test
    fun validEventsPass() {
        val before = funded(parent.chore(500), parent.chore(60, Fixtures.minutes))
        assertEquals(ValidationResult.Valid, LedgerRules.validate(parent.allowance(100), before))
        assertTrue(LedgerRules.validate(kid.purchase(500), before).isValid)
        assertTrue(LedgerRules.validate(kid.transfer(200), before).isValid)
        assertTrue(LedgerRules.validate(kid.game(50), before).isValid)
        assertTrue(LedgerRules.validate(kid.timeStart("s"), before).isValid)
    }

    @Test
    fun amountsMustBePositive() {
        for (amount in listOf(0L, -5L)) {
            assertEquals(listOf(Violation.NonPositiveAmount(Money(amount))), violations(parent.chore(amount)))
            assertEquals(listOf(Violation.NonPositiveAmount(Money(amount))), violations(parent.allowance(amount)))
            assertTrue(Violation.NonPositiveAmount(Money(amount)) in violations(kid.game(amount)))
            assertTrue(Violation.NonPositiveAmount(Money(amount)) in violations(kid.purchase(amount)))
            assertTrue(Violation.NonPositiveAmount(Money(amount)) in violations(kid.transfer(amount)))
            assertTrue(Violation.NonPositiveAmount(Money(amount)) in violations(parent.interest(amount)))
            assertTrue(Violation.NonPositiveAmount(Money(amount)) in violations(kid.timeStop("s", amount)))
            assertTrue(
                Violation.NonPositiveAmount(Money(amount)) in
                    violations(parent.compensate(amount, listOf(parent.chore(1).eventId))),
            )
        }
    }

    @Test
    fun manualAdjustmentMayBeNegativeButNeverZero() {
        assertEquals(listOf(Violation.ZeroAdjustment), violations(parent.adjust(0)))
        assertEquals(emptyList(), violations(parent.adjust(-30), funded(parent.chore(100))))
        assertEquals(emptyList(), violations(parent.adjust(30)))
        assertEquals(listOf(Violation.BlankText("reason")), violations(parent.adjust(30, reason = " ")))
    }

    @Test
    fun parentOnlyTypesRejectKidAuthors() {
        val kidAuthored = listOf(
            kid.chore(10),
            kid.allowance(10),
            kid.adjust(10),
            kid.interest(10),
            kid.compensate(10, listOf(kid.game(1).eventId)),
        )
        for (event in kidAuthored) {
            val violation = violations(event).filterIsInstance<Violation.ParentOnly>().single()
            assertEquals(Role.KID, violation.authorRole)
            assertEquals(event::class.simpleName, violation.eventType)
        }
        assertEquals(LedgerRules.PARENT_ONLY, kidAuthored.map { it::class }.toSet())
    }

    @Test
    fun debitsCannotOverdrawAtValidationTime() {
        val before = funded(parent.chore(100))
        assertEquals(
            listOf(Violation.InsufficientFunds(Fixtures.main.id, available = Money(100), requested = Money(101))),
            violations(kid.purchase(101), before),
        )
        assertEquals(
            listOf(Violation.InsufficientFunds(Fixtures.main.id, Money(100), Money(150))),
            violations(kid.transfer(150), before),
        )
        assertEquals(
            listOf(Violation.InsufficientFunds(Fixtures.main.id, Money(100), Money(101))),
            violations(parent.adjust(-101), before),
        )
        assertEquals(emptyList(), violations(kid.purchase(100), before))
    }

    @Test
    fun timeBankStopIsNeverRefusedForInsufficientMinutes() {
        val before = funded(parent.chore(10, Fixtures.minutes), kid.timeStart("s"))
        assertEquals(emptyList(), violations(kid.timeStop("s", 25), before))
    }

    @Test
    fun gamesRespectTheDailyCap() {
        val policy = LedgerPolicy(dailyGameCap = Money(30))
        val before = Projection.empty(Fixtures.accounts, policy).apply(listOf(kid.game(20)))
        assertEquals(
            listOf(Violation.DailyCapExceeded(Money(30), Money(20), Money(11))),
            violations(kid.game(11), before),
        )
        assertEquals(emptyList(), violations(kid.game(10), before))
        // A different day has its own allowance.
        assertEquals(emptyList(), violations(kid.game(30, Fixtures.today.copy(day = 3)), before))
        // So does another currency, with its own override.
        val minutesPolicy = policy.copy(dailyGameCapOverrides = mapOf(Currency.Minutes to Money(5)))
        val minutesBefore = Projection.empty(Fixtures.accounts, minutesPolicy)
        assertEquals(emptyList(), violations(kid.game(5, account = Fixtures.minutes), minutesBefore))
        assertIs<Violation.DailyCapExceeded>(
            violations(kid.game(6, account = Fixtures.minutes), minutesBefore).single(),
        )
    }

    @Test
    fun currencyMustMatchTheAccount() {
        val eurChore = parent.write(Fixtures.main) { ChoreRewarded(it, Currency.Fiat("EUR"), Money(5), "c") }
        assertEquals(
            listOf(Violation.CurrencyMismatch(Fixtures.main.id, Fixtures.gbp, Currency.Fiat("EUR"))),
            violations(eurChore),
        )
        val startOnMoney = kid.write(Fixtures.main) { TimeBankStarted(it, "s") }
        assertTrue(violations(startOnMoney).any { it is Violation.CurrencyMismatch })
    }

    @Test
    fun accountsMustBeKnownOwnedAndOfTheRightKind() {
        val stranger = Account(AccountId("other"), KidId("kid-2"), Fixtures.gbp, AccountKind.MAIN)
        assertEquals(
            listOf(Violation.UnknownAccount(stranger.id)),
            violations(parent.chore(5, stranger)),
        )
        val withStranger = Projection.empty(Fixtures.accounts + stranger)
        assertEquals(
            listOf(Violation.KidMismatch(stranger.id, KidId("kid-2"), Fixtures.kid)),
            violations(parent.chore(5, stranger).mutateEnvelope { it.copy(kidId = Fixtures.kid) }, withStranger),
        )
        val interestOnMain = parent.write(Fixtures.main) {
            InterestAccrued(it, Fixtures.gbp, Money(1), Money(20), 500, 365)
        }
        assertEquals(
            listOf(Violation.WrongAccountKind(Fixtures.main.id, AccountKind.SAVINGS, AccountKind.MAIN)),
            violations(interestOnMain),
        )
        val toSelf = kid.write(Fixtures.main) { TransferToSavings(it, Fixtures.gbp, Money(1), Fixtures.main.id) }
        assertTrue(Violation.SameAccountTransfer in violations(toSelf, funded(parent.chore(10))))
    }

    @Test
    fun duplicatesAreRejected() {
        val chore = parent.chore(10)
        assertEquals(listOf(Violation.DuplicateEvent(chore.eventId)), violations(chore, funded(chore)))
    }

    @Test
    fun timeBankSessionsAreConsistent() {
        assertEquals(listOf(Violation.TimeBankEmpty), violations(kid.timeStart("s")))
        val running = funded(parent.chore(30, Fixtures.minutes), kid.timeStart("s"))
        assertEquals(listOf(Violation.TimeBankAlreadyRunning), violations(kid.timeStart("t"), running))
        assertEquals(listOf(Violation.TimeBankNotRunning), violations(kid.timeStop("other", 5), running))
        assertEquals(listOf(Violation.TimeBankNotRunning), violations(kid.timeStop("s", 5)))
        assertEquals(listOf(Violation.BlankText("sessionId")), violations(kid.timeStop("", 5), running).take(1))
    }

    @Test
    fun creditsDebitsAndTimeBankEventsOnlyTargetMainAccounts() {
        val savingsMinutes = Account(AccountId("save-min"), Fixtures.kid, Currency.Minutes, AccountKind.SAVINGS)
        val before = Projection.empty(Fixtures.accounts + savingsMinutes)
        val onSavings = listOf(
            parent.chore(5, Fixtures.savings),
            parent.write(Fixtures.savings) { AllowancePaid(it, Fixtures.gbp, Money(5), "weekly") },
            kid.game(5, account = Fixtures.savings),
            kid.purchase(5, Fixtures.savings),
        )
        for (event in onSavings) {
            assertEquals(
                Violation.WrongAccountKind(Fixtures.savings.id, AccountKind.MAIN, AccountKind.SAVINGS),
                violations(event, before).first(),
                event.toString(),
            )
        }
        val timeOnSavings = listOf(
            kid.write(savingsMinutes) { TimeBankStarted(it, "s") },
            kid.write(savingsMinutes) { TimeBankStopped(it, "s", Money(5)) },
        )
        for (event in timeOnSavings) {
            assertEquals(
                Violation.WrongAccountKind(savingsMinutes.id, AccountKind.MAIN, AccountKind.SAVINGS),
                violations(event, before).first(),
            )
        }
        // Static rules, so projection excludes them too.
        val projected = Ledger.project(onSavings + timeOnSavings, Fixtures.accounts + savingsMinutes)
        assertEquals(6, projected.anomalies.filterIsInstance<LedgerAnomaly.InvalidEvent>().size)
        // Adjustments and compensations may target either kind.
        assertEquals(
            emptyList(),
            violations(parent.write(Fixtures.savings) { ManualAdjustment(it, Fixtures.gbp, Money(5), "gift") }),
        )
    }

    @Test
    fun interestMustMatchItsRecordedTerms() {
        val before = funded()
        assertEquals(emptyList(), violations(parent.interest(7), before))
        val wrong = parent.write(Fixtures.savings) {
            InterestAccrued(it, Fixtures.gbp, Money(500), Money(1_000), 500, 30)
        }
        assertEquals(listOf(Violation.InterestMismatch(Money(4), Money(500))), violations(wrong, before))
        val rounded = parent.write(Fixtures.savings) {
            InterestAccrued(it, Fixtures.gbp, InterestCalculator.accrue(Money(1_000), 500, 30), Money(1_000), 500, 30)
        }
        assertEquals(emptyList(), violations(rounded, before))
        val overflowing = parent.write(Fixtures.savings) {
            InterestAccrued(it, Fixtures.gbp, Money(1), Money(1_000_000_000_000), Int.MAX_VALUE, Int.MAX_VALUE)
        }
        assertEquals(listOf(Violation.Overflow), violations(overflowing, before))
        val hugePrincipal = parent.write(Fixtures.savings) {
            InterestAccrued(it, Fixtures.gbp, Money(1), Money(1_000_000_000_001), 1, 1)
        }
        assertTrue(
            Violation.AmountTooLarge(Money(1_000_000_000_001), LedgerPolicy.DEFAULT_MAX_AMOUNT) in
                violations(hugePrincipal, before),
        )
    }

    @Test
    fun amountsAreBoundedByThePolicy() {
        val max = LedgerPolicy.DEFAULT_MAX_AMOUNT
        assertEquals(emptyList(), violations(parent.chore(max.minorUnits)))
        assertEquals(
            listOf(Violation.AmountTooLarge(max + Money(1), max)),
            violations(parent.chore(max.minorUnits + 1)),
        )
        assertEquals(
            listOf(Violation.AmountTooLarge(Money(Long.MAX_VALUE), max)),
            violations(parent.adjust(Long.MAX_VALUE)),
        )
        assertEquals(
            listOf(Violation.AmountTooLarge(Money(Long.MIN_VALUE), max)),
            violations(parent.adjust(Long.MIN_VALUE)),
        )
        assertEquals(
            listOf(
                Violation.NonPositiveAmount(Money(Long.MIN_VALUE)),
                Violation.AmountTooLarge(Money(Long.MIN_VALUE), max),
            ),
            violations(kid.purchase(Long.MIN_VALUE)),
        )
        val tight = Projection.empty(Fixtures.accounts, LedgerPolicy(maxAmount = Money(10)))
        assertEquals(listOf(Violation.AmountTooLarge(Money(11), Money(10))), violations(parent.chore(11), tight))
    }

    @Test
    fun validationNeverThrowsAndReportsOverflow() {
        val permissive = LedgerPolicy(maxAmount = Money(Long.MAX_VALUE), dailyGameCap = Money(Long.MAX_VALUE))
        val rich = Projection.empty(Fixtures.accounts, permissive).apply(parent.chore(Long.MAX_VALUE))
        assertEquals(listOf(Violation.Overflow), violations(parent.chore(1), rich))
        // The daily cap is compared without adding, so a huge game is a cap violation, not an overflow.
        val capped = permissive.copy(dailyGameCap = Money(Long.MAX_VALUE - 1))
        val played = Projection.empty(Fixtures.accounts, capped)
            .apply(listOf(kid.game(Long.MAX_VALUE - 1), parent.adjust(-(Long.MAX_VALUE - 1))))
        assertEquals(Money.ZERO, played.balanceOf(Fixtures.main.id))
        assertEquals(
            listOf(
                Violation.DailyCapExceeded(Money(Long.MAX_VALUE - 1), Money(Long.MAX_VALUE - 1), Money(Long.MAX_VALUE)),
            ),
            violations(kid.game(Long.MAX_VALUE), played),
        )
    }

    @Test
    fun freeTextIsBoundedAndWellFormed() {
        val longest = "x".repeat(LedgerRules.MAX_TEXT_LENGTH)
        assertEquals(emptyList(), violations(parent.adjust(5, reason = longest)))
        assertEquals(
            listOf(Violation.TextTooLong("reason", LedgerRules.MAX_TEXT_LENGTH)),
            violations(parent.adjust(5, reason = longest + "x")),
        )
        // Paired surrogates (an emoji) are fine; an unpaired one cannot be encoded as UTF-8.
        assertEquals(emptyList(), violations(parent.adjust(5, reason = "plant \uD83C\uDF31")))
        // Such an event cannot even be hashed, so it is built directly rather than through the factory.
        val chore = parent.chore(1)
        for (bad in listOf("x\uD800", "\uDC00x", "a\uD800\uD800\uDC00", "\uDBFF")) {
            val event = ManualAdjustment(chore.envelope, Fixtures.gbp, Money(5), bad)
            assertEquals(listOf(Violation.InvalidText("reason")), violations(event), bad)
        }
        val note = OverdraftCompensated(chore.envelope, Fixtures.gbp, Money(5), listOf(chore.eventId), "x\uD800")
        assertEquals(listOf(Violation.InvalidText("note")), violations(note))
        val longNote =
            OverdraftCompensated(chore.envelope, Fixtures.gbp, Money(5), listOf(chore.eventId), longest + "x")
        assertEquals(listOf(Violation.TextTooLong("note", LedgerRules.MAX_TEXT_LENGTH)), violations(longNote))
        assertEquals(emptyList(), violations(note.copy(note = null)))
        val refs = listOf(
            ChoreRewarded(chore.envelope, Fixtures.gbp, Money(1), "x\uD800") to "choreId",
            TimeBankStarted(chore.envelope.copy(accountId = Fixtures.minutes.id), "x\uD800") to "sessionId",
        )
        for ((event, field) in refs) assertTrue(Violation.InvalidText(field) in violations(event), field)
    }

    @Test
    fun freeTextIsRedactedFromToString() {
        val chore = parent.chore(1)
        val adjustment = parent.adjust(5, reason = "bought Emma's birthday present")
        val compensation =
            OverdraftCompensated(chore.envelope, Fixtures.gbp, Money(5), listOf(chore.eventId), "secret note")
        assertFalse("Emma" in adjustment.toString())
        assertTrue("reason=<redacted>" in adjustment.toString())
        assertFalse("secret" in compensation.toString())
        assertTrue("note=<redacted>" in compensation.toString())
        assertTrue("note=null" in compensation.copy(note = null).toString())
        assertTrue("amount=" in adjustment.toString())
    }

    @Test
    fun aLamportTooFarAheadOfEverythingSeenIsRejected() {
        val before = funded(parent.chore(100))
        val maxJump = before.policy.maxLamportJump
        val near = DeviceWriter(Fixtures.kidTablet, lamport = LamportClock(start = before.maxLamport + maxJump - 1))
        assertEquals(emptyList(), violations(near.game(1), before))
        val far = DeviceWriter(Fixtures.kidTablet, lamport = LamportClock(start = Long.MAX_VALUE - 1)).game(1)
        assertEquals(
            listOf(
                Violation.LamportTooFarAhead(latest = before.maxLamport, actual = Long.MAX_VALUE, maxJump = maxJump),
            ),
            violations(far, before),
        )
        val tight = Projection.empty(Fixtures.accounts, LedgerPolicy(maxLamportJump = 5))
        assertEquals(
            emptyList(),
            violations(DeviceWriter(Fixtures.kidTablet, lamport = LamportClock(4)).game(1), tight),
        )
        assertIs<Violation.LamportTooFarAhead>(
            violations(DeviceWriter(Fixtures.kidTablet, lamport = LamportClock(5)).game(1), tight).single(),
        )
    }

    @Test
    fun compensationMustNameItsCause() {
        assertEquals(listOf(Violation.BlankText("causedBy")), violations(parent.compensate(5, emptyList())))
    }
}

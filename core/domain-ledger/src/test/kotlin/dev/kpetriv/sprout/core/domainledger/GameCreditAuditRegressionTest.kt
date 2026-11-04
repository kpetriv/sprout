package dev.kpetriv.sprout.core.domainledger

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Regressions from the M5 security audit's probes: a forged second cap in screen-time minutes (H1), the size of the
 * accepted backdating burst (I1), and an author that keeps out-of-window credit until it learns its own server stamps
 * (M1; the data layer backfills them, see `core/database` `GameAuditRegressionTest`).
 */
class GameCreditAuditRegressionTest {
    private val kid = DeviceWriter(Fixtures.kidTablet)

    private fun violations(event: LedgerEvent, before: Projection = Projection.empty(Fixtures.accounts)) =
        when (val result = LedgerRules.validate(event, before)) {
            ValidationResult.Valid -> emptyList()
            is ValidationResult.Invalid -> result.violations
        }

    /** A stamp on [Fixtures.today], 01:00 UTC, for every event. */
    private fun stampedToday(events: List<LedgerEvent>): Map<String, Instant> {
        val at = Instant.fromEpochSeconds(Fixtures.today.toEpochDay() * SECONDS_PER_DAY + SECONDS_PER_HOUR)
        return events.associate { it.envelope.hash to at }
    }

    @Test
    fun aGameNeverCreditsScreenTimeMinutes() {
        // A minutes play would get its own daily cap on top of the fiat one. Static, so a pulled one is quarantined
        // on ingest and a stored one is excluded from projection.
        val onMinutes = kid.game(5, account = Fixtures.minutes)
        assertEquals(listOf(Violation.GameCurrencyNotAllowed(Currency.Minutes)), violations(onMinutes))
        val generous = LedgerPolicy(dailyGameCapOverrides = mapOf(Currency.Minutes to Money(1_000)))
        assertEquals(
            listOf(Violation.GameCurrencyNotAllowed(Currency.Minutes)),
            violations(onMinutes, Projection.empty(Fixtures.accounts, generous)),
        )
        // Every other game rule is skipped: the currency alone decides.
        assertEquals(
            listOf(Violation.GameCurrencyNotAllowed(Currency.Minutes)),
            violations(kid.game(16, account = Fixtures.minutes, gameId = "chess")),
        )
        // A minutes play into the fiat account is a currency mismatch as well.
        val minutesIntoMain = kid.write(Fixtures.main) {
            GamePlayed(it, Currency.Minutes, Money(5), Fixtures.GAME_ID, Fixtures.GAME_SCORE, Fixtures.today)
        }
        assertEquals(
            setOf(
                Violation.GameCurrencyNotAllowed(Currency.Minutes),
                Violation.CurrencyMismatch(Fixtures.main.id, Fixtures.gbp, Currency.Minutes),
            ),
            violations(minutesIntoMain).toSet(),
        )
        assertEquals(emptyList(), violations(kid.game(5)))
    }

    @Test
    fun aForgedClientCannotCreditMinutesOnTopOfTheFiatCap() {
        val fiat = List(4) { kid.game(12, account = Fixtures.main) }
        val minutes = List(4) { kid.game(12, account = Fixtures.minutes) }
        val events = fiat + minutes
        // Refused locally, whatever the projection.
        val empty = Projection.empty(Fixtures.accounts, roleOf = Fixtures::roleOf)
        val validation = LedgerRules.validate(minutes.first(), empty)
        assertEquals(
            ValidationResult.Invalid(listOf(Violation.GameCurrencyNotAllowed(Currency.Minutes))),
            validation,
        )
        // Excluded by a receiver: the fiat plays count, the minutes plays are invalid events.
        val receiver = Ledger.project(
            events,
            Fixtures.accounts,
            LedgerPolicy(),
            Fixtures::roleOf,
            receivedAt = stampedToday(events),
        )
        assertEquals(Money(48), receiver.balanceOf(Fixtures.main.id))
        assertEquals(Money.ZERO, receiver.balanceOf(Fixtures.minutes.id))
        val invalid = receiver.anomalies.filterIsInstance<LedgerAnomaly.InvalidEvent>()
        assertEquals(minutes.map { it.eventId }.toSet(), invalid.map { it.eventId }.toSet())
        assertTrue(invalid.all { it.violations == listOf(Violation.GameCurrencyNotAllowed(Currency.Minutes)) })
        assertEquals(invalid.size, receiver.anomalies.size)
    }

    @Test
    fun theBackdatingBurstIsBoundedToNineDatesOfCap() {
        // The window holds D-7..D+1: nine dates, so eight days of cap beyond today's (THREAT_MODEL T12, ADR-0009).
        val inWindow = (-PlayedOnWindow.DAYS_BEHIND..PlayedOnWindow.DAYS_AHEAD).flatMap { offset ->
            List(4) { kid.game(12, date = Fixtures.today.plusDays(offset)) }
        }
        val outside = listOf(
            kid.game(12, date = Fixtures.today.plusDays(-PlayedOnWindow.DAYS_BEHIND - 1)),
            kid.game(12, date = Fixtures.today.plusDays(PlayedOnWindow.DAYS_AHEAD + 1)),
        )
        val events = inWindow + outside
        val receiver = Ledger.project(
            events,
            Fixtures.accounts,
            LedgerPolicy(),
            Fixtures::roleOf,
            receivedAt = stampedToday(events),
        )
        assertEquals(Money(9 * 48), receiver.balanceOf(Fixtures.main.id))
        assertEquals(
            outside.map { it.eventId },
            receiver.anomalies.filterIsInstance<LedgerAnomaly.PlayedOnOutOfRange>().map { it.eventId },
        )
    }

    @Test
    fun anAuthorConvergesOnceItHoldsItsOwnStamps() {
        // Plays on 30 far-past dates, each under that date's cap: without stamps (the author's local copies) every
        // one counts; with the stamps every receiver sees, none does.
        val events = (1..30).map { kid.game(15, date = Fixtures.today.plusDays(-100L - it)) }
        val stamps = stampedToday(events)
        val unstamped = Ledger.project(events, Fixtures.accounts, LedgerPolicy(), Fixtures::roleOf)
        val receiver = Ledger.project(events, Fixtures.accounts, LedgerPolicy(), Fixtures::roleOf, receivedAt = stamps)
        assertEquals(Money(450), unstamped.balanceOf(Fixtures.main.id))
        assertEquals(Money.ZERO, receiver.balanceOf(Fixtures.main.id))
        // Applying the same events with the stamps the author pulls back gives the receivers' answer.
        val backfilled = Projection.empty(Fixtures.accounts, roleOf = Fixtures::roleOf).apply(events, stamps)
        assertEquals(receiver.balanceOf(Fixtures.main.id), backfilled.balanceOf(Fixtures.main.id))
        assertIs<LedgerAnomaly.PlayedOnOutOfRange>(backfilled.anomalies.first())
    }

    private companion object {
        const val SECONDS_PER_DAY = 86_400L
        const val SECONDS_PER_HOUR = 3_600L
    }
}

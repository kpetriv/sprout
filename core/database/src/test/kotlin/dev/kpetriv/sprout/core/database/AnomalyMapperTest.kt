package dev.kpetriv.sprout.core.database

import dev.kpetriv.sprout.core.domainledger.AccountKind
import dev.kpetriv.sprout.core.domainledger.Currency
import dev.kpetriv.sprout.core.domainledger.DuplicateCopy
import dev.kpetriv.sprout.core.domainledger.EventId
import dev.kpetriv.sprout.core.domainledger.Fixtures
import dev.kpetriv.sprout.core.domainledger.LedgerAnomaly
import dev.kpetriv.sprout.core.domainledger.Money
import dev.kpetriv.sprout.core.domainledger.Violation
import dev.kpetriv.sprout.core.model.AccountId
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.Role
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class AnomalyMapperTest {
    private val eventA = EventId("00000000-0000-4000-8000-00000000000a")
    private val eventB = EventId("00000000-0000-4000-8000-00000000000b")
    private val account = AccountId("acc")

    /** One of every [Violation] type; keep in sync with the sealed interface (the `when` in ViolationCodec is). */
    private val everyViolation = listOf(
        Violation.NonPositiveAmount(Money(-1)),
        Violation.AmountTooLarge(Money(5), Money(4)),
        Violation.Overflow,
        Violation.ZeroAdjustment,
        Violation.BlankText("reason"),
        Violation.TextTooLong("note", 200),
        Violation.InvalidText("reason"),
        Violation.SameAccountTransfer,
        Violation.InvalidInterestTerms,
        Violation.InterestMismatch(Money(3), Money(4)),
        Violation.ParentOnly("ChoreRewarded", Role.KID),
        Violation.UnknownAccount(account),
        Violation.CurrencyMismatch(account, Currency.Fiat("GBP"), Currency.Minutes),
        Violation.KidMismatch(account, KidId("a"), KidId("b")),
        Violation.WrongAccountKind(account, AccountKind.MAIN, AccountKind.SAVINGS),
        Violation.DuplicateEvent(eventA),
        Violation.LamportTooFarAhead(latest = 1, actual = 9, maxJump = 2),
        Violation.InsufficientFunds(account, Money(1), Money(2)),
        Violation.DailyCapExceeded(Money(50), Money(40), Money(20)),
        Violation.TimeBankAlreadyRunning,
        Violation.TimeBankEmpty,
        Violation.TimeBankNotRunning,
    )

    @Test
    fun everyViolationRoundTrips() {
        everyViolation.forEach { assertEquals(it, ViolationCodec.decode(ViolationCodec.encode(it))) }
    }

    @Test
    fun everyAnomalyKindRoundTripsInOrder() {
        val anomalies = listOf(
            LedgerAnomaly.ConflictingDuplicate(
                eventB,
                listOf(
                    DuplicateCopy(
                        "a".repeat(64),
                        Fixtures.kid,
                        Fixtures.parentPhone.uid,
                        Role.PARENT,
                        Fixtures.parentPhone.deviceId,
                        3,
                    ),
                    DuplicateCopy(
                        "b".repeat(64),
                        KidId("kid-2"),
                        Fixtures.kidTablet.uid,
                        Role.KID,
                        Fixtures.kidTablet.deviceId,
                        1,
                    ),
                ),
            ),
            LedgerAnomaly.OverdraftDetected(account, Money(20), listOf(eventA, eventB)),
            LedgerAnomaly.DailyCapExceeded(Fixtures.kid, Fixtures.today, Currency.Minutes, eventA, Money(7)),
            LedgerAnomaly.InvalidEvent(eventB, everyViolation),
        )
        val rows = anomalies.mapIndexed { order, anomaly -> AnomalyMapper.toEntity(Fixtures.kid, anomaly, order, 0) }

        assertEquals(anomalies, AnomalyMapper.toDomain(rows.shuffled(kotlin.random.Random(1))))
        assertEquals(rows.size, rows.map { it.anomalyId }.toSet().size)
    }

    @Test
    fun anUnreadableRowIsSkippedNotThrown() {
        val good = AnomalyMapper.toEntity(
            Fixtures.kid,
            LedgerAnomaly.InvalidEvent(eventA, listOf(Violation.Overflow)),
            0,
            0,
        )
        val corrupt = good.copy(anomalyId = "x", detailsJson = "{\"order\":1,\"violations\":[{\"kind\":\"Nope\"}]}")
        assertEquals(
            listOf<LedgerAnomaly>(LedgerAnomaly.InvalidEvent(eventA, listOf(Violation.Overflow))),
            AnomalyMapper.toDomain(listOf(corrupt, good)),
        )
        assertFalse(AnomalyMapper.toDomain(listOf(good.copy(detailsJson = "nope"))).any())
    }
}

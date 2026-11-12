package dev.kpetriv.sprout.core.domainledger

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Times tables plays (M9-games §6.2) under the unchanged [LedgerRules]: `tables.sprout` and `tables.sapling` parse
 * because `GameKind` grew, a score is within 0..20, and the amount is at most what `GameCredit` re-derives (capped
 * at 15). The daily cap and played-on window are covered for every game by [LedgerRulesTest].
 */
class TimesTablesRulesTest {
    private val kid = DeviceWriter(Fixtures.kidTablet)

    private fun violations(event: LedgerEvent): List<Violation> =
        when (val result = LedgerRules.validate(event, Projection.empty(Fixtures.accounts))) {
            ValidationResult.Valid -> emptyList()
            is ValidationResult.Invalid -> result.violations
        }

    @Test
    fun aTimesTablesPlayIsAcceptedWithoutARulesChange() {
        // M9-games §6.2: 20 rounds; 20 of 20 earns 20 + 3 (+ 2 timed), capped at 15.
        assertEquals(emptyList(), violations(kid.game(15, gameId = "tables.sapling", score = 20)))
        assertEquals(emptyList(), violations(kid.game(15, gameId = "tables.sprout", score = 20)))
        assertEquals(emptyList(), violations(kid.game(14, gameId = "tables.sprout", score = 14)))
        assertEquals(
            listOf(Violation.GameCreditTooHigh(Money(15), Money(16))),
            violations(kid.game(16, gameId = "tables.sapling", score = 20)),
        )
        assertEquals(
            listOf(Violation.ScoreOutOfRange(21, 20)),
            violations(kid.game(1, gameId = "tables.sapling", score = 21)),
        )
        // Seedling has no Times tables, so its id does not parse.
        assertEquals(
            listOf(Violation.UnknownGame("tables.seedling")),
            violations(kid.game(1, gameId = "tables.seedling", score = 1)),
        )
    }

    @Test
    fun everyScoreOfATimesTablesPlayEarnsOnePerCorrectUpToTheCap() {
        for (gameId in listOf("tables.sprout", "tables.sapling")) {
            for (score in 0L..20L) {
                val earned = minOf(score, 15L)
                if (earned > 0) assertEquals(emptyList(), violations(kid.game(earned, gameId = gameId, score = score)))
                assertEquals(
                    listOf(Violation.GameCreditTooHigh(Money(earned), Money(earned + 1))),
                    violations(kid.game(earned + 1, gameId = gameId, score = score)),
                    "$gameId $score",
                )
            }
        }
    }
}

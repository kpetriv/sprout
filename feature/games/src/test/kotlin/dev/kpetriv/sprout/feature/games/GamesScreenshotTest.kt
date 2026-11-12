package dev.kpetriv.sprout.feature.games

import androidx.compose.runtime.Composable
import dev.kpetriv.sprout.core.model.KidUiTier
import dev.kpetriv.sprout.core.testing.screenshot.ScreenshotTest
import dev.kpetriv.sprout.core.testing.screenshot.ScreenshotWindow
import dev.kpetriv.sprout.core.ui.ReadAloud
import org.junit.Test

/**
 * Golden screenshots of the brain games, every tier (word scramble: Sprout and Sapling only, D-M5-6; Times tables:
 * Sprout and Sapling), in src/test/screenshots. The hub is a Morning garden screen; everything from the start card to
 * the result is on the Playground (M9-games). Every play screen and the result also has a landscape tablet
 * (`_tablet_`) and a portrait tablet (`_tablet_portrait_`) golden per tier (M9-games §5). Re-record with
 * `./gradlew :feature:games:recordRoborazziDebug`.
 */
class GamesScreenshotTest : ScreenshotTest(checkAccessibility = true) {
    @Test
    fun `hub, ready`() = screenshot.captureTiers("games_hub") { tier -> Hub(GamesPreviewData.hub(tier), tier) }

    @Test
    fun `hub on a tablet, landscape and portrait, every tier`() = screenshot.captureWidthClassTiers(
        name = "games_hub",
        windows = TABLETS,
    ) { _, tier -> Hub(GamesPreviewData.hub(tier), tier) }

    @Test
    fun `hub, cap reached`() = screenshot.captureTiers("games_hub_cap_reached") { tier ->
        Hub(GamesPreviewData.hubCapReached(tier), tier)
    }

    @Test
    fun `arithmetic round, choices or typed`() = screenshot.captureTiers("games_arithmetic") { tier ->
        Play(GamesPreviewData.arithmetic(tier))
    }

    @Test
    fun `arithmetic round on tablets`() = screenshot.captureWidthClassTiers("games_arithmetic", TABLETS) { _, tier ->
        Play(GamesPreviewData.arithmetic(tier))
    }

    @Test
    fun `start card, sapling with the timed switch`() = screenshot.captureTiers(
        "games_start_timed",
        tiers = listOf(KidUiTier.SAPLING),
    ) { _ -> Play(GamesPreviewData.startTimed()) }

    @Test
    fun `start card, sapling with the last plays`() = screenshot.captureTiers(
        "games_start_history",
        tiers = listOf(KidUiTier.SAPLING),
    ) { _ -> Play(GamesPreviewData.startHistory()) }

    @Test
    fun `sapling start card on tablets`() =
        screenshot.captureWidthClassTiers("games_start_timed", TABLETS, tiers = SAPLING) { _, _ ->
            Play(GamesPreviewData.startTimed())
        }

    @Test
    fun `sapling start card with the last plays on tablets`() =
        screenshot.captureWidthClassTiers("games_start_history", TABLETS, tiers = SAPLING) { _, _ ->
            Play(GamesPreviewData.startHistory())
        }

    @Test
    fun `timed round on tablets`() {
        screenshot.compose.mainClock.autoAdvance = false
        screenshot.captureWidthClassTiers("games_arithmetic_timed", TABLETS, tiers = SAPLING) { _, _ ->
            Play(GamesPreviewData.arithmeticTimed())
        }
    }

    @Test
    fun `times tables, rock star, on tablets`() {
        screenshot.compose.mainClock.autoAdvance = false
        screenshot.captureWidthClassTiers("games_tables_timed", TABLETS, tiers = SAPLING) { _, _ ->
            Play(GamesPreviewData.tablesTimed())
        }
    }

    @Test
    fun `timed round with its countdown ring`() {
        // Hold the clock so the ring is captured full rather than drained.
        screenshot.compose.mainClock.autoAdvance = false
        screenshot.captureTiers("games_arithmetic_timed", tiers = listOf(KidUiTier.SAPLING)) { _ ->
            Play(GamesPreviewData.arithmeticTimed())
        }
    }

    @Test
    fun `memory, sequence showing`() = screenshot.captureTiers("games_memory_showing") { tier ->
        Play(GamesPreviewData.memoryShowing(tier))
    }

    @Test
    fun `memory, sequence showing, on tablets`() =
        screenshot.captureWidthClassTiers("games_memory_showing", TABLETS) { _, tier ->
            Play(GamesPreviewData.memoryShowing(tier))
        }

    @Test
    fun `memory, answering`() = screenshot.captureTiers("games_memory_answering") { tier ->
        Play(GamesPreviewData.memoryAnswering(tier))
    }

    @Test
    fun `memory, answering, on tablets`() =
        screenshot.captureWidthClassTiers("games_memory_answering", TABLETS) { _, tier ->
            Play(GamesPreviewData.memoryAnswering(tier))
        }

    @Test
    fun `word round`() = screenshot.captureTiers("games_word", tiers = OLDER) { tier ->
        Play(GamesPreviewData.word(tier))
    }

    @Test
    fun `word round on tablets`() = screenshot.captureWidthClassTiers("games_word", TABLETS, tiers = OLDER) { _, tier ->
        Play(GamesPreviewData.word(tier))
    }

    @Test
    fun `times tables setup`() = screenshot.captureTiers("games_tables_setup", tiers = OLDER) { tier ->
        Play(GamesPreviewData.tablesStart(tier))
    }

    @Test
    fun `times tables setup on tablets`() =
        screenshot.captureWidthClassTiers("games_tables_setup", TABLETS, tiers = OLDER) { _, tier ->
            Play(GamesPreviewData.tablesStart(tier))
        }

    // A held clock does not recompose between the variants of one captureTiers call, so each tier of a held-clock
    // phone golden is its own test (the tablet captures launch a fresh window per variant).
    @Test
    fun `times tables question, sprout`() = tablesQuestion(KidUiTier.SPROUT)

    @Test
    fun `times tables question, sapling`() = tablesQuestion(KidUiTier.SAPLING)

    private fun tablesQuestion(tier: KidUiTier) {
        // Hold the clock so the informational ring is captured full.
        screenshot.compose.mainClock.autoAdvance = false
        screenshot.captureTiers("games_tables", tiers = listOf(tier)) { Play(GamesPreviewData.tables(it)) }
    }

    @Test
    fun `times tables question on tablets`() {
        screenshot.compose.mainClock.autoAdvance = false
        screenshot.captureWidthClassTiers("games_tables", TABLETS, tiers = OLDER) { _, tier ->
            Play(GamesPreviewData.tables(tier))
        }
    }

    @Test
    fun `times tables, rock star, timed`() {
        screenshot.compose.mainClock.autoAdvance = false
        screenshot.captureTiers("games_tables_timed", tiers = listOf(KidUiTier.SAPLING)) { _ ->
            Play(GamesPreviewData.tablesTimed())
        }
    }

    @Test
    fun `result, credited`() = screenshot.captureTiers("games_result_credited") { tier ->
        Play(GamesPreviewData.resultCredited(tier))
    }

    @Test
    fun `result, credited, on tablets`() =
        screenshot.captureWidthClassTiers("games_result_credited", TABLETS) { _, tier ->
            Play(GamesPreviewData.resultCredited(tier))
        }

    @Test
    fun `result, cap reached`() = screenshot.captureTiers("games_result_capped") { tier ->
        Play(GamesPreviewData.resultCapped(tier))
    }

    @Test
    fun `result, clamped to the cap`() = screenshot.captureTiers("games_result_clamped") { tier ->
        Play(GamesPreviewData.resultClamped(tier))
    }

    @Test
    fun `result, refused`() = screenshot.captureTiers("games_result_refused") { tier ->
        Play(GamesPreviewData.resultRefused(tier))
    }

    @Test
    fun `result, unknown after process death`() = screenshot.captureTiers("games_result_unknown") { tier ->
        Play(GamesPreviewData.resultUnknown(tier))
    }

    private companion object {
        val TABLETS = listOf(ScreenshotWindow.TABLET, ScreenshotWindow.TABLET_PORTRAIT)
        val OLDER = listOf(KidUiTier.SPROUT, KidUiTier.SAPLING)
        val SAPLING = listOf(KidUiTier.SAPLING)
    }
}

/** A play screen; the speech implementation shows Seedling's read-aloud tiles, which are hidden without one. */
@Composable
private fun Play(state: GamePlayUiState) {
    GamePlayScreen(state = state, onEvent = {}, onFinished = {}, readAloud = ReadAloud { })
}

/** The hub as the shell shows it: a tab root without a back arrow on Sprout and Sapling, pushed on Seedling. */
@Composable
private fun Hub(state: GamesHubUiState, tier: KidUiTier) {
    GamesHubScreen(
        state = state,
        onEvent = {},
        onPlay = {},
        onBack = if (tier == KidUiTier.SEEDLING) ({}) else null,
        readAloud = ReadAloud { },
    )
}

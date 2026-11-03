package dev.kpetriv.sprout.feature.games

import dev.kpetriv.sprout.core.model.KidUiTier
import dev.kpetriv.sprout.core.testing.screenshot.ScreenshotTest
import dev.kpetriv.sprout.core.ui.ReadAloud
import org.junit.Test

/**
 * Golden screenshots of the brain games, every tier (word scramble: Sprout and Sapling only, D-M5-6), in
 * src/test/screenshots. Re-record with `./gradlew :feature:games:recordRoborazziDebug`.
 */
class GamesScreenshotTest : ScreenshotTest() {
    @Test
    fun `hub, ready`() = screenshot.captureTiers("games_hub") { tier ->
        GamesHubScreen(state = GamesPreviewData.hub(tier), onEvent = {}, onPlay = {}, readAloud = speech)
    }

    @Test
    fun `hub, cap reached`() = screenshot.captureTiers("games_hub_cap_reached") { tier ->
        GamesHubScreen(state = GamesPreviewData.hubCapReached(tier), onEvent = {}, onPlay = {}, readAloud = speech)
    }

    @Test
    fun `arithmetic round, choices or typed`() = screenshot.captureTiers("games_arithmetic") { tier ->
        GamePlayScreen(state = GamesPreviewData.arithmetic(tier), onEvent = {}, onFinished = {}, readAloud = speech)
    }

    @Test
    fun `memory, sequence showing`() = screenshot.captureTiers("games_memory_showing") { tier ->
        GamePlayScreen(state = GamesPreviewData.memoryShowing(tier), onEvent = {}, onFinished = {}, readAloud = speech)
    }

    @Test
    fun `memory, answering`() = screenshot.captureTiers("games_memory_answering") { tier ->
        GamePlayScreen(
            state = GamesPreviewData.memoryAnswering(tier),
            onEvent = {},
            onFinished = {},
            readAloud = speech,
        )
    }

    @Test
    fun `word round`() = screenshot.captureTiers(
        "games_word",
        tiers = listOf(KidUiTier.SPROUT, KidUiTier.SAPLING),
    ) { tier ->
        GamePlayScreen(state = GamesPreviewData.word(tier), onEvent = {}, onFinished = {})
    }

    @Test
    fun `result, credited`() = screenshot.captureTiers("games_result_credited") { tier ->
        GamePlayScreen(state = GamesPreviewData.resultCredited(tier), onEvent = {}, onFinished = {})
    }

    @Test
    fun `result, cap reached`() = screenshot.captureTiers("games_result_capped") { tier ->
        GamePlayScreen(state = GamesPreviewData.resultCapped(tier), onEvent = {}, onFinished = {})
    }

    private companion object {
        // Shows Seedling's read-aloud buttons, which are hidden without a speech implementation.
        val speech = ReadAloud { }
    }
}

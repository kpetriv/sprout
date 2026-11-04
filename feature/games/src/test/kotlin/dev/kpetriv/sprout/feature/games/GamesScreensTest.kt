package dev.kpetriv.sprout.feature.games

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kpetriv.sprout.core.designsystem.theme.SproutTheme
import dev.kpetriv.sprout.core.domainledger.Money
import dev.kpetriv.sprout.core.games.ArithmeticRound
import dev.kpetriv.sprout.core.games.Difficulty
import dev.kpetriv.sprout.core.games.GameId
import dev.kpetriv.sprout.core.games.GameKind
import dev.kpetriv.sprout.core.games.GameSession
import dev.kpetriv.sprout.core.games.MemoryRound
import dev.kpetriv.sprout.core.model.KidUiTier
import dev.kpetriv.sprout.core.ui.ReadAloud
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The games screens under Robolectric: the play screen runs on a real [GameViewModel] (fixed seed, virtual time for
 * the memory sequence), the hub and result on fixed states. A tall window keeps every control composed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-rGB-w411dp-h2400dp")
class GamesScreensTest {
    @get:Rule(order = 0)
    val main = MainDispatcherRule()

    @get:Rule(order = 1)
    val compose = createComposeRule()

    private val spoken = mutableListOf<String>()
    private val readAloud = ReadAloud { spoken += it }

    private fun play(tier: KidUiTier, kind: GameKind): GameViewModel {
        val vm = GameViewModel(
            handle = TestData.handle(tier, kind),
            submitter = FakeCreditSubmitter(),
            seeds = FixedSeeds(SEED),
            currency = { TestData.gbp },
            timer = FakeGameTimer(),
            creditScope = creditScope(main.dispatcher),
        )
        compose.setContent {
            SproutTheme {
                val state by vm.state.collectAsStateWithLifecycle()
                GamePlayScreen(state = state, onEvent = vm::onEvent, onFinished = {}, readAloud = readAloud)
            }
        }
        compose.onNodeWithText("Start").performClick()
        return vm
    }

    /** A memory choice button (the sequence card may describe the same symbol). */
    private fun symbol(label: String) = compose.onNode(hasContentDescription(label) and hasClickAction())

    private fun session(tier: KidUiTier, kind: GameKind) =
        GameSession.create(GameId(kind, Difficulty.forTier(tier)), SEED)

    @Test
    fun `tapping the right choice advances to the next round`() {
        val vm = play(KidUiTier.SPROUT, GameKind.ARITHMETIC)
        val round = session(KidUiTier.SPROUT, GameKind.ARITHMETIC).rounds[0] as ArithmeticRound

        compose.onNodeWithContentDescription("Round 1 of 8").assertIsDisplayed()
        compose.onNodeWithText(round.prompt).assertIsDisplayed()
        compose.onNodeWithContentDescription(round.answer.toString()).performClick()

        compose.onNodeWithContentDescription("Round 2 of 8").assertIsDisplayed()
        assertEquals(2, assertIs<GamePlayUiState.Playing>(vm.state.value).roundNumber)
    }

    @Test
    fun `seedling hears each prompt when it appears and when tapped`() {
        play(KidUiTier.SEEDLING, GameKind.ARITHMETIC)
        val round = session(KidUiTier.SEEDLING, GameKind.ARITHMETIC).rounds[0] as ArithmeticRound
        compose.waitForIdle()
        val prompt = if (round.dots != null) round.spokenPrompt else "What is ${round.spokenPrompt}?"
        assertEquals(listOf(prompt), spoken)

        compose.onNodeWithContentDescription("Read this to me").performClick()
        assertEquals(listOf(prompt, prompt), spoken)
    }

    @Test
    fun `a wrong memory tap ends the round`() {
        val vm = play(KidUiTier.SPROUT, GameKind.MEMORY)
        val round = session(KidUiTier.SPROUT, GameKind.MEMORY).rounds[0] as MemoryRound

        compose.onNodeWithText("Watch carefully!").assertIsDisplayed()
        symbol(round.sequence[0].label).assertIsNotEnabled()
        main.dispatcher.scheduler.advanceTimeBy(GameViewModel.SYMBOL_SHOW_MILLIS * round.sequence.size + 1)
        compose.onNodeWithText("Now tap them in the same order").assertIsDisplayed()

        symbol(round.sequence[0].label).performClick()
        compose.onNodeWithContentDescription("1 of ${round.sequence.size} tapped").assert(politeLiveRegion)
        symbol(round.sequence[2].label).performClick()

        compose.onNodeWithContentDescription("Round 2 of 8").assertIsDisplayed()
        assertEquals(2, assertIs<GamePlayUiState.Playing>(vm.state.value).roundNumber)
    }

    @Test
    fun `the memory card announces the whole sequence once, when it hides`() {
        play(KidUiTier.SAPLING, GameKind.MEMORY)
        val round = session(KidUiTier.SAPLING, GameKind.MEMORY).rounds[0] as MemoryRound

        // While the symbols appear the card's description does not change, so nothing is announced every 700 ms.
        compose.onNodeWithContentDescription("Showing the order").assert(politeLiveRegion)
        main.dispatcher.scheduler.advanceTimeBy(GameViewModel.SYMBOL_SHOW_MILLIS * 2 + 1)
        compose.onNodeWithContentDescription("Showing the order").assertExists()
        main.dispatcher.scheduler.advanceTimeBy(GameViewModel.SYMBOL_SHOW_MILLIS * round.sequence.size)

        val sequence = round.sequence.joinToString(", ") { it.label }
        compose.onNodeWithContentDescription("The order was $sequence").assert(politeLiveRegion)
        compose.onNodeWithContentDescription("Showing the order").assertDoesNotExist()
    }

    @Test
    fun `counting dots are described by their number`() {
        val state = GamesPreviewData.arithmetic(KidUiTier.SEEDLING)
        val dots = checkNotNull((state.round as RoundUi.Arithmetic).round.dots)
        compose.setContent { SproutTheme { GamePlayScreen(state = state, onEvent = {}, onFinished = {}) } }

        compose.onNodeWithContentDescription(if (dots == 1) "1 dot" else "$dots dots").assertIsDisplayed()
    }

    @Test
    fun `back is disabled while the credit is saved`() {
        var state: GamePlayUiState by mutableStateOf(
            GamesPreviewData.resultCredited(KidUiTier.SPROUT).copy(credit = CreditUi.Submitting),
        )
        var backs = 0
        var dispatcher: OnBackPressedDispatcher? = null
        compose.setContent {
            dispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            SproutTheme { GamePlayScreen(state = state, onEvent = {}, onFinished = {}, onBack = { backs++ }) }
        }

        compose.onNodeWithContentDescription("Back").assertIsNotEnabled()
        compose.onNodeWithText("Done").assertIsNotEnabled()
        assertTrue(checkNotNull(dispatcher).hasEnabledCallbacks(), "system back is taken while saving")

        state = GamesPreviewData.resultCredited(KidUiTier.SPROUT)
        compose.onNodeWithContentDescription("Back").assertIsEnabled().performClick()
        assertEquals(1, backs)
        assertFalse(checkNotNull(dispatcher).hasEnabledCallbacks(), "system back goes to navigation again")
    }

    @Test
    fun `sapling types the answer and finishes with done on the keyboard`() {
        val vm = play(KidUiTier.SAPLING, GameKind.ARITHMETIC)
        val round = session(KidUiTier.SAPLING, GameKind.ARITHMETIC).rounds[0] as ArithmeticRound

        compose.onNode(hasSetTextAction()).performTextInput(round.answer.toString())
        compose.onNode(hasSetTextAction()).performImeAction()

        compose.onNodeWithContentDescription("Round 2 of 10").assertIsDisplayed()
        assertEquals(2, assertIs<GamePlayUiState.Playing>(vm.state.value).roundNumber)
    }

    @Test
    fun `hub tiles open a game and the cap reached copy shows`() {
        val played = mutableListOf<GameKind>()
        compose.setContent {
            SproutTheme {
                GamesHubScreen(
                    state = GamesPreviewData.hubCapReached(KidUiTier.SPROUT),
                    onEvent = {},
                    onPlay = { played += it },
                )
            }
        }

        compose.onNodeWithText("Come back tomorrow, you can still play for fun").assertIsDisplayed()
        compose.onNodeWithContentDescription("Play Word scramble").performClick()
        assertEquals(listOf(GameKind.WORDS), played)
    }

    @Test
    fun `seedling hub counts pennies and reads them aloud`() {
        compose.setContent {
            SproutTheme {
                GamesHubScreen(
                    state = GamesPreviewData.hub(KidUiTier.SEEDLING),
                    onEvent = {},
                    onPlay = {},
                    readAloud = readAloud,
                )
            }
        }

        compose.onNodeWithText("Word scramble").assertDoesNotExist()
        compose.onNodeWithContentDescription("Read this to me").performClick()
        assertEquals(listOf("You can still win 23 pennies today"), spoken)
    }

    @Test
    fun `result for fun once the cap was reached, and a refused credit offers a retry`() {
        val events = mutableListOf<GamePlayEvent>()
        var finished = false
        var state: GamePlayUiState by mutableStateOf(
            GamesPreviewData.resultCapped(KidUiTier.SPROUT),
        )
        compose.setContent {
            SproutTheme {
                GamePlayScreen(state = state, onEvent = { events += it }, onFinished = { finished = true })
            }
        }

        compose.onNodeWithText("You've earned all you can today. This one was for fun!").assertIsDisplayed()
        compose.onNodeWithText("Done").performClick()
        assertTrue(finished)

        state = GamesPreviewData.resultCredited(KidUiTier.SPROUT).copy(credit = CreditUi.Refused)
        compose.onNodeWithText("We couldn't save what you earned.").assertIsDisplayed()
        compose.onNodeWithText("Try again").performClick()
        compose.onNodeWithText("Play again").performClick()
        assertEquals(listOf(GamePlayEvent.RetryCredit, GamePlayEvent.PlayAgain), events)
    }

    @Test
    fun `credited result in pennies for seedling and money for sprout`() {
        var state: GamePlayUiState by mutableStateOf(
            GamesPreviewData.resultCredited(KidUiTier.SEEDLING),
        )
        compose.setContent { SproutTheme { GamePlayScreen(state = state, onEvent = {}, onFinished = {}) } }

        compose.onNodeWithText("You got 4 of 5!").assertIsDisplayed()
        compose.onNodeWithText("+4 pennies").assertIsDisplayed()

        state = GamesPreviewData.resultCredited(KidUiTier.SPROUT).copy(
            credit = CreditUi.Credited(Money(7), Money(15), clamped = false),
        )
        compose.onNodeWithText("You earned +£0.07").assertIsDisplayed()
        compose.onNodeWithText("You can still earn £0.15 today").assertIsDisplayed()
    }

    @Test
    fun `a play that earned nothing, a clamped one and a date refusal say so`() {
        val events = mutableListOf<GamePlayEvent>()
        var state: GamePlayUiState by mutableStateOf(
            GamesPreviewData.resultCredited(KidUiTier.SPROUT).copy(credit = CreditUi.NothingEarned),
        )
        compose.setContent {
            SproutTheme { GamePlayScreen(state = state, onEvent = { events += it }, onFinished = {}) }
        }

        compose.onNodeWithText("Nothing earned this time. This one was for fun!").assertIsDisplayed()
        compose.onNodeWithText("+£0.00", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Try again").assertDoesNotExist()

        state = GamesPreviewData.resultCredited(KidUiTier.SEEDLING).copy(credit = CreditUi.NothingEarned)
        compose.onNodeWithText("Nothing won this time. This one was for fun!").assertIsDisplayed()
        compose.onNodeWithText("pennies", substring = true).assertDoesNotExist()

        state = GamesPreviewData.resultClamped(KidUiTier.SPROUT)
        compose.onNodeWithText("You reached today's limit, so this game earned +£0.04").assertIsDisplayed()

        state = GamesPreviewData.resultCredited(KidUiTier.SPROUT).copy(credit = CreditUi.CheckDate)
        compose.onNodeWithText("Check this phone's date, then try again.").assertIsDisplayed()
        compose.onNodeWithText("Try again").performClick()
        assertEquals(listOf<GamePlayEvent>(GamePlayEvent.RetryCredit), events)
    }

    private companion object {
        const val SEED = 42L

        val politeLiveRegion = SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite)
    }
}

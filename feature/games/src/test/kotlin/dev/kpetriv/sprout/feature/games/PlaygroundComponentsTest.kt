package dev.kpetriv.sprout.feature.games

import android.app.Activity
import android.view.View
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import dev.kpetriv.sprout.core.designsystem.theme.Accent
import dev.kpetriv.sprout.core.designsystem.theme.PlaygroundColors
import dev.kpetriv.sprout.core.designsystem.theme.PlaygroundTokens
import dev.kpetriv.sprout.core.designsystem.theme.ProvideKidTier
import dev.kpetriv.sprout.core.designsystem.theme.SproutTheme
import dev.kpetriv.sprout.core.games.MemorySymbols
import dev.kpetriv.sprout.core.model.KidUiTier
import dev.kpetriv.sprout.feature.games.playground.CountdownRing
import dev.kpetriv.sprout.feature.games.playground.NumberPad
import dev.kpetriv.sprout.feature.games.playground.PictureGlyph
import dev.kpetriv.sprout.feature.games.playground.PlayField
import dev.kpetriv.sprout.feature.games.playground.PlayTile
import dev.kpetriv.sprout.feature.games.playground.PlaygroundTheme
import dev.kpetriv.sprout.feature.games.playground.SegmentProgress
import dev.kpetriv.sprout.feature.games.playground.SequencePanel
import dev.kpetriv.sprout.feature.games.playground.ShapeGlyph
import dev.kpetriv.sprout.feature.games.playground.playgroundColorScheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Behaviour and semantics of the Playground components (M9-games §2.2) that the goldens cannot show. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-rGB-w411dp-h914dp")
class PlaygroundComponentsTest {
    @get:Rule
    val compose = createComposeRule()

    private fun setContent(
        tier: KidUiTier = KidUiTier.SPROUT,
        reducedMotion: Boolean = true,
        content: @Composable () -> Unit,
    ) {
        compose.setContent {
            SproutTheme(reducedMotion = reducedMotion) {
                ProvideKidTier(tier) { PlaygroundTheme(tier, content = content) }
            }
        }
    }

    @Test
    fun `a tile is a button at least the tier's touch target, even when asked to be smaller`() {
        var clicks = 0
        setContent(KidUiTier.SEEDLING) {
            PlayTile(accent = Accent.Sky, onClick = { clicks++ }, modifier = Modifier.size(40.dp)) { Text("7") }
        }

        compose.onNodeWithText("7", useUnmergedTree = false)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .assertWidthIsAtLeast(72.dp)
            .assertHeightIsAtLeast(72.dp)
            .performClick()
        assertEquals(1, clicks)
    }

    @Test
    fun `a disabled tile does not click`() {
        var clicks = 0
        setContent { PlayTile(accent = Accent.Marigold, onClick = { clicks++ }, enabled = false) { Text("A") } }

        compose.onNodeWithText("A").assertIsNotEnabled().performClick()
        assertEquals(0, clicks)
    }

    @Test
    fun `the number pad sends digits, backspace and go`() {
        val events = mutableListOf<String>()
        setContent(KidUiTier.SAPLING) {
            NumberPad(onDigit = { events += "$it" }, onBackspace = { events += "back" }, onGo = { events += "go" })
        }

        compose.onNodeWithText("4").performClick()
        compose.onNodeWithText("0").performClick()
        compose.onNodeWithContentDescription("Delete last digit").performClick()
        compose.onNodeWithText("Go").performClick()
        assertEquals(listOf("4", "0", "back", "go"), events)
    }

    @Test
    fun `progress reads as the round`() {
        setContent { SegmentProgress(round = 3, total = 8) }

        compose.onNodeWithContentDescription("Round 3 of 8").assertExists()
    }

    @Test
    fun `the sequence panel keeps today's live region`() {
        val sequence = MemorySymbols.coloursAndShapes.take(3)
        setContent { SequencePanel(sequence = sequence, filled = emptyList(), showing = false, pictures = false) }

        compose.onNodeWithContentDescription("The order was red circle, green circle, blue circle")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
    }

    @Test
    fun `the play field's back tile goes back, and is disabled while saving`() {
        var backs = 0
        setContent { PlayField(title = "Numbers", onBack = { backs++ }, backEnabled = false) {} }

        compose.onNodeWithContentDescription("Back").assertIsNotEnabled().performClick()
        compose.onNodeWithText("Numbers").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        assertEquals(0, backs)
    }

    @Test
    fun `a pressed tile sinks by its edge and settles back on release`() {
        setContent(reducedMotion = false) { PlayTile(accent = Accent.Sky, onClick = {}) { Text("7") } }
        val label = compose.onNodeWithText("7", useUnmergedTree = true)
        val resting = label.fetchSemanticsNode().positionInRoot.y
        val edge = with(compose.density) { PlaygroundTokens.Sprout.tileEdge.toPx() }

        compose.onNodeWithText("7").performTouchInput { down(center) }
        compose.waitForIdle()
        assertEquals(resting + edge, label.fetchSemanticsNode().positionInRoot.y, absoluteTolerance = 0.5f)

        compose.onNodeWithText("7").performTouchInput { up() }
        compose.waitForIdle()
        assertEquals(resting, label.fetchSemanticsNode().positionInRoot.y, absoluteTolerance = 0.5f)
    }

    @Test
    fun `the countdown ring counts whole seconds and restarts with a new round`() {
        compose.mainClock.autoAdvance = false
        var roundKey by mutableStateOf(1L)
        setContent { CountdownRing(roundKey = roundKey, limitMs = 10_000L) }
        val ring = compose.onNodeWithContentDescription("Time left")
        fun seconds() = ring.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo]

        compose.mainClock.advanceTimeByFrame()
        assertEquals(ProgressBarRangeInfo(10f, 0f..10f), seconds())

        compose.mainClock.advanceTimeBy(10_500L)
        assertEquals(ProgressBarRangeInfo(0f, 0f..10f), seconds())

        // A few frames (80 ms) for the new key to recompose and the new clock to start: still 10 whole seconds.
        compose.runOnIdle { roundKey = 2L }
        repeat(5) {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
        }
        assertEquals(ProgressBarRangeInfo(10f, 0f..10f), seconds())
    }

    @Test
    fun `glyphs are described by their symbol's label unless the caller describes them`() {
        val circle = MemorySymbols.coloursAndShapes.first()
        val apple = MemorySymbols.pictures.first()
        val banana = MemorySymbols.pictures[1]
        setContent {
            Column {
                ShapeGlyph(symbol = circle, size = 48.dp)
                PictureGlyph(symbol = apple, size = 48.dp)
                PictureGlyph(symbol = banana, size = 48.dp, labelled = false)
            }
        }

        compose.onNodeWithContentDescription("red circle").assertExists()
        compose.onNodeWithContentDescription("apple").assertExists()
        compose.onNodeWithContentDescription("banana").assertDoesNotExist()
    }

    @Test
    fun `leaving the last play field restores the app's own bar icons`() {
        var fields by mutableStateOf(2)
        lateinit var view: View
        setContent {
            view = LocalView.current
            Column { repeat(fields) { PlayField(title = "Game $it", onBack = {}) {} } }
        }
        val controller = WindowCompat.getInsetsController((view.context as Activity).window, view)
        assertFalse(controller.isAppearanceLightStatusBars)

        // One of two overlapping fields leaves (a navigation transition): the other still owns the dark field's icons.
        compose.runOnIdle { fields = 1 }
        compose.waitForIdle()
        assertFalse(controller.isAppearanceLightStatusBars)
        assertFalse(controller.isAppearanceLightNavigationBars)

        // The last one leaves: the light theme's dark icons come back, whatever was saved.
        compose.runOnIdle { fields = 0 }
        compose.waitForIdle()
        assertTrue(controller.isAppearanceLightStatusBars)
        assertTrue(controller.isAppearanceLightNavigationBars)
    }

    @Test
    fun `the playground scheme's text roles are readable on their surfaces`() {
        val scheme = playgroundColorScheme(PlaygroundColors.Default)
        val pairs = mapOf(
            "onSurface on surface" to (scheme.onSurface to scheme.surface),
            "onSurfaceVariant on surfaceVariant" to (scheme.onSurfaceVariant to scheme.surfaceVariant),
            "onSurface on surfaceContainerLow (sheets)" to (scheme.onSurface to scheme.surfaceContainerLow),
            "onSurface on surfaceContainerLowest" to (scheme.onSurface to scheme.surfaceContainerLowest),
            "onSurface on surfaceContainerHighest" to (scheme.onSurface to scheme.surfaceContainerHighest),
            "inverseOnSurface on inverseSurface (snackbars)" to (scheme.inverseOnSurface to scheme.inverseSurface),
            "inversePrimary on inverseSurface (snackbar action)" to (scheme.inversePrimary to scheme.inverseSurface),
            "onPrimary on primary" to (scheme.onPrimary to scheme.primary),
        )
        pairs.forEach { (label, pair) ->
            val (lighter, darker) = listOf(pair.first.luminance(), pair.second.luminance()).sortedDescending()
            val ratio = (lighter + 0.05f) / (darker + 0.05f)
            assertTrue(ratio >= 4.5f, "$label contrast is $ratio")
        }
    }
}

package dev.kpetriv.sprout.feature.games

import dev.kpetriv.sprout.core.model.KidUiTier
import dev.kpetriv.sprout.core.testing.screenshot.ScreenshotTest
import dev.kpetriv.sprout.feature.games.playground.CountdownRingSample
import dev.kpetriv.sprout.feature.games.playground.NumberPadSample
import dev.kpetriv.sprout.feature.games.playground.PictureGlyphSample
import dev.kpetriv.sprout.feature.games.playground.PlayFieldSample
import dev.kpetriv.sprout.feature.games.playground.PlayTileSample
import dev.kpetriv.sprout.feature.games.playground.PlaygroundSampleFrame
import dev.kpetriv.sprout.feature.games.playground.PlaygroundTheme
import dev.kpetriv.sprout.feature.games.playground.ReadAloudTileSample
import dev.kpetriv.sprout.feature.games.playground.RewardCardSample
import dev.kpetriv.sprout.feature.games.playground.RosetteSample
import dev.kpetriv.sprout.feature.games.playground.SegmentProgressSample
import dev.kpetriv.sprout.feature.games.playground.SequencePanelSample
import dev.kpetriv.sprout.feature.games.playground.ShapeGlyphSample
import dev.kpetriv.sprout.feature.games.playground.StreakPillSample
import org.junit.Test

/**
 * Per-tier goldens of the Playground components (M9-games §2.2, `playground_<component>_<tier>`), each on the field
 * inside `PlaygroundTheme`, with the accessibility rule on every variant. Re-record with
 * `./gradlew :feature:games:recordRoborazziDebug`.
 */
class PlaygroundScreenshotTest : ScreenshotTest(checkAccessibility = true) {
    @Test
    fun `play field fills the window`() = screenshot.captureTiers("playground_play_field") { tier ->
        PlaygroundTheme(tier) { PlayFieldSample() }
    }

    @Test
    fun `tile set, every accent, disabled and outline`() = screenshot.captureTiers("playground_tiles") { tier ->
        PlaygroundSampleFrame(tier) { PlayTileSample() }
    }

    @Test
    fun `segment progress`() = screenshot.captureTiers("playground_segment_progress") { tier ->
        PlaygroundSampleFrame(tier) { SegmentProgressSample() }
    }

    @Test
    fun `countdown ring, held at the start and part-way`() {
        // Hold the clock so the running ring is captured full (10 s left); the second ring is drawn at 4.5 s.
        screenshot.compose.mainClock.autoAdvance = false
        screenshot.captureTiers("playground_countdown_ring") { tier ->
            PlaygroundSampleFrame(tier) { CountdownRingSample() }
        }
    }

    @Test
    fun `sequence panel, showing and answering`() = screenshot.captureTiers("playground_sequence_panel") { tier ->
        PlaygroundSampleFrame(tier) { SequencePanelSample(pictures = tier == KidUiTier.SEEDLING) }
    }

    @Test
    fun `sequence panel at font scale 2, badges grow instead of clipping`() =
        screenshot.captureFontScaleTiers("playground_sequence_panel") { tier ->
            PlaygroundSampleFrame(tier) { SequencePanelSample(pictures = tier == KidUiTier.SEEDLING) }
        }

    @Test
    fun `shape glyphs, all twelve`() = screenshot.captureTiers("playground_shape_glyphs") { tier ->
        PlaygroundSampleFrame(tier) { ShapeGlyphSample() }
    }

    @Test
    fun `picture glyphs, all twelve`() = screenshot.captureTiers("playground_picture_glyphs") { tier ->
        PlaygroundSampleFrame(tier) { PictureGlyphSample() }
    }

    @Test
    fun `reward card, with the cap line and Seedling's amount only`() =
        screenshot.captureTiers("playground_reward_card") { tier ->
            PlaygroundSampleFrame(tier) { RewardCardSample() }
        }

    @Test
    fun `rosette, perfect and not`() = screenshot.captureTiers("playground_rosette") { tier ->
        PlaygroundSampleFrame(tier) { RosetteSample() }
    }

    @Test
    fun `streak pill`() = screenshot.captureTiers("playground_streak_pill") { tier ->
        PlaygroundSampleFrame(tier) { StreakPillSample() }
    }

    @Test
    fun `number pad`() = screenshot.captureTiers("playground_number_pad") { tier ->
        PlaygroundSampleFrame(tier) { NumberPadSample() }
    }

    @Test
    fun `read-aloud tile`() = screenshot.captureTiers("playground_read_aloud_tile") { tier ->
        PlaygroundSampleFrame(tier) { ReadAloudTileSample() }
    }
}

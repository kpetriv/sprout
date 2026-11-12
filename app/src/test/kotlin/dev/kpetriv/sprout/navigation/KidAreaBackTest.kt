package dev.kpetriv.sprout.navigation

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import dev.kpetriv.sprout.core.designsystem.theme.SproutTheme
import dev.kpetriv.sprout.core.games.GameKind
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.KidUiTier
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Kid mode on a parent's phone is left only through the parent gate (IA-22, M9 shell): through the real
 * [KidAreaContent] and kid shell (placeholder screens, no Hilt), every way back out at the root asks for the parent
 * ([KidAreaContent]'s `onOpenExitGate`, which shows `KidModeExitGate`) exactly once and never finishes the activity.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class) // Not the Hilt app: its startup opens the encrypted database.
class KidAreaBackTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val leo = KidId("kid-leo")
    private lateinit var navigation: TopLevelBackStack
    private var tier by mutableStateOf<KidUiTier?>(KidUiTier.SPROUT)
    private var exiting by mutableStateOf(false)
    private var gateOpened = 0

    private val placeholders = KidEntries { navigation, _, _, back ->
        this.navigation = navigation
        entryProvider {
            entry<KidHomeKey> {
                // The wallet's own back arrow, a top-level arrow until slice B removes it.
                Button(onClick = back) { Text("Wallet screen") }
            }
            entry<KidChoresKey> { Text("Chores screen") }
            entry<ShopKey> { Text("Shop screen") }
            entry<KidGamesKey> { Text("Games hub") }
            entry<ChatThreadKey> { Text("Chat screen") }
            entry<TimeBankKey> { Text("Time bank") }
            entry<GamePlayKey> { Text("Game in play") }
        }
    }

    private fun show() {
        compose.setContent {
            SproutTheme {
                KidAreaContent(
                    kidId = leo,
                    tier = tier,
                    linkedDevice = false,
                    chatAvailable = true,
                    exiting = exiting,
                    onOpenExitGate = { gateOpened += 1 },
                    entries = placeholders,
                )
            }
        }
    }

    private fun pressBack() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    private fun push(key: NavKey) {
        compose.runOnUiThread { navigation.push(key) }
        compose.waitForIdle()
    }

    @Test
    fun `back at the start asks for the parent once and does not finish`() {
        show()

        pressBack()

        assertEquals(1, gateOpened)
        assertFalse(compose.activity.isFinishing)
    }

    @Test
    fun `the wallet's back arrow at the start asks for the parent once`() {
        show()

        compose.onNodeWithText("Wallet screen").performClick()

        assertEquals(1, gateOpened)
        assertFalse(compose.activity.isFinishing)
    }

    @Test
    fun `after a push and a tab switch, repeated back ends in exactly one gate and never a finish`() {
        show()
        push(TimeBankKey(leo))
        compose.onNodeWithText("Games").performClick()
        push(GamePlayKey(leo, KidUiTier.SPROUT, GameKind.entries.first()))

        // The game, then the hub (back to the wallet tab's time bank), then the time bank.
        repeat(3) { pressBack() }
        assertEquals(0, gateOpened)
        compose.onNodeWithText("Wallet screen").assertIsDisplayed()

        pressBack()
        assertEquals(1, gateOpened)
        assertFalse(compose.activity.isFinishing)
    }

    @Test
    fun `reselecting a tab pops it to its root, then back returns to the first tab and asks for the parent`() {
        show()
        compose.onNodeWithText("Games").performClick()
        push(GamePlayKey(leo, KidUiTier.SPROUT, GameKind.entries.first()))
        compose.onNodeWithText("Game in play").assertIsDisplayed()
        // The bar is hidden in a game; select the tab as the bar would once back on the hub.
        compose.runOnUiThread { navigation.select(KidGamesKey(leo, KidUiTier.SPROUT)) }
        compose.waitForIdle()
        compose.onNodeWithText("Games hub").assertIsDisplayed()

        compose.onNodeWithText("Wallet").performClick()
        push(TimeBankKey(leo))
        compose.onNodeWithText("Wallet").performClick()
        compose.onNodeWithText("Wallet screen").assertIsDisplayed()

        pressBack()

        assertEquals(1, gateOpened)
        assertFalse(compose.activity.isFinishing)
    }

    @Test
    fun `while the tier is still unknown back asks for the parent`() {
        tier = null
        show()

        pressBack()

        assertEquals(1, gateOpened)
        assertFalse(compose.activity.isFinishing)
    }

    @Test
    fun `with the gate already up the handler is off`() {
        exiting = true
        show()
        compose.waitForIdle()

        assertFalse(compose.activity.onBackPressedDispatcher.hasEnabledCallbacks())

        tier = null
        compose.waitForIdle()
        assertFalse(compose.activity.onBackPressedDispatcher.hasEnabledCallbacks())
        assertEquals(0, gateOpened)
    }

    @Test
    fun `a tier change rebuilds the shell in the new tier`() {
        show()
        compose.onNodeWithText("Games").assertIsDisplayed()

        tier = KidUiTier.SEEDLING
        compose.waitForIdle()

        compose.onNodeWithText("Games").assertDoesNotExist()
        compose.onNodeWithText("Wallet screen").assertIsDisplayed()
        assertEquals(3, navigation.tabs.size)
    }
}

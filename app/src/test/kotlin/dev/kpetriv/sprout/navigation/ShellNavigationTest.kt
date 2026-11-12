package dev.kpetriv.sprout.navigation

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import dev.kpetriv.sprout.core.designsystem.theme.SproutTheme
import dev.kpetriv.sprout.core.games.GameKind
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.KidUiTier
import dev.kpetriv.sprout.core.ui.HideNavigationWhile
import dev.kpetriv.sprout.feature.wallet.WalletMode
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The shell's bar, its `NavDisplay` and the system back together (M9 §5.2, D-M9-10, D-M9-11), through the real
 * [KidAreaContent] and kid shell on a kid's linked phone (back at the root finishes the activity; the parent's-phone
 * path is `KidAreaBackTest`) and the parent tabs: one back stack per tab, back pops within the tab, then returns to the
 * first tab with its stack intact, then leaves. The screens are labelled placeholders, so no Hilt.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class) // Not the Hilt app: its startup opens the encrypted database.
class ShellNavigationTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val leo = KidId("kid-leo")
    private val game = GameKind.entries.first()
    private lateinit var navigation: TopLevelBackStack
    private var timerRunning by mutableStateOf(false)
    private var chatAvailable by mutableStateOf(true)

    private val placeholders = KidEntries { navigation, _, _, _ ->
        this.navigation = navigation
        entryProvider {
            entry<KidHomeKey> { Text("Wallet screen") }
            entry<KidChoresKey> { Text("Chores screen") }
            entry<ShopKey> { Text("Shop screen") }
            entry<KidGamesKey> { Text("Games hub") }
            entry<ChatThreadKey> { Text("Chat screen") }
            entry<TimeBankKey> {
                HideNavigationWhile(timerRunning)
                Text("Time bank")
            }
            entry<GamePlayKey> { Text("Game in play") }
        }
    }

    private fun showKidShell() {
        compose.setContent {
            SproutTheme {
                KidAreaContent(
                    kidId = leo,
                    tier = KidUiTier.SPROUT,
                    linkedDevice = true,
                    chatAvailable = chatAvailable,
                    exiting = false,
                    onOpenExitGate = { error("a kid's own phone has no exit gate") },
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
    fun `the bar shows the tier's tabs with the first selected`() {
        showKidShell()

        listOf("Wallet", "Chores", "Shop", "Games", "Chat").forEach { compose.onNodeWithText(it).assertIsDisplayed() }
        compose.onNodeWithText("Wallet").assertIsSelected()
        compose.onNodeWithText("Wallet screen").assertIsDisplayed()
    }

    @Test
    fun `push in tab A, switch to B, back pops B, returns to A intact, then leaves`() {
        showKidShell()
        push(TimeBankKey(leo))
        compose.onNodeWithText("Time bank").assertIsDisplayed()

        compose.onNodeWithText("Games").performClick()
        compose.onNodeWithText("Games hub").assertIsDisplayed()
        push(GamePlayKey(leo, KidUiTier.SPROUT, game))
        compose.onNodeWithText("Game in play").assertIsDisplayed()

        pressBack()
        compose.onNodeWithText("Games hub").assertIsDisplayed()

        pressBack()
        compose.onNodeWithText("Time bank").assertIsDisplayed()
        compose.onNodeWithText("Wallet").assertIsSelected()
        assertEquals(listOf(KidHomeKey(leo), TimeBankKey(leo)), navigation.current.toList())

        pressBack()
        compose.onNodeWithText("Wallet screen").assertIsDisplayed()
        assertTrue(navigation.atStart)
        assertFalse(compose.activity.isFinishing)

        pressBack()
        assertTrue(compose.activity.isFinishing, "back at the first tab's root leaves a kid's own phone")
    }

    @Test
    fun `switching tabs keeps each tab's pushed screens`() {
        showKidShell()
        push(TimeBankKey(leo))
        compose.onNodeWithText("Shop").performClick()
        compose.onNodeWithText("Shop screen").assertIsDisplayed()

        compose.onNodeWithText("Wallet").performClick()

        compose.onNodeWithText("Time bank").assertIsDisplayed()
    }

    @Test
    fun `the bar hides inside a game and comes back after it`() {
        showKidShell()
        compose.onNodeWithText("Games").performClick()
        push(GamePlayKey(leo, KidUiTier.SPROUT, game))

        compose.onNodeWithText("Shop").assertDoesNotExist()

        pressBack()
        compose.onNodeWithText("Shop").assertIsDisplayed()
    }

    // D-M9-18: what ships is the bar hidden while the time bank screen shows a running timer; back still leaves it.
    @Test
    fun `the bar is hidden while the time bank screen shows a running timer, and back still leaves it`() {
        showKidShell()
        push(TimeBankKey(leo))
        compose.onNodeWithText("Shop").assertIsDisplayed()

        timerRunning = true
        compose.waitForIdle()
        compose.onNodeWithText("Shop").assertDoesNotExist()

        timerRunning = false
        compose.waitForIdle()
        compose.onNodeWithText("Shop").assertIsDisplayed()

        timerRunning = true
        compose.waitForIdle()
        compose.onNodeWithText("Shop").assertDoesNotExist()
        pressBack()
        compose.onNodeWithText("Wallet screen").assertIsDisplayed()
        compose.onNodeWithText("Shop").assertIsDisplayed()
    }

    @Test
    fun `a tab's root key opened from a screen selects that tab`() {
        showKidShell()

        push(ShopKey(leo, WalletMode.KID))

        compose.onNodeWithText("Shop").assertIsSelected()
        assertEquals(listOf<NavKey>(KidHomeKey(leo)), navigation.stackOf(KidHomeKey(leo)).toList())
    }

    @Test
    fun `chat going away while on the last tab shows the wallet, and chat coming back selects it again`() {
        showKidShell()
        compose.onNodeWithText("Chat").performClick()
        compose.onNodeWithText("Chat screen").assertIsDisplayed()

        chatAvailable = false
        compose.waitForIdle()
        compose.onNodeWithText("Chat").assertDoesNotExist()
        compose.onNodeWithText("Wallet").assertIsSelected()
        compose.onNodeWithText("Wallet screen").assertIsDisplayed()

        chatAvailable = true
        compose.waitForIdle()
        compose.onNodeWithText("Chat").assertIsSelected()
        compose.onNodeWithText("Chat screen").assertIsDisplayed()
    }

    @Test
    fun `chat coming available keeps the selected tab by key`() {
        chatAvailable = false
        showKidShell()
        compose.onNodeWithText("Games").performClick()

        chatAvailable = true
        compose.waitForIdle()

        compose.onNodeWithText("Games").assertIsSelected()
        compose.onNodeWithText("Games hub").assertIsDisplayed()
    }

    @Test
    fun `the same screen in two tabs is two entries with their own state`() {
        compose.setContent {
            SproutTheme {
                val tabs = parentTabs(chatAvailable = true)
                val roots = tabs.map { it.root }
                val nav = rememberTopLevelBackStack(roots).also { navigation = it }
                SproutShell(tabs, nav.selectedIndexIn(roots), onSelect = { nav.select(roots[it]) }) {
                    ShellNavDisplay(
                        navigation = nav,
                        decorators = rememberTabDecorators(nav.tabs),
                        entryProvider = entryProvider {
                            entry<ParentHomeKey> { Text("Dashboard") }
                            entry<ChoresKey> { Text("Chore list") }
                            entry<ChatThreadsKey> { Text("Inbox") }
                            entry<SettingsKey> { Text("Settings hub") }
                            entry<ApprovalsKey> {
                                var taps by rememberSaveable { mutableIntStateOf(0) }
                                Button(onClick = { taps += 1 }) { Text("Approvals $taps") }
                            }
                        },
                    )
                }
            }
        }
        push(ApprovalsKey)
        compose.onNodeWithText("Approvals 0").performClick()
        compose.onNodeWithText("Approvals 1").performClick()

        compose.onNodeWithText("Chores").performClick()
        push(ApprovalsKey)
        compose.onNodeWithText("Approvals 0").assertIsDisplayed().performClick()
        compose.onAllNodes(hasText("Approvals 2")).assertCountEquals(0)

        compose.onNodeWithText("Home").performClick()
        compose.onNodeWithText("Approvals 2").assertIsDisplayed()
        compose.onAllNodes(hasText("Approvals 1")).assertCountEquals(0)

        compose.onNodeWithText("Chores").performClick()
        compose.onNodeWithText("Approvals 1").assertIsDisplayed()
        assertEquals(listOf(ChoresKey, ApprovalsKey), navigation.current.toList())
        assertEquals(listOf(ParentHomeKey, ApprovalsKey), navigation.stackOf(ParentHomeKey).toList())
    }
}

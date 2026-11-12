package dev.kpetriv.sprout.navigation

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.kpetriv.sprout.core.model.KidId
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/**
 * The per-tab stacks and the selected tab survive process death, when the tab list arrives shorter first (chat not
 * yet known to be available) and grows a moment later (M9 review, item 2).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class) // Not the Hilt app: its startup opens the encrypted database.
class ShellStateRestorationTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val leo = KidId("kid-leo")
    private lateinit var navigation: TopLevelBackStack

    // A plain field, so flipping it before the save changes what the restored composition sees, not what is saved.
    private var chatAvailable = true
    private var recompose by mutableIntStateOf(0)

    @Test
    fun `stacks and the selected tab are restored by key while the tabs grow`() {
        val tester = StateRestorationTester(compose)
        tester.setContent {
            recompose.let { }
            val nav = rememberTopLevelBackStack(parentTabs(chatAvailable).map { it.root })
            navigation = nav
            Text("Selected ${nav.selectedIndex}")
        }
        compose.runOnUiThread {
            navigation.push(ApprovalsKey)
            navigation.select(SettingsKey)
            navigation.push(SecurityKey)
            navigation.select(ChatThreadsKey)
            navigation.openDetail(ChatThreadKey(leo))
        }
        compose.waitForIdle()

        chatAvailable = false
        tester.emulateSavedInstanceStateRestore()

        assertEquals(parentTabs(chatAvailable = false).map { it.root }, navigation.tabs)
        assertEquals(ParentHomeKey, navigation.selectedTab, "the first tab shows until chat is back")
        assertEquals(listOf(ParentHomeKey, ApprovalsKey), navigation.stackOf(ParentHomeKey).toList())
        assertEquals(listOf(SettingsKey, SecurityKey), navigation.stackOf(SettingsKey).toList())

        chatAvailable = true
        recompose += 1
        compose.waitForIdle()

        assertEquals(ChatThreadsKey, navigation.selectedTab)
        assertEquals(listOf(ChatThreadsKey, ChatThreadKey(leo)), navigation.current.toList())
        assertEquals(listOf(SettingsKey, SecurityKey), navigation.stackOf(SettingsKey).toList())
    }
}

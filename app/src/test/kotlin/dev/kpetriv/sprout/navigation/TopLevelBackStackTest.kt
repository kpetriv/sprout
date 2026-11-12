package dev.kpetriv.sprout.navigation

import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import dev.kpetriv.sprout.core.model.ChoreId
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.KidUiTier
import dev.kpetriv.sprout.feature.wallet.WalletMode
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** One back stack per tab (M9 §5.2, D-M9-11), as plain state. */
class TopLevelBackStackTest {
    private val leo = KidId("kid-leo")
    private val parentTabs = listOf(ParentHomeKey, ChoresKey, ChatThreadsKey, SettingsKey)

    private fun navigation(tabs: List<NavKey> = parentTabs) = TopLevelBackStack(tabs)

    private val TopLevelBackStack.shown: List<NavKey> get() = visibleTabs.flatMap { stackOf(it) }

    @Test
    fun `starts on the first tab with every tab at its root`() {
        val navigation = navigation()

        assertEquals(0, navigation.selectedIndex)
        assertEquals(ParentHomeKey, navigation.top)
        assertTrue(navigation.atStart)
        assertEquals(listOf<NavKey>(ParentHomeKey), navigation.shown)
        parentTabs.forEach { tab -> assertEquals(listOf(tab), navigation.stackOf(tab).toList()) }
    }

    @Test
    fun `push in tab A, switch to B, back pops B, then returns to A with its stack intact, then leaves`() {
        val navigation = navigation()
        val wallet = WalletKey(leo, WalletMode.PARENT)
        navigation.push(wallet)
        navigation.push(ApprovalsKey)

        navigation.select(ChoresKey)
        val editor = ChoreEditorKey(ChoreId("chore-1"))
        navigation.push(editor)
        assertEquals(listOf(ParentHomeKey, wallet, ApprovalsKey, ChoresKey, editor), navigation.shown)

        assertTrue(navigation.back())
        assertEquals(ChoresKey, navigation.top)
        assertEquals(1, navigation.selectedIndex)

        assertTrue(navigation.back())
        assertEquals(0, navigation.selectedIndex)
        assertEquals(listOf(ParentHomeKey, wallet, ApprovalsKey), navigation.current.toList())

        assertTrue(navigation.back())
        assertTrue(navigation.back())
        assertTrue(navigation.atStart)
        assertFalse(navigation.back(), "back at the first tab's root leaves the mode")
        assertEquals(listOf<NavKey>(ParentHomeKey), navigation.shown)
    }

    @Test
    fun `switching tabs keeps every tab's pushed screens`() {
        val navigation = navigation()
        navigation.push(ApprovalsKey)
        navigation.select(SettingsKey)
        navigation.push(SecurityKey)
        navigation.select(ParentHomeKey)

        assertEquals(listOf(ParentHomeKey, ApprovalsKey), navigation.current.toList())
        navigation.select(SettingsKey)
        assertEquals(listOf(SettingsKey, SecurityKey), navigation.current.toList())
        assertEquals(listOf(ParentHomeKey, ApprovalsKey, SettingsKey, SecurityKey), navigation.shown)
    }

    @Test
    fun `reselecting the selected tab returns it to its root`() {
        val navigation = navigation()
        navigation.select(ChatThreadsKey)
        navigation.openDetail(ChatThreadKey(leo))

        navigation.select(ChatThreadsKey)

        assertEquals(listOf<NavKey>(ChatThreadsKey), navigation.current.toList())
        assertEquals(2, navigation.selectedIndex)
    }

    @Test
    fun `pushing a tab's root key selects that tab instead of stacking it`() {
        val navigation = navigation()
        navigation.push(WalletKey(leo, WalletMode.PARENT))

        navigation.push(ChoresKey)

        assertEquals(ChoresKey, navigation.selectedTab)
        val home = navigation.stackOf(ParentHomeKey).toList()
        assertEquals(listOf(ParentHomeKey, WalletKey(leo, WalletMode.PARENT)), home)
        assertEquals(listOf<NavKey>(ChoresKey), navigation.current.toList())
    }

    @Test
    fun `a double push does not stack the same screen twice`() {
        val navigation = navigation()
        navigation.push(ApprovalsKey)
        navigation.push(ApprovalsKey)

        assertEquals(listOf(ParentHomeKey, ApprovalsKey), navigation.current.toList())
    }

    @Test
    fun `popIf closes only the screen on top and never a root`() {
        val navigation = navigation()
        navigation.push(SecurityKey)
        navigation.popIf(ApprovalsKey)
        assertEquals(SecurityKey, navigation.top)

        navigation.popIf(SecurityKey)
        navigation.popIf(SecurityKey)
        navigation.popIf(ParentHomeKey)
        assertEquals(listOf<NavKey>(ParentHomeKey), navigation.current.toList())
        assertFalse(navigation.pop())
    }

    @Test
    fun `openDetail replaces the detail already open, never the list`() {
        val navigation = navigation()
        navigation.select(ChoresKey)
        navigation.openDetail(ChoreEditorKey())
        navigation.openDetail(ChoreEditorKey(ChoreId("chore-2")))
        navigation.openDetail(ChoreEditorKey(ChoreId("chore-2")))

        assertEquals(listOf(ChoresKey, ChoreEditorKey(ChoreId("chore-2"))), navigation.current.toList())
    }

    @Test
    fun `seedling's chat is pushed onto the wallet tab because it is not a tab there`() {
        val seedling = TopLevelBackStack(kidTabs(leo, KidUiTier.SEEDLING, chatAvailable = true).map { it.root })
        seedling.push(ChatThreadKey(leo))

        assertEquals(0, seedling.selectedIndex)
        assertEquals(listOf(KidHomeKey(leo), ChatThreadKey(leo)), seedling.current.toList())
    }

    @Test
    fun `an update keeps the selected tab and the stacks of the tabs that stay`() {
        val stacks = parentTabs.associateWith { NavBackStack(it) }
        val navigation = TopLevelBackStack(parentTabs, stacks)
        navigation.select(SettingsKey)
        navigation.push(SecurityKey)

        val withoutChat = parentTabs - ChatThreadsKey
        navigation.update(withoutChat, withoutChat.associateWith { stacks.getValue(it) })

        assertEquals(withoutChat, navigation.tabs)
        assertEquals(SettingsKey, navigation.selectedTab)
        assertSame(stacks.getValue(SettingsKey), navigation.current)
        assertEquals(listOf(SettingsKey, SecurityKey), navigation.current.toList())
    }

    @Test
    fun `an update that drops the selected tab shows the first, and the selection comes back with its tab`() {
        val stacks = parentTabs.associateWith { NavBackStack(it) }
        val navigation = TopLevelBackStack(parentTabs, stacks)
        navigation.select(ChatThreadsKey)
        navigation.openDetail(ChatThreadKey(leo))
        val withoutChat = parentTabs - ChatThreadsKey

        navigation.update(withoutChat, withoutChat.associateWith { stacks.getValue(it) })
        assertEquals(ParentHomeKey, navigation.selectedTab)
        assertEquals(0, navigation.selectedIndexIn(withoutChat))

        navigation.update(parentTabs, stacks)
        assertEquals(ChatThreadsKey, navigation.selectedTab)
        assertEquals(listOf(ChatThreadsKey, ChatThreadKey(leo)), navigation.current.toList())
    }

    @Test
    fun `navigating while the selected tab is gone settles on the tab shown`() {
        val stacks = parentTabs.associateWith { NavBackStack(it) }
        val navigation = TopLevelBackStack(parentTabs, stacks)
        navigation.select(ChatThreadsKey)
        val withoutChat = parentTabs - ChatThreadsKey
        navigation.update(withoutChat, withoutChat.associateWith { stacks.getValue(it) })

        navigation.push(ApprovalsKey)
        navigation.update(parentTabs, stacks)

        assertEquals(ParentHomeKey, navigation.selectedTab)
        assertEquals(listOf(ParentHomeKey, ApprovalsKey), navigation.current.toList())
    }

    @Test
    fun `the selected index can be read against a newer tab list`() {
        val navigation = navigation(parentTabs - ChatThreadsKey)
        navigation.select(SettingsKey)

        assertEquals(2, navigation.selectedIndex)
        assertEquals(3, navigation.selectedIndexIn(parentTabs))
    }

    @Test
    fun `selecting a tab not seen yet resolves once it arrives`() {
        val withoutChat = parentTabs - ChatThreadsKey
        val stacks = parentTabs.associateWith { NavBackStack(it) }
        val navigation = TopLevelBackStack(withoutChat, withoutChat.associateWith { stacks.getValue(it) })

        navigation.select(ChatThreadsKey)
        assertEquals(ParentHomeKey, navigation.selectedTab)
        navigation.update(parentTabs, stacks)

        assertEquals(ChatThreadsKey, navigation.selectedTab)
    }
}

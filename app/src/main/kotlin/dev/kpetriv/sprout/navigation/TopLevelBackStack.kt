package dev.kpetriv.sprout.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSerializable
import androidx.compose.runtime.setValue
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.serialization.NavKeySerializer

/**
 * One back stack per bottom-bar destination (M9 §5.2, D-M9-11), the Navigation 3 samples' multiple-back-stacks
 * pattern. [tabs] are the destinations' root keys, left to right; the first is the start destination. Each tab keeps
 * its own [NavBackStack] (rooted at its key, never emptied), so switching tabs keeps every tab's pushed screens.
 *
 * Back ([back]) pops within the selected tab, then returns to the first tab (with its stack as it was), then reports
 * that there is nothing left so the caller can leave the mode. [visibleTabs] is what the shell hands `NavDisplay`: the
 * first tab's stack followed by the selected tab's, so `NavDisplay` sees exactly where back goes (and predictive back
 * previews it).
 *
 * The selection is stored as the selected tab's root key, not an index: after process death the tabs can arrive in
 * a shorter list first (chat not known to be available yet) and grow a moment later. While the selected key is not
 * among [tabs] the first tab shows, and the key is kept, so the selection comes back when its tab does; any
 * navigation in the meantime settles on what is shown.
 *
 * Plain state (snapshot lists and states), so it is unit tested without Compose; [rememberTopLevelBackStack] keeps
 * the stacks and the selection in saved state.
 */
@Stable
internal class TopLevelBackStack(
    tabs: List<NavKey>,
    stacks: Map<NavKey, NavBackStack<NavKey>> = tabs.associateWith { NavBackStack(it) },
    private val selection: MutableState<NavKey> = mutableStateOf(tabs.first()),
) {
    /** The destinations' root keys, left to right. */
    var tabs: List<NavKey> by mutableStateOf(tabs)
        private set

    private var stacks: Map<NavKey, NavBackStack<NavKey>> by mutableStateOf(stacks)

    init {
        validate(tabs, stacks)
    }

    /**
     * Replaces the destinations (chat became available or went away). The selection is kept by key: the selected tab
     * stays selected when it is still there, and comes back if it returns before anything else is opened. A tab in
     * both lists keeps its stack when [stacks] passes the same one.
     */
    fun update(tabs: List<NavKey>, stacks: Map<NavKey, NavBackStack<NavKey>>) {
        validate(tabs, stacks)
        this.tabs = tabs
        this.stacks = stacks
    }

    /** The selected tab's index in [tabs]: the first tab while the selected key is not among them. */
    val selectedIndex: Int get() = selectedIndexIn(tabs)

    /**
     * The selected tab's index in [tabs] as the caller has them, for a bar composed with a newer tab list than this
     * back stack has seen yet ([update] runs after composition).
     */
    fun selectedIndexIn(tabs: List<NavKey>): Int = tabs.indexOf(selection.value).coerceAtLeast(0)

    /** The selected tab's root key. */
    val selectedTab: NavKey get() = tabs[selectedIndex]

    /** The selected tab's stack. */
    val current: NavBackStack<NavKey> get() = stacks.getValue(selectedTab)

    /** The screen on top of the selected tab. */
    val top: NavKey get() = current.last()

    /** On the first tab with nothing pushed: back from here leaves the mode. */
    val atStart: Boolean get() = selectedIndex == 0 && current.size == 1

    /** The tabs whose stacks `NavDisplay` shows, bottom first: the first tab, then the selected one if different. */
    val visibleTabs: List<NavKey> get() = if (selectedIndex == 0) listOf(tabs[0]) else listOf(tabs[0], selectedTab)

    /** [tab]'s stack. */
    fun stackOf(tab: NavKey): NavBackStack<NavKey> = stacks.getValue(tabs[indexOf(tab)])

    /**
     * Selects the tab at [index]. Selecting the tab already selected returns it to its root (the usual bottom-bar
     * reselect); any other tab keeps its stack.
     */
    fun select(index: Int) {
        require(index in tabs.indices) { "No tab $index" }
        if (index == selectedIndex) {
            settle()
            current.popToRoot()
        } else {
            selection.value = tabs[index]
        }
    }

    /**
     * Selects [tab] as [select] does. A tab this back stack has not seen yet (a bar composed with a newer tab list,
     * [update] still to run) is remembered as the selection and resolves once it arrives.
     */
    fun select(tab: NavKey) {
        val index = tabs.indexOf(tab)
        if (index >= 0) select(index) else selection.value = tab
    }

    /**
     * Opens [key]: a tab's root key selects that tab (keeping its stack); any other key is pushed onto the selected
     * tab unless it is already on top (a double tap must not stack the same screen twice).
     */
    fun push(key: NavKey) {
        settle()
        when {
            key in tabs -> selection.value = key
            current.last() != key -> current.add(key)
        }
    }

    /** Closes [key] if it is on top of the selected tab (above its root); a no-op otherwise. */
    fun popIf(key: NavKey) {
        settle()
        if (current.size > 1 && current.last() == key) current.removeAt(current.lastIndex)
    }

    /** Pops the selected tab's top screen, never its root. False when the tab was at its root already. */
    fun pop(): Boolean {
        settle()
        if (current.size <= 1) return false
        current.removeAt(current.lastIndex)
        return true
    }

    /**
     * Opens [detail] beside its list in the selected tab: it replaces the detail already open (another thread, another
     * chore), so back from a list-detail screen returns to the list rather than walking through every item opened.
     */
    fun openDetail(detail: NavKey) {
        settle()
        val top = current.last()
        when {
            top == detail -> Unit
            current.size > 1 && top::class == detail::class -> current[current.lastIndex] = detail
            else -> current.add(detail)
        }
    }

    /**
     * The system back (D-M9-11): pops within the selected tab; at a tab's root returns to the first tab; at the first
     * tab's root returns false, meaning back should leave the mode.
     */
    fun back(): Boolean = when {
        pop() -> true

        selectedIndex != 0 -> {
            selection.value = tabs[0]
            true
        }

        else -> false
    }

    /** Makes the tab on screen the selected one when the selected key is not among [tabs] (see the class docs). */
    private fun settle() {
        if (selection.value !in tabs) selection.value = tabs[0]
    }

    private fun validate(tabs: List<NavKey>, stacks: Map<NavKey, NavBackStack<NavKey>>) {
        require(tabs.isNotEmpty()) { "A shell needs at least one tab" }
        require(tabs.toSet().size == tabs.size) { "Tabs must be distinct" }
        tabs.forEach { tab ->
            val stack = requireNotNull(stacks[tab]) { "No back stack for $tab" }
            if (stack.isEmpty()) stack.add(tab)
        }
    }

    private fun indexOf(tab: NavKey): Int = tabs.indexOf(tab).also { require(it >= 0) { "$tab is not a tab" } }

    private fun NavBackStack<NavKey>.popToRoot() {
        while (size > 1) removeAt(lastIndex)
    }
}

/**
 * A [TopLevelBackStack] for [tabs] whose stacks and selection survive configuration changes and process death. Each
 * tab's stack is remembered under its root key, so a tab that stays when [tabs] changes (chat becoming available)
 * keeps its stack. The instance itself stays the same when [tabs] changes ([TopLevelBackStack.update]), so callbacks
 * that captured it keep working.
 */
@Composable
internal fun rememberTopLevelBackStack(tabs: List<NavKey>): TopLevelBackStack {
    val stacks = tabs.associateWith { tab -> key(tab) { rememberNavBackStack(tab) } }
    val selection = rememberSerializable(stateSerializer = NavKeySerializer()) { mutableStateOf(tabs.first()) }
    val navigation = remember { TopLevelBackStack(tabs, stacks, selection) }
    if (navigation.tabs != tabs) {
        SideEffect { navigation.update(tabs, stacks) }
    }
    return navigation
}

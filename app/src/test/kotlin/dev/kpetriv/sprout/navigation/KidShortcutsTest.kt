package dev.kpetriv.sprout.navigation

import dev.kpetriv.sprout.R
import dev.kpetriv.sprout.core.model.KidUiTier
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Seedling's giant shortcut pills under the wallet (M9 §5.1, D-M9-10). */
class KidShortcutsTest {
    private fun kinds(tier: KidUiTier, chat: Boolean) =
        kidShortcuts(tier, chat, onChat = {}, onScreenTime = {}, onGames = {}).map { it.kind }

    @Test
    fun `seedling gets chat and games on top and screen time under them`() {
        assertEquals(
            listOf(ShortcutKind.CHAT, ShortcutKind.GAMES, ShortcutKind.SCREEN_TIME),
            kinds(KidUiTier.SEEDLING, chat = true),
        )
    }

    @Test
    fun `seedling without chat gets games and screen time`() {
        assertEquals(listOf(ShortcutKind.GAMES, ShortcutKind.SCREEN_TIME), kinds(KidUiTier.SEEDLING, chat = false))
    }

    @Test
    fun `sprout and sapling get none`() {
        assertTrue(kinds(KidUiTier.SPROUT, chat = true).isEmpty())
        assertTrue(kinds(KidUiTier.SAPLING, chat = true).isEmpty())
    }

    @Test
    fun `each shortcut opens its own screen`() {
        val opened = mutableListOf<ShortcutKind>()
        kidShortcuts(
            KidUiTier.SEEDLING,
            chat = true,
            onChat = { opened += ShortcutKind.CHAT },
            onScreenTime = { opened += ShortcutKind.SCREEN_TIME },
            onGames = { opened += ShortcutKind.GAMES },
        ).forEach { it.onClick() }
        assertEquals(listOf(ShortcutKind.CHAT, ShortcutKind.GAMES, ShortcutKind.SCREEN_TIME), opened)
    }

    @Test
    fun `labels are the plain-text home strings`() {
        assertEquals(
            listOf(R.string.home_chat, R.string.home_games, R.string.home_screen_time),
            ShortcutKind.entries.map { it.label },
        )
    }
}

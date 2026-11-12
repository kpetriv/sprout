package dev.kpetriv.sprout.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * The app shell's chrome as a screen sees it (M9 §5.2, D-M9-10): the bottom bar or rail around the screen. A feature
 * screen asks for it to be hidden through [HideNavigationWhile] and never reaches the shell directly.
 */
interface ShellChrome {
    /**
     * Asks the shell to hide ([hidden] true) its navigation, or withdraws that request ([hidden] false). Every
     * `true` is followed by exactly one `false` from the same caller, so a shell can count the open requests.
     */
    fun setNavigationHidden(hidden: Boolean)
}

/** The shell's chrome; a no-op outside a shell (feature screenshot tests, previews). */
val LocalShellChrome = staticCompositionLocalOf<ShellChrome> { NoShellChrome }

private object NoShellChrome : ShellChrome {
    override fun setNavigationHidden(hidden: Boolean) = Unit
}

/**
 * Hides the shell's bottom bar (or rail) while [hidden] is true and this is composed, so a child cannot tab away
 * mid-session without stopping (the time bank's running timer). The request is withdrawn when [hidden] turns false
 * or the caller leaves the composition.
 */
@Composable
fun HideNavigationWhile(hidden: Boolean) {
    val chrome = LocalShellChrome.current
    if (hidden) {
        DisposableEffect(chrome) {
            chrome.setNavigationHidden(true)
            onDispose { chrome.setNavigationHidden(false) }
        }
    }
}

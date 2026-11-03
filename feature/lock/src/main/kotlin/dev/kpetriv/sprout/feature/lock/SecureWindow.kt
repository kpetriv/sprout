package dev.kpetriv.sprout.feature.lock

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.Window
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import java.util.WeakHashMap

/**
 * Sets `FLAG_SECURE` on the window this composable is in (the activity's, or a Compose dialog's) while it is in
 * composition, so lock and parent screens never show up in screenshots, screen recordings or the recents
 * thumbnail. Nested uses are counted per window: the flag is cleared only when the last one leaves.
 */
@Composable
fun SecureWindow() {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = (view.parent as? DialogWindowProvider)?.window ?: view.context.findActivity()?.window
        window?.let(SecureFlagCounter::acquire)
        onDispose { window?.let(SecureFlagCounter::release) }
    }
}

/** Per-window reference count of [SecureWindow] uses. Main thread only, like the composition that drives it. */
internal object SecureFlagCounter {
    private val counts = WeakHashMap<Window, Int>()

    fun acquire(window: Window) {
        val count = counts[window] ?: 0
        if (count == 0) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        counts[window] = count + 1
    }

    fun release(window: Window) {
        val count = (counts[window] ?: return) - 1
        if (count <= 0) {
            counts.remove(window)
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            counts[window] = count
        }
    }
}

private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current != null) {
        if (current is Activity) return current
        current = (current as? ContextWrapper)?.baseContext
    }
    return null
}

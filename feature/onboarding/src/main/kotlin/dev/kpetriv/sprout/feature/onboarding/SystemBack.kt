package dev.kpetriv.sprout.feature.onboarding

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.OnBackPressedCallback
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext

/**
 * Calls [onBack] for the system back gesture while [enabled], through the activity's back dispatcher (no extra
 * dependency); does nothing outside an [OnBackPressedDispatcherOwner].
 */
@Composable
internal fun SystemBackHandler(enabled: Boolean, onBack: () -> Unit) {
    val context = LocalContext.current
    val currentOnBack by rememberUpdatedState(onBack)
    val callback = remember {
        object : OnBackPressedCallback(enabled) {
            override fun handleOnBackPressed() = currentOnBack()
        }
    }
    callback.isEnabled = enabled
    DisposableEffect(context, callback) {
        context.findBackOwner()?.onBackPressedDispatcher?.addCallback(callback)
        onDispose { callback.remove() }
    }
}

private tailrec fun Context.findBackOwner(): OnBackPressedDispatcherOwner? = when (this) {
    is OnBackPressedDispatcherOwner -> this
    is ContextWrapper -> baseContext.findBackOwner()
    else -> null
}

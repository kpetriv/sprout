package dev.kpetriv.sprout.feature.lock

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/** Whether any of the app's activities is visible. [ParentSession] auto-locks on the transitions. */
interface AppForegroundSource {
    /** True while the app is started (in the foreground), false otherwise. Emits the current value first. */
    val isInForeground: Flow<Boolean>
}

/**
 * [AppForegroundSource] over [ProcessLifecycleOwner]: started when the first activity starts, stopped (after
 * the owner's short debounce for configuration changes) when the last one stops. A `BiometricPrompt` only pauses
 * the activity, so prompting does not count as leaving.
 */
class ProcessLifecycleForegroundSource @Inject constructor() : AppForegroundSource {
    override val isInForeground: Flow<Boolean>
        get() = ProcessLifecycleOwner.get().lifecycle.currentStateFlow
            .map { it.isAtLeast(Lifecycle.State.STARTED) }
            .distinctUntilChanged()
}

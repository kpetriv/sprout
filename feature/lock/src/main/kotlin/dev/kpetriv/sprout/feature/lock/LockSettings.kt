package dev.kpetriv.sprout.feature.lock

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Parent-lock preferences. Changed from the parent's settings screen (behind the lock). */
interface LockSettings {
    /** Lock the parent session as soon as the app leaves the foreground. Default on. */
    val lockWhenLeaving: StateFlow<Boolean>

    fun setLockWhenLeaving(enabled: Boolean)
}

/**
 * [LockSettings] in app-private `SharedPreferences`. Not secret: changing it needs the parent session, and a kid
 * who could edit app-private files could also delete the PIN file. Read lazily so construction does no disk I/O.
 */
class SharedPreferencesLockSettings(context: Context) : LockSettings {
    private val appContext = context.applicationContext
    private val preferences by lazy { appContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE) }
    private val lockWhenLeavingFlow by lazy {
        MutableStateFlow(preferences.getBoolean(KEY_LOCK_WHEN_LEAVING, DEFAULT_LOCK_WHEN_LEAVING))
    }

    override val lockWhenLeaving: StateFlow<Boolean> get() = lockWhenLeavingFlow.asStateFlow()

    override fun setLockWhenLeaving(enabled: Boolean) {
        preferences.edit { putBoolean(KEY_LOCK_WHEN_LEAVING, enabled) }
        lockWhenLeavingFlow.value = enabled
    }

    private companion object {
        const val FILE_NAME = "sprout_lock_settings"
        const val KEY_LOCK_WHEN_LEAVING = "lock_when_leaving"
        const val DEFAULT_LOCK_WHEN_LEAVING = true
    }
}

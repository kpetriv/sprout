package dev.kpetriv.sprout.sync

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Plain (non-secret) app-shell flags that must survive a wipe, which deletes the database and the `SecretStore`
 * blobs but not shared preferences. Holds no personal data.
 */
interface ShellPreferences {
    /**
     * True once this device has belonged to a real household. Debug builds seed the demo trial household only on a
     * device that never did, so a wipe or a sign-out lands on onboarding instead of a fresh trial (M3 §6).
     */
    fun wasLinked(): Boolean

    fun markLinked()
}

@Singleton
class SharedShellPreferences @Inject constructor(@param:ApplicationContext private val context: Context) :
    ShellPreferences {
    private val preferences by lazy { context.getSharedPreferences(FILE, Context.MODE_PRIVATE) }

    override fun wasLinked(): Boolean = preferences.getBoolean(LINKED, false)

    override fun markLinked() {
        if (!wasLinked()) preferences.edit { putBoolean(LINKED, true) }
    }

    private companion object {
        const val FILE = "sprout_shell"
        const val LINKED = "linked_once"
    }
}

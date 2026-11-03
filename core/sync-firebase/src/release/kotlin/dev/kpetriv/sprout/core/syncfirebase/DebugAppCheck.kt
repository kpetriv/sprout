package dev.kpetriv.sprout.core.syncfirebase

import com.google.firebase.appcheck.AppCheckProviderFactory

/** Release builds never contain the debug App Check provider (D-M3-6). */
internal object DebugAppCheck {
    fun factoryOrNull(): AppCheckProviderFactory? = null
}

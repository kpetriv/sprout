package dev.kpetriv.sprout.core.syncfirebase

import com.google.firebase.appcheck.AppCheckProviderFactory
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory

/** Debug builds: the debug App Check provider (its token is registered in the console per device, D-M3-6). */
internal object DebugAppCheck {
    fun factoryOrNull(): AppCheckProviderFactory? = DebugAppCheckProviderFactory.getInstance()
}

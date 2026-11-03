package dev.kpetriv.sprout.core.syncfirebase

import android.content.Context
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Installs the App Check provider (D-M3-6): Play Integrity, or the debug provider when [FirebaseConfig.debugAppCheck]
 * is set. The debug provider only exists in debug builds of this module (`src/debug`); a release build asked for it
 * falls back to Play Integrity. Idempotent: only the first [install] does anything.
 */
@Singleton
class AppCheckInstaller @Inject constructor(
    private val config: FirebaseConfig,
    private val bootstrap: FirebaseBootstrap,
    @param:ApplicationContext private val context: Context,
) {
    private val installed = AtomicBoolean(false)

    fun install() {
        if (!installed.compareAndSet(false, true)) return
        val factory = (if (config.debugAppCheck) DebugAppCheck.factoryOrNull() else null)
            ?: PlayIntegrityAppCheckProviderFactory.getInstance()
        FirebaseAppCheck.getInstance(bootstrap.ensureInitialised(context)).installAppCheckProviderFactory(factory)
    }
}

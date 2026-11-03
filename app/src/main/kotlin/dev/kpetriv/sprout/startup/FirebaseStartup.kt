package dev.kpetriv.sprout.startup

import dev.kpetriv.sprout.core.syncfirebase.AppCheckInstaller
import javax.inject.Inject

/**
 * Initialises the default Firebase app (emulator or real project, `FirebaseBootstrap`) and installs App Check (M3 §5,
 * D-M3-6): [AppCheckInstaller.install] does both, idempotently.
 */
class FirebaseStartup @Inject constructor(private val appCheck: AppCheckInstaller) {
    fun initialise() {
        appCheck.install()
    }
}

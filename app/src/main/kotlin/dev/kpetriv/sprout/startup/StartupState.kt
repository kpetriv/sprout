package dev.kpetriv.sprout.startup

import kotlinx.coroutines.flow.StateFlow

/** Progress of the one-time process-start work ([AppInitializer]). */
sealed interface StartupState {
    data object Running : StartupState

    data object Ready : StartupState

    /** The work failed; [kind] says roughly where, without any detail that could carry personal data. */
    data class Failed(val kind: StartupFailure) : StartupState
}

/** Where startup failed, coarse enough to show to a user. */
enum class StartupFailure {
    /** A Keystore-backed key or the database secret could not be read. */
    KEYS,

    /** The database could not be opened or written. */
    DATABASE,

    /** Anything else. */
    UNKNOWN,
}

/** Read side of [AppInitializer] for screens: its [state] and a way to try again after a failure. */
interface StartupStatus {
    val state: StateFlow<StartupState>

    /** Runs the startup work again if it [StartupState.Failed]; does nothing otherwise. */
    fun retry()
}

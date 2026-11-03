package dev.kpetriv.sprout.sync

import dagger.Lazy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Holds sync off while the "Sign in again" gate or the delete confirmation's re-authentication is busy (audit M-1). A
 * sign-in on the gate may replace the Firebase user with another account before its uid can be compared; no sync may
 * run with that account's credentials, or read its claims as evidence about this household, in between.
 *
 * [pausing] marks sync paused (the [SyncController] loop stops, [SyncController.syncNow] and the background worker
 * skip), then runs its block while no sync runs at all ([SyncRunner.exclusive]: it waits for a sync in flight to
 * finish, and none starts until the block returns), and resumes afterwards, whatever the block did. Pauses nest.
 */
@Singleton
class SyncPause internal constructor(private val exclusion: Exclusion) {
    @Inject
    constructor(runner: Lazy<SyncRunner>) : this(
        object : Exclusion {
            override suspend fun <T> exclusive(block: suspend () -> T): T = runner.get().exclusive(block)
        },
    )

    /** What [pausing] holds while its block runs; [SyncRunner.exclusive] in the app. */
    internal interface Exclusion {
        suspend fun <T> exclusive(block: suspend () -> T): T
    }

    private val pauses = MutableStateFlow(0)

    /** True while paused; emits on every change (the current state first). */
    val paused: Flow<Boolean> = pauses.map { it > 0 }.distinctUntilChanged()

    /** True while paused. */
    val isPaused: Boolean get() = pauses.value > 0

    /** Runs [block] with sync paused and no sync running; resumes afterwards. */
    suspend fun <T> pausing(block: suspend () -> T): T {
        pauses.update { it + 1 }
        try {
            return exclusion.exclusive(block)
        } finally {
            pauses.update { it - 1 }
        }
    }
}

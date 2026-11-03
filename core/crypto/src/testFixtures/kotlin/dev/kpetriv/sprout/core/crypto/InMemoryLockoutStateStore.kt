package dev.kpetriv.sprout.core.crypto

/**
 * [LockoutStateStore] in memory. Test fixture only; production binds [EncryptedLockoutStateStore]. Set
 * [unreadable] to simulate a tampered or undecryptable stored state.
 */
class InMemoryLockoutStateStore(initial: PinLockoutState? = null) : LockoutStateStore {
    @Volatile
    var state: PinLockoutState? = initial

    @Volatile
    var unreadable: Boolean = false

    /** Number of [write] calls, for asserting that a read-only check did or did not persist. */
    @Volatile
    var writes: Int = 0
        private set

    override suspend fun read(): PinLockoutState? {
        if (unreadable) throw LockoutStateUnavailableException("Simulated unreadable lockout state")
        return state
    }

    override suspend fun write(state: PinLockoutState) {
        this.state = state
        unreadable = false
        writes++
    }
}

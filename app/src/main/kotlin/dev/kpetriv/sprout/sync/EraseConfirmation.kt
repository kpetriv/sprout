package dev.kpetriv.sprout.sync

import dev.kpetriv.sprout.core.database.ChatIdentityKeeper
import dev.kpetriv.sprout.core.database.SessionStore
import dev.kpetriv.sprout.core.database.SproutDatabase
import dev.kpetriv.sprout.feature.lock.ParentPinStore
import dev.kpetriv.sprout.feature.lock.ParentSession
import dev.kpetriv.sprout.feature.lock.PinUnlockResult
import java.io.IOException
import javax.inject.Inject

/** How a PIN check before "Erase this phone's copy instead" ended. */
enum class ErasePinCheck {
    CONFIRMED,
    WRONG,
    LOCKED_OUT,

    /** The stored PIN cannot be read: the erase stays closed (it never falls back to "no PIN"). */
    UNAVAILABLE,
}

/** What the gate's erase confirmation needs to know and check (audit L5); a seam for tests. */
interface EraseConfirmation {
    /** Changes made on this phone that the server has not accepted yet: erasing loses them. */
    suspend fun unsyncedChanges(): Int

    /** True when this phone has a parent PIN, which must then be entered to erase. */
    suspend fun hasParentPin(): Boolean

    /** Checks [pin] with `feature/lock`'s PIN check (its lockout included). Zeroes [pin]. */
    suspend fun checkPin(pin: CharArray): ErasePinCheck

    /**
     * True when an erase after the registry answered keeps this phone's chat key: the session is a parent's (ACTIVE or
     * PENDING), the same test the removal makes ([keepsDeviceIdentity], D-REL-28).
     */
    suspend fun keepsChatKey(): Boolean

    /**
     * True when a chat key is stored on this phone ([ChatIdentityKeeper.hasKey]): an erase that keeps the key keeps
     * nothing without one, so the confirmation promises it only when both hold.
     */
    suspend fun hasChatKey(): Boolean
}

/**
 * [EraseConfirmation] over the outbox and `feature/lock`: the PIN is checked by [ParentSession.unlockWithPin], under
 * the same lockout as the parent lock (a wrong PIN counts as a failed attempt). A PIN store that cannot be read counts
 * as "a PIN exists".
 */
class LocalEraseConfirmation @Inject constructor(
    private val database: SproutDatabase,
    private val pins: ParentPinStore,
    private val parentSession: ParentSession,
    private val sessions: SessionStore,
    private val chatIdentity: ChatIdentityKeeper,
) : EraseConfirmation {
    override suspend fun unsyncedChanges(): Int = database.outboxDao().count()

    override suspend fun keepsChatKey(): Boolean = keepsDeviceIdentity(sessions.get())

    override suspend fun hasChatKey(): Boolean = chatIdentity.hasKey()

    @Suppress("SwallowedException") // Unreadable storage reads as "a PIN exists"; nothing to log.
    override suspend fun hasParentPin(): Boolean = try {
        pins.hasPin()
    } catch (unreadable: IOException) {
        true
    }

    override suspend fun checkPin(pin: CharArray): ErasePinCheck = when (parentSession.unlockWithPin(pin)) {
        PinUnlockResult.Unlocked, PinUnlockResult.SetupRequired -> ErasePinCheck.CONFIRMED
        is PinUnlockResult.WrongPin -> ErasePinCheck.WRONG
        is PinUnlockResult.LockedOut -> ErasePinCheck.LOCKED_OUT
        PinUnlockResult.Unavailable -> ErasePinCheck.UNAVAILABLE
    }
}

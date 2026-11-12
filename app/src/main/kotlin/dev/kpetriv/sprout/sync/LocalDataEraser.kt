package dev.kpetriv.sprout.sync

import dev.kpetriv.sprout.core.database.usecase.WipeLocalDataUseCase
import dev.kpetriv.sprout.core.syncfirebase.FirebaseAuthSession
import kotlinx.coroutines.CancellationException
import javax.inject.Inject

/** Erases this device's household data and signs out of Firebase (M3 §6); the caller restarts the app. */
fun interface LocalDataEraser {
    /**
     * True when the database, the photos and the secrets are verifiably gone. [keepDeviceIdentity] (a sign-out only,
     * D-REL-6) keeps this phone's chat encryption key with its device key, since the device id survives.
     */
    suspend fun erase(keepDeviceIdentity: Boolean): Boolean
}

/**
 * [LocalDataEraser] over [WipeLocalDataUseCase] (session, database files, photos, `SecretStore` blobs; the device
 * key stays, and so does the chat encryption key when `keepDeviceIdentity` is set) and
 * [FirebaseAuthSession.signOut]. The sign-out is best effort: a deleted or disabled account may already be gone.
 */
class WipingLocalDataEraser @Inject constructor(
    private val wipe: WipeLocalDataUseCase,
    private val auth: FirebaseAuthSession,
) : LocalDataEraser {
    @Suppress("TooGenericExceptionCaught", "SwallowedException") // The wipe must run whatever the sign-out does.
    override suspend fun erase(keepDeviceIdentity: Boolean): Boolean {
        try {
            auth.signOut()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            // Signed out locally anyway once the session is gone; nothing to log.
        }
        return wipe(keepDeviceIdentity)
    }
}

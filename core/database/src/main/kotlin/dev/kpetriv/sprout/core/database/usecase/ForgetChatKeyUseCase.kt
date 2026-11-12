package dev.kpetriv.sprout.core.database.usecase

import dev.kpetriv.sprout.core.database.ChatIdentityKeeper
import dev.kpetriv.sprout.core.model.DeviceId
import java.io.IOException
import javax.inject.Inject

/**
 * Moves this phone's chat X25519 private key (`chat.x25519.v1`, which a parent's sign-out keeps for the same device id,
 * D-REL-6) out of use before this phone registers a new device id: a new household, a second parent phone or a kid
 * link (D-REL-7). The key is not deleted but stashed, tagged with [currentDeviceId] (the device it belongs to,
 * [ChatIdentityKeeper.stash], D-REL-29): a mistyped link code or a registration that fails must not destroy the old
 * account's chat on this phone. The stash does not open as a key, so the new registration gets a fresh one and a kid,
 * another household or another parent never inherits the old key. A registration the server accepted deletes the
 * stash ([ChatIdentityKeeper.dropStash]); restoring the stash's own device moves it back
 * ([ChatIdentityKeeper.unstash]).
 */
class ForgetChatKeyUseCase @Inject constructor(private val identity: ChatIdentityKeeper) {
    /** @throws IOException when a key is still stored under its live name afterwards; the caller must not register. */
    suspend operator fun invoke(currentDeviceId: DeviceId) = identity.stash(currentDeviceId)
}

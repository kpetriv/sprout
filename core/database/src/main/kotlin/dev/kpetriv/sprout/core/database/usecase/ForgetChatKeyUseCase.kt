package dev.kpetriv.sprout.core.database.usecase

import dev.kpetriv.sprout.core.common.Dispatcher
import dev.kpetriv.sprout.core.common.SproutDispatchers
import dev.kpetriv.sprout.core.database.LocalDataFiles
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject

/**
 * Deletes this phone's chat X25519 private key ([LocalDataFiles.DEVICE_IDENTITY_SECRETS], `chat.x25519.v1`), which a
 * sign-out wipe keeps for the same device id ([WipeLocalDataUseCase], D-REL-6). Onboarding runs it before this phone
 * registers a new device id (a new household, a second parent phone, a kid link) and before it erases a device it
 * restored for another account (D-REL-7), so a kid linked on the same phone, another household or another parent never
 * inherits the key a previous identity registered: the next use of `EncryptionKeyStore` creates a fresh one.
 */
class ForgetChatKeyUseCase @Inject constructor(
    private val files: LocalDataFiles,
    @param:Dispatcher(SproutDispatchers.IO) private val dispatcher: CoroutineDispatcher,
) {
    /** @throws IOException when a file of the key is still there afterwards; the caller must not register then. */
    suspend operator fun invoke() = withContext(dispatcher) {
        val key = LocalDataFiles.DEVICE_IDENTITY_SECRETS.flatMap(files::secretFiles)
        key.forEach { it.delete() }
        if (key.any { it.exists() }) throw IOException("The chat key could not be deleted")
    }
}

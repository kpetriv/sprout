package dev.kpetriv.sprout.core.crypto

import dev.kpetriv.sprout.core.common.Dispatcher
import dev.kpetriv.sprout.core.common.SproutDispatchers
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Signs payloads with this device's key: the entry point for ledger and chat code. Keystore (especially
 * StrongBox) signing is a blocking IPC call, so it runs on the IO dispatcher.
 */
class DeviceSigner @Inject constructor(
    private val keyStore: DeviceKeyStore,
    @param:Dispatcher(SproutDispatchers.IO) private val dispatcher: CoroutineDispatcher,
) {
    /** ECDSA P-256 / SHA-256 DER signature over [payload]. */
    suspend fun sign(payload: ByteArray): ByteArray {
        val key = keyStore.deviceSigningKey()
        return withContext(dispatcher) { JceEcdsaSigner(key).sign(payload) }
    }

    suspend fun publicKey(): PublicKeyBytes = keyStore.deviceSigningKey().publicKey
}

package dev.kpetriv.sprout.core.crypto

import javax.inject.Inject

/**
 * This device's X25519 chat key pair (M4 §1.1, D-M4-2): one per device, generated on first use, its private scalar a
 * 32-byte secret in [SecretStore] under [SECRET_NAME] (Keystore-wrapped, wiped with the other secrets). The public key
 * is registered with the household, bound to the device key by `EncryptionKeyBinding`.
 */
interface EncryptionKeyStore {
    /**
     * The key pair; close it when done (it wipes the private key copy). Creates and persists the key on first use
     * unless [createIfMissing] is false.
     *
     * @throws SecretUnavailableException when the stored key cannot be unwrapped, or none is stored and
     *   [createIfMissing] is false. A stored key is never silently replaced: the registered key would no longer match.
     */
    suspend fun keyPair(createIfMissing: Boolean = true): X25519KeyPair

    /** The public half of [keyPair], creating the key on first use. */
    suspend fun publicKey(): X25519PublicKey = keyPair().use { it.publicKey }

    companion object {
        /** The [SecretStore] name of the private scalar. */
        const val SECRET_NAME: String = "chat.x25519.v1"
    }
}

/**
 * [EncryptionKeyStore] over [SecretStore]: the store's random 32-byte secret is the X25519 private scalar as is
 * (X25519 clamps the scalar itself, RFC 7748 §5), and the public key is computed with [agreement] on every call.
 */
class SecretStoreEncryptionKeyStore @Inject constructor(
    private val secretStore: SecretStore,
    private val agreement: X25519KeyAgreement,
) : EncryptionKeyStore {
    override suspend fun keyPair(createIfMissing: Boolean): X25519KeyPair {
        val privateKey = secretStore.getOrCreate(EncryptionKeyStore.SECRET_NAME, createIfMissing).use { secret ->
            secret.withMaterial { X25519PrivateKey.copyOf(it) }
        }
        return try {
            X25519KeyPair(privateKey, agreement.publicKeyOf(privateKey))
        } catch (e: KeyAgreementException) {
            privateKey.close()
            throw e
        }
    }
}

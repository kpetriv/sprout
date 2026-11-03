package dev.kpetriv.sprout.core.crypto.chat

import dev.kpetriv.sprout.core.crypto.Aead
import dev.kpetriv.sprout.core.crypto.AeadException
import dev.kpetriv.sprout.core.crypto.DeviceSigner
import dev.kpetriv.sprout.core.domainledger.chat.ChatLimits
import dev.kpetriv.sprout.core.domainledger.chat.MessageEnvelope
import dev.kpetriv.sprout.core.domainledger.chat.MessageHeader
import dev.kpetriv.sprout.core.domainledger.chat.MessagePadding
import dev.kpetriv.sprout.core.domainledger.chat.MessagePlaintext
import dev.kpetriv.sprout.core.domainledger.chat.MessagePlaintextCodec
import java.security.GeneralSecurityException
import javax.inject.Inject

/** A message did not decrypt: wrong key, tampered or transplanted ciphertext, or a plaintext that does not decode. */
open class MessageDecryptionException(message: String, cause: Throwable? = null) :
    GeneralSecurityException(message, cause)

/**
 * The ciphertext authenticated under the key, so the author really wrote it, but what it holds is not a valid padded
 * plaintext of the envelope's kind (review H1): the message is undecodable for good, not waiting for a key.
 */
class MessagePlaintextException(message: String) : MessageDecryptionException(message)

/** The encrypted plaintext would exceed [ChatLimits.MAX_MESSAGE_CIPHERTEXT_BYTES]. */
class MessageTooLargeException(size: Int) :
    IllegalArgumentException("Ciphertext of $size bytes exceeds ${ChatLimits.MAX_MESSAGE_CIPHERTEXT_BYTES}")

/**
 * Encrypts and decrypts message content (M4 §1.3): `Aead` (AES-256-GCM) under the thread key of the header's epoch,
 * plaintext [MessagePlaintextCodec.encode] padded to its kind's bucket ([MessagePadding], audit M3), associated data
 * [MessageHeader.associatedData]. The ciphertext is bound to its header, so it fails authentication in any other
 * envelope.
 */
class MessageCipher @Inject constructor(private val aead: Aead) {
    /**
     * The ciphertext for [plaintext] in a message with [header]; pass it to [MessageEnvelope.of].
     *
     * @throws IllegalArgumentException if [key] is not the key of the header's thread and epoch, or the plaintext's
     *   kind is not the header's.
     * @throws MessageTooLargeException if the ciphertext would exceed [ChatLimits.MAX_MESSAGE_CIPHERTEXT_BYTES].
     */
    fun encrypt(key: ThreadKey, header: MessageHeader, plaintext: MessagePlaintext): ByteArray {
        require(key.threadId == header.threadId && key.epoch == header.epoch) { "key is not the header's epoch key" }
        require(plaintext.kind == header.kind) { "plaintext kind differs from the header's" }
        val bytes = MessagePlaintextCodec.encode(plaintext)
        if (MessagePadding.paddedSize(header.kind, bytes.size) == null) {
            bytes.fill(0)
            throw MessageTooLargeException(
                bytes.size + MessagePadding.LENGTH_BYTES + MessagePadding.AEAD_OVERHEAD_BYTES,
            )
        }
        val padded = MessagePadding.pad(header.kind, bytes)
        val ciphertext = try {
            aead.encrypt(key.key, padded, header.associatedData())
        } finally {
            bytes.fill(0)
            padded.fill(0)
        }
        if (ciphertext.size > ChatLimits.MAX_MESSAGE_CIPHERTEXT_BYTES) throw MessageTooLargeException(ciphertext.size)
        return ciphertext
    }

    /**
     * Decrypts [envelope] with [key]. A failure is **not** a quarantine (M4 §1.3): the data layer keeps the message
     * LOCKED and retries when another key for the epoch arrives.
     *
     * @throws MessageDecryptionException if [key] is for another thread or epoch, or the ciphertext does not
     *   authenticate under the envelope's header.
     * @throws MessagePlaintextException (a [MessageDecryptionException]) when it authenticates but the padding or
     *   the plaintext does not decode as the envelope's kind.
     */
    @Suppress("ThrowsCount") // A wrong key, then undecodable padding, then undecodable content.
    fun decrypt(key: ThreadKey, envelope: MessageEnvelope): MessagePlaintext {
        if (key.threadId != envelope.threadId || key.epoch != envelope.epoch) {
            throw MessageDecryptionException("Key is not the envelope's epoch key")
        }
        val padded = open(key, envelope)
        val bytes = MessagePadding.unpad(padded)
        padded.fill(0)
        if (bytes == null) throw MessagePlaintextException("Padding does not decode")
        val plaintext = try {
            MessagePlaintextCodec.decode(envelope.kind, bytes)
        } finally {
            bytes.fill(0)
        }
        return plaintext ?: throw MessagePlaintextException("Plaintext does not decode")
    }

    private fun open(key: ThreadKey, envelope: MessageEnvelope): ByteArray = try {
        aead.decrypt(key.key, envelope.ciphertextBytes(), envelope.header.associatedData())
    } catch (e: AeadException) {
        throw MessageDecryptionException("Ciphertext does not authenticate", e)
    }
}

/** Signs message envelopes with this device's key (M4 §1.3): ECDSA P-256 over [MessageEnvelope.signedPayload]. */
class MessageSigner @Inject constructor(private val signer: DeviceSigner) {
    suspend fun sign(envelope: MessageEnvelope): MessageEnvelope =
        envelope.withSignature(signer.sign(envelope.signedPayload()))
}

package dev.kpetriv.sprout.core.crypto.chat

import dev.kpetriv.sprout.core.crypto.Aead
import dev.kpetriv.sprout.core.crypto.AeadException
import dev.kpetriv.sprout.core.crypto.HouseholdKeyDerivation
import dev.kpetriv.sprout.core.crypto.KeyAgreementException
import dev.kpetriv.sprout.core.crypto.SymmetricKey
import dev.kpetriv.sprout.core.crypto.X25519KeyAgreement
import dev.kpetriv.sprout.core.crypto.X25519PrivateKey
import dev.kpetriv.sprout.core.crypto.X25519PublicKey
import dev.kpetriv.sprout.core.domainledger.chat.ThreadKeyDocument
import dev.kpetriv.sprout.core.domainledger.chat.ThreadKeyWrapFormat
import dev.kpetriv.sprout.core.domainledger.chat.ThreadMember
import dev.kpetriv.sprout.core.model.DeviceId
import dev.kpetriv.sprout.core.model.HouseholdId
import dev.kpetriv.sprout.core.model.ThreadId
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.inject.Inject
import kotlin.io.encoding.Base64

/** A thread key could not be unwrapped: no wrap for this device, a failed agreement, or a wrap that does not open. */
class ThreadKeyUnwrapException(message: String, cause: Throwable? = null) : GeneralSecurityException(message, cause)

/**
 * Creates, wraps and unwraps thread keys exactly as [ThreadKeyWrapFormat] describes (M4 §1.2). Which epoch to create
 * or re-wrap, and which epoch to send with, is [dev.kpetriv.sprout.core.domainledger.chat.ThreadKeyPolicy]'s job.
 */
class ThreadKeyring @Inject constructor(
    private val agreement: X25519KeyAgreement,
    private val derivation: HouseholdKeyDerivation,
    private val aead: Aead,
    private val random: SecureRandom,
) {
    /** A fresh random key for ([threadId], [epoch]): a rotation, or epoch 0. */
    fun newKey(threadId: ThreadId, epoch: Long): ThreadKey = ThreadKey.generate(threadId, epoch, random)

    /**
     * The `thread_key` payload that wraps [key] for every one of [members], created by [sender] with its private key
     * [senderPrivate]. [sender] must be one of [members] (it wraps for itself too) and its member key must be the
     * public key of [senderPrivate]; otherwise this throws [IllegalArgumentException], because nobody, the sender
     * included, could unwrap what it wrote.
     *
     * @throws KeyAgreementException if a member's key is a low-order point.
     */
    fun wrapFor(
        householdId: HouseholdId,
        key: ThreadKey,
        sender: DeviceId,
        senderPrivate: X25519PrivateKey,
        members: List<ThreadMember>,
    ): ThreadKeyDocument {
        val self = requireNotNull(members.firstOrNull { it.deviceId == sender }) { "the sender must be a member" }
        require(agreement.publicKeyOf(senderPrivate) == X25519PublicKey(self.encryptionKey)) {
            "the sender's registered encryption key does not match its private key"
        }
        val salt = ThreadKeyWrapFormat.salt(householdId)
        val info = ThreadKeyWrapFormat.info(key.threadId, key.epoch)
        val wraps = members.associate { member ->
            val associatedData = ThreadKeyWrapFormat.associatedData(
                householdId,
                key.threadId,
                key.epoch,
                sender,
                member.deviceId,
            )
            val wrapped = wrapKey(senderPrivate, X25519PublicKey(member.encryptionKey), salt, info).use { wrapKey ->
                val material = key.copyBytes()
                try {
                    aead.encrypt(wrapKey, material, associatedData)
                } finally {
                    material.fill(0)
                }
            }
            member.deviceId to Base64.Default.encode(wrapped)
        }
        return ThreadKeyDocument(key.threadId, key.epoch, sender, wraps)
    }

    /**
     * Unwraps [document]'s key for [myDeviceId]. [senderPublic] is the **registered, binding-verified** encryption key
     * of `document.createdByDeviceId` (never a key taken from the document).
     *
     * @throws ThreadKeyUnwrapException when the document has no wrap for [myDeviceId] or the wrap does not open (a
     *   wrong key, another household, thread, epoch, sender or recipient).
     */
    fun unwrap(
        householdId: HouseholdId,
        document: ThreadKeyDocument,
        myDeviceId: DeviceId,
        myPrivate: X25519PrivateKey,
        senderPublic: X25519PublicKey,
    ): ThreadKey {
        val wrapped = document.wrappedFor(myDeviceId) ?: throw ThreadKeyUnwrapException("No wrap for this device")
        val associatedData = ThreadKeyWrapFormat.associatedData(
            householdId,
            document.threadId,
            document.epoch,
            document.createdByDeviceId,
            myDeviceId,
        )
        val salt = ThreadKeyWrapFormat.salt(householdId)
        val info = ThreadKeyWrapFormat.info(document.threadId, document.epoch)
        val material = open(wrapped, associatedData) { wrapKey(myPrivate, senderPublic, salt, info) }
        return try {
            if (material.size != ThreadKey.SIZE) throw ThreadKeyUnwrapException("Unwrapped key has the wrong size")
            ThreadKey.fromBytes(document.threadId, document.epoch, material)
        } finally {
            material.fill(0)
        }
    }

    /** Decrypts [wrapped]; a failed agreement ([KeyAgreementException]) or AEAD ([AeadException]) fails the unwrap. */
    private fun open(wrapped: ByteArray, associatedData: ByteArray, wrapKey: () -> SymmetricKey): ByteArray = try {
        wrapKey().use { aead.decrypt(it, wrapped, associatedData) }
    } catch (e: GeneralSecurityException) {
        throw ThreadKeyUnwrapException("Wrap does not open", e)
    }

    private fun wrapKey(
        privateKey: X25519PrivateKey,
        peer: X25519PublicKey,
        salt: ByteArray,
        info: ByteArray,
    ): SymmetricKey = agreement.sharedSecret(privateKey, peer).use { derivation.deriveThreadKey(it, salt, info) }
}

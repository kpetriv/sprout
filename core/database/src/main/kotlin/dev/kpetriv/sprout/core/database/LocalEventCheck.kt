package dev.kpetriv.sprout.core.database

import dev.kpetriv.sprout.core.crypto.DeviceSigner
import dev.kpetriv.sprout.core.crypto.Verifier
import dev.kpetriv.sprout.core.domainledger.LedgerEvent
import dev.kpetriv.sprout.core.domainledger.signatureBytes
import dev.kpetriv.sprout.core.domainledger.toHex
import dev.kpetriv.sprout.core.model.DeviceId

/**
 * Decides which events count as authored on this device: [RoomLedgerRepository.append] accepts only those, and
 * stored rows of origin LOCAL must still pass it when they are read back.
 */
internal interface LocalEventCheck {
    /** Binds to this device's current key. A Keystore read, so call it before opening a transaction. */
    suspend fun bind(): Bound

    /** A check bound to one key; cheap and safe to call inside a transaction. */
    fun interface Bound {
        /** True when [event] was written and signed by this device. [signedPayload] is what the signature covers. */
        fun accepts(event: LedgerEvent, signedPayload: ByteArray): Boolean
    }
}

/**
 * The production [LocalEventCheck]: the author device id must be this device's key fingerprint (hex SHA-256 of the
 * public key, the session's device id) and the signature must verify with [verifier] against that
 * key over the event's domain-separated signed payload (ADR-0004 section 5).
 */
internal class DeviceKeyEventCheck(private val signer: DeviceSigner, private val verifier: Verifier) :
    LocalEventCheck {
    override suspend fun bind(): LocalEventCheck.Bound {
        val key = signer.publicKey()
        val deviceId = DeviceId(key.fingerprint().toHex())
        return LocalEventCheck.Bound { event, payload ->
            val signature = event.signatureBytes()
            event.envelope.authorDeviceId == deviceId && signature != null && verifier.verify(key, payload, signature)
        }
    }
}

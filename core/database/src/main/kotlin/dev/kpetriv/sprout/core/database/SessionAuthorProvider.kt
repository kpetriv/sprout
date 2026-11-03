package dev.kpetriv.sprout.core.database

import dev.kpetriv.sprout.core.crypto.DeviceSigner
import dev.kpetriv.sprout.core.domainledger.EventAuthor
import dev.kpetriv.sprout.core.domainledger.toHex
import dev.kpetriv.sprout.core.model.DeviceId
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.Role
import javax.inject.Inject

/** Who is writing right now: household, user, role and this device. */
interface SessionAuthorProvider {
    /** The author of this device's own writes. Throws [NoSessionException] when nobody is signed in. */
    suspend fun currentAuthor(): EventAuthor

    /**
     * The author of what [kidId] does on this device in kid mode (D-M3-12). On a parent device this is the parent's
     * uid and device with role [Role.KID] (receivers accept KID-role writes from a PARENT device, never the other way
     * round). This default covers parent sessions only; [SessionBackedAuthorProvider] also handles KID sessions.
     */
    suspend fun kidAuthor(kidId: KidId): EventAuthor {
        val author = currentAuthor()
        check(author.role == Role.PARENT) { "kid authors on a KID session need the session's kid" }
        return author.copy(role = Role.KID)
    }
}

/**
 * The production [SessionAuthorProvider]: every author comes from the signed-in [Session] ([SessionStore]).
 * - [currentAuthor]: the session's household, uid, role and device; [NoSessionException] without a session.
 * - [kidAuthor] on a PARENT session: the parent's uid and device with role KID (D-M3-12).
 * - [kidAuthor] on a KID session: the session's own author, only for the kid the device is linked to; any other
 *   kid is an [IllegalArgumentException]. A KID session can never produce a PARENT author.
 */
class SessionBackedAuthorProvider @Inject constructor(private val sessions: SessionStore) : SessionAuthorProvider {
    override suspend fun currentAuthor(): EventAuthor = session().author()

    override suspend fun kidAuthor(kidId: KidId): EventAuthor {
        val session = session()
        return when (session.role) {
            Role.PARENT -> session.author().copy(role = Role.KID)

            Role.KID -> {
                require(session.kidId == kidId) { "this device is linked to another kid" }
                session.author()
            }
        }
    }

    private suspend fun session(): Session = sessions.get() ?: throw NoSessionException()
}

/** This device's id: the hex SHA-256 fingerprint of its signing key (a Keystore read). */
suspend fun DeviceSigner.deviceId(): DeviceId = DeviceId(publicKey().fingerprint().toHex())

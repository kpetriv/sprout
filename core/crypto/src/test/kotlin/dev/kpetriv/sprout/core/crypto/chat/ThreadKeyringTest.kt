package dev.kpetriv.sprout.core.crypto.chat

import dev.kpetriv.sprout.core.crypto.AesGcmAead
import dev.kpetriv.sprout.core.crypto.HouseholdKeyDerivation
import dev.kpetriv.sprout.core.crypto.JceX25519KeyAgreement
import dev.kpetriv.sprout.core.crypto.TinkX25519KeyAgreement
import dev.kpetriv.sprout.core.crypto.X25519KeyAgreement
import dev.kpetriv.sprout.core.domainledger.chat.ThreadKeyDocument
import dev.kpetriv.sprout.core.domainledger.chat.ThreadMember
import dev.kpetriv.sprout.core.model.DeviceId
import dev.kpetriv.sprout.core.model.HouseholdId
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.Role
import dev.kpetriv.sprout.core.model.ThreadId
import java.security.SecureRandom
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

/** Wrap and unwrap with every combination of implementations: devices on API 28 and 35 must interoperate. */
class ThreadKeyringTest {
    private val random = SecureRandom()
    private val jce = JceX25519KeyAgreement(random)
    private val tink = TinkX25519KeyAgreement(random)

    private fun keyring(agreement: X25519KeyAgreement) =
        ThreadKeyring(agreement, HouseholdKeyDerivation(), AesGcmAead(random), random)

    private val parent = CryptoDevice("parent-phone", Role.PARENT, jce)
    private val tablet = CryptoDevice("parent-tablet", Role.PARENT, tink)
    private val kid = CryptoDevice("kid-tablet", Role.KID, tink)
    private val members = listOf(parent, tablet, kid).map { it.member() }

    @Test
    fun everyMemberUnwrapsTheSameKeyWhicheverImplementationEachSideUses() {
        for (senderAgreement in listOf(jce, tink)) {
            val sender = keyring(senderAgreement)
            sender.newKey(thread, 2).use { key ->
                val doc = sender.wrapFor(household, key, parent.deviceId, parent.keys.privateKey, members)
                assertEquals(setOf(parent.deviceId, tablet.deviceId, kid.deviceId), doc.recipients)
                assertEquals(parent.deviceId, doc.createdByDeviceId)
                // Through the document's own payload, as a receiver gets it.
                val received = ThreadKeyDocument.fromPayload(doc.toPayload())!!
                assertEveryoneUnwraps(received, key)
            }
        }
    }

    private fun assertEveryoneUnwraps(received: ThreadKeyDocument, key: ThreadKey) {
        for (recipient in listOf(parent, tablet, kid)) {
            for (recipientAgreement in listOf(jce, tink)) {
                val unwrapped = keyring(recipientAgreement).unwrap(
                    household,
                    received,
                    recipient.deviceId,
                    recipient.keys.privateKey,
                    parent.keys.publicKey,
                )
                assertContentEquals(key.copyBytes(), unwrapped.copyBytes())
                assertEquals(thread, unwrapped.threadId)
                assertEquals(2, unwrapped.epoch)
                unwrapped.close()
            }
        }
    }

    @Test
    fun aDeviceWithoutAWrapOrTheWrongKeysCannotUnwrap() {
        val ring = keyring(jce)
        val doc = ring.newKey(thread, 0).use {
            ring.wrapFor(household, it, parent.deviceId, parent.keys.privateKey, members)
        }
        val outsider = CryptoDevice("revoked-phone", Role.PARENT, jce)
        assertFailsWith<ThreadKeyUnwrapException> {
            ring.unwrap(household, doc, outsider.deviceId, outsider.keys.privateKey, parent.keys.publicKey)
        }
        // The outsider's private key under the kid's device id: the agreement gives another wrap key.
        assertFailsWith<ThreadKeyUnwrapException> {
            ring.unwrap(household, doc, kid.deviceId, outsider.keys.privateKey, parent.keys.publicKey)
        }
        // A sender key that is not the creator's.
        assertFailsWith<ThreadKeyUnwrapException> {
            ring.unwrap(household, doc, kid.deviceId, kid.keys.privateKey, tablet.keys.publicKey)
        }
    }

    @Test
    fun aWrapIsBoundToHouseholdThreadEpochSenderAndRecipient() {
        val ring = keyring(tink)
        val doc = ring.newKey(thread, 5).use {
            ring.wrapFor(household, it, parent.deviceId, parent.keys.privateKey, members)
        }
        fun failsFor(document: ThreadKeyDocument, householdId: HouseholdId = household, recipient: CryptoDevice = kid) =
            assertFailsWith<ThreadKeyUnwrapException> {
                ring.unwrap(householdId, document, recipient.deviceId, recipient.keys.privateKey, parent.keys.publicKey)
            }
        failsFor(doc, householdId = HouseholdId("house-2"))
        failsFor(doc.copy(epoch = 6))
        failsFor(doc.copy(threadId = ThreadId.of(KidId("kid-2"))))
        // The kid's wrap relabelled as the tablet's.
        failsFor(
            doc.copy(wraps = doc.wraps + (tablet.deviceId to doc.wraps.getValue(kid.deviceId))),
            recipient = tablet,
        )
        // Claimed by another creator: the tablet as sender (its wrap for itself would need its key in the agreement).
        val relabelled = doc.copy(createdByDeviceId = tablet.deviceId)
        assertFailsWith<ThreadKeyUnwrapException> {
            ring.unwrap(household, relabelled, kid.deviceId, kid.keys.privateKey, tablet.keys.publicKey)
        }
    }

    @Test
    fun theSenderMustBeAMemberWithItsOwnRegisteredKey() {
        val ring = keyring(jce)
        ring.newKey(thread, 0).use { key ->
            assertFailsWith<IllegalArgumentException> {
                ring.wrapFor(household, key, parent.deviceId, parent.keys.privateKey, members.drop(1))
            }
            assertFailsWith<IllegalArgumentException> {
                ring.wrapFor(household, key, parent.deviceId, tablet.keys.privateKey, members)
            }
        }
    }

    @Test
    fun aMemberWithASmallOrderKeyIsSkippedAndTheOthersStillGetTheKey() {
        val ring = keyring(jce)
        val bad = ThreadMember(DeviceId("bad-kid"), Role.KID, ByteArray(32).also { it[0] = 1 })
        val doc = ring.newKey(thread, 0).use {
            ring.wrapFor(household, it, parent.deviceId, parent.keys.privateKey, members + bad)
        }
        assertEquals(setOf(parent.deviceId, tablet.deviceId, kid.deviceId), doc.recipients)
    }

    @Test
    fun newKeysAreRandomAndNeverPrinted() {
        val ring = keyring(jce)
        val a = ring.newKey(thread, 0)
        val b = ring.newKey(thread, 0)
        assertFalse(a.copyBytes().contentEquals(b.copyBytes()))
        assertFalse(a.toString().contains(a.copyBytes().joinToString("") { "%02x".format(it) }))
        a.close()
        assertFailsWith<IllegalStateException> { a.copyBytes() }
        assertFailsWith<IllegalArgumentException> { ThreadKey.fromBytes(thread, 0, ByteArray(16)) }
        assertEquals(DeviceId("kid-tablet"), kid.deviceId)
    }
}

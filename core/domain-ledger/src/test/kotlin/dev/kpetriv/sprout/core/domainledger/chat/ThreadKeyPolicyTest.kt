package dev.kpetriv.sprout.core.domainledger.chat

import dev.kpetriv.sprout.core.domainledger.DeviceStatus
import dev.kpetriv.sprout.core.domainledger.SoftwareKey
import dev.kpetriv.sprout.core.domainledger.SoftwareSignatureCheck
import dev.kpetriv.sprout.core.domainledger.bindEncryptionKey
import dev.kpetriv.sprout.core.model.Role
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ThreadKeyPolicyTest {
    private val phone = ChatDevice("parent-phone", Role.PARENT)
    private val tablet = ChatDevice("parent-tablet", Role.PARENT)
    private val kidTablet = ChatDevice("kid-tablet", Role.KID, thread.kidId)
    private val kidPhone = ChatDevice("kid-phone", Role.KID, thread.kidId)
    private val siblingTablet = ChatDevice("sibling-tablet", Role.KID, otherKid)

    private fun membership(
        vararg devices: dev.kpetriv.sprout.core.domainledger.RegisteredDevice,
        kidArchived: Boolean = false,
    ) = ThreadMembers.resolve(thread, devices.toList(), SoftwareSignatureCheck, kidArchived)

    private val everyone =
        membership(phone.registered(), tablet.registered(), kidTablet.registered(), siblingTablet.registered())

    @Test
    fun membersAreActiveParentsAndTheThreadsKidDevicesWithAVerifiedKey() {
        assertEquals(listOf(kidTablet, phone, tablet).map { it.deviceId }, everyone.members.map { it.deviceId })
        assertEquals(setOf(siblingTablet.deviceId), everyone.excluded)
    }

    @Test
    fun theFirstParentWithAKeyCreatesEpochZero() {
        val decision = ThreadKeyPolicy.decide(everyone, phone.deviceId, latest = null, selfHoldsLatestKey = false)
        assertEquals(ThreadKeyDecision.Rotate(0, everyone.members), decision)
        // A kid device never creates or re-wraps keys.
        assertEquals(ThreadKeyDecision.NoAction, ThreadKeyPolicy.decide(everyone, kidTablet.deviceId, null, false))
    }

    @Test
    fun anAddedMemberGetsTheCurrentEpochReWrapped() {
        val latest = keyDoc(2, phone, tablet)
        val decision = ThreadKeyPolicy.decide(everyone, phone.deviceId, latest, selfHoldsLatestKey = true)
        val rewrap = assertIs<ThreadKeyDecision.ReWrapCurrent>(decision)
        assertEquals(2, rewrap.epoch)
        assertEquals(setOf(kidTablet.deviceId), rewrap.forDevices)
        assertEquals(everyone.members, rewrap.members)
    }

    @Test
    fun aParentWithoutTheKeyWaitsForOneThatHasItOrRotatesWhenNoneCan() {
        val latest = keyDoc(2, phone)
        // The tablet cannot unwrap epoch 2, but the phone (in the wrap set) can re-wrap: wait.
        assertEquals(ThreadKeyDecision.NoAction, ThreadKeyPolicy.decide(everyone, tablet.deviceId, latest, false))
        // Only the phone was in the wrap set and it is gone (revoked): rotate.
        val phoneRevoked = membership(
            phone.registered(DeviceStatus.Revoked(atLamport = 10)),
            tablet.registered(),
            kidTablet.registered(),
        )
        assertEquals(
            ThreadKeyDecision.Rotate(3, phoneRevoked.members),
            ThreadKeyPolicy.decide(phoneRevoked, tablet.deviceId, latest, false),
        )
        // The wrap set's only parent is now unknown to this registry (not excluded) and nobody else can re-wrap.
        val strangerWrapped =
            ThreadKeyDocument(
                thread,
                2,
                ChatDevice("old-parent", Role.PARENT).deviceId,
                wrapsFor(ChatDevice("old-parent", Role.PARENT)),
            )
        assertEquals(
            ThreadKeyDecision.Rotate(3, everyone.members),
            ThreadKeyPolicy.decide(everyone, tablet.deviceId, strangerWrapped, false),
        )
    }

    @Test
    fun aRevokedDeviceInTheWrapSetForcesARotationWithoutIt() {
        val latest = keyDoc(4, phone, tablet, kidTablet)
        val kidRevoked = membership(
            phone.registered(),
            tablet.registered(),
            kidTablet.registered(DeviceStatus.Revoked(atLamport = 50)),
        )
        assertFalse(kidTablet.deviceId in kidRevoked.memberIds)
        assertTrue(kidTablet.deviceId in kidRevoked.excluded)
        val decision =
            assertIs<ThreadKeyDecision.Rotate>(ThreadKeyPolicy.decide(kidRevoked, tablet.deviceId, latest, true))
        assertEquals(5, decision.newEpoch)
        assertEquals(listOf(phone.deviceId, tablet.deviceId), decision.members.map { it.deviceId })
        // Senders stop using epoch 4 until a parent has rotated.
        assertNull(ThreadKeyPolicy.sendingEpoch(latest, heldEpochs = setOf(4), excluded = kidRevoked.excluded))
    }

    @Test
    fun archivingTheKidRotatesItsDevicesOut() {
        val latest = keyDoc(1, phone, kidTablet)
        val archived = membership(phone.registered(), kidTablet.registered(), kidArchived = true)
        assertEquals(listOf(phone.deviceId), archived.members.map { it.deviceId })
        assertEquals(
            ThreadKeyDecision.Rotate(2, archived.members),
            ThreadKeyPolicy.decide(archived, phone.deviceId, latest, true),
        )
    }

    @Test
    fun aDeviceWithoutAVerifiedEncryptionKeyIsNeitherMemberNorReasonToRotate() {
        val withoutKey =
            membership(phone.registered(), kidPhone.registered(encryptionKey = null), kidTablet.registered())
        assertEquals(setOf(phone.deviceId, kidTablet.deviceId), withoutKey.memberIds)
        assertTrue(withoutKey.excluded.isEmpty())
        val latest = keyDoc(0, phone, kidTablet)
        assertEquals(ThreadKeyDecision.NoAction, ThreadKeyPolicy.decide(withoutKey, phone.deviceId, latest, true))

        // A key whose binding was signed by another device key is ignored the same way.
        val forged = SoftwareKey().bindEncryptionKey(household, kidPhone.deviceId, kidPhone.encryptionKey)
        val withForged =
            membership(phone.registered(), kidPhone.registered(encryptionKey = forged), kidTablet.registered())
        assertEquals(setOf(phone.deviceId, kidTablet.deviceId), withForged.memberIds)
        assertEquals(ThreadKeyDecision.NoAction, ThreadKeyPolicy.decide(withForged, phone.deviceId, latest, true))
    }

    @Test
    fun pendingDevicesAreNotMembersAndNeverTriggerARotation() {
        val pending = membership(phone.registered(), tablet.registered(DeviceStatus.Pending))
        assertEquals(setOf(phone.deviceId), pending.memberIds)
        assertTrue(pending.excluded.isEmpty())
        val latest = keyDoc(0, phone, tablet)
        assertEquals(ThreadKeyDecision.NoAction, ThreadKeyPolicy.decide(pending, phone.deviceId, latest, true))
    }

    @Test
    fun aCompleteWrapSetNeedsNothing() {
        val latest = keyDoc(3, phone, tablet, kidTablet)
        assertEquals(ThreadKeyDecision.NoAction, ThreadKeyPolicy.decide(everyone, phone.deviceId, latest, true))
        assertEquals(ThreadKeyDecision.NoAction, ThreadKeyPolicy.decide(everyone, tablet.deviceId, latest, false))
    }

    @Test
    fun sendersUseTheLatestEpochOnlyWhenTheyHoldItsKey() {
        val latest = keyDoc(3, phone, tablet, kidTablet)
        assertEquals(3, ThreadKeyPolicy.sendingEpoch(latest, setOf(0, 1, 2, 3), everyone.excluded))
        assertNull(ThreadKeyPolicy.sendingEpoch(latest, setOf(0, 1, 2), everyone.excluded))
        assertNull(ThreadKeyPolicy.sendingEpoch(null, setOf(0), everyone.excluded))
    }
}

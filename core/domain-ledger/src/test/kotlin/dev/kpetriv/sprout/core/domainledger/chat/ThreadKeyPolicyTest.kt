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
        val decision = ThreadKeyPolicy.decide(everyone, phone.deviceId, emptyList(), emptySet())
        assertEquals(ThreadKeyDecision.Rotate(0, everyone.members), decision)
        // A kid device never creates or re-wraps keys.
        assertEquals(
            ThreadKeyDecision.NoAction,
            ThreadKeyPolicy.decide(everyone, kidTablet.deviceId, emptyList(), emptySet()),
        )
    }

    @Test
    fun anAddedMemberGetsTheCurrentEpochReWrapped() {
        val latest = keyDoc(2, phone, tablet)
        val decision = ThreadKeyPolicy.decide(everyone, phone.deviceId, listOf(latest), setOf(phone.deviceId))
        val rewrap = assertIs<ThreadKeyDecision.ReWrapCurrent>(decision)
        assertEquals(2, rewrap.epoch)
        assertEquals(setOf(kidTablet.deviceId), rewrap.forDevices)
        assertEquals(everyone.members, rewrap.members)
        assertEquals(phone.deviceId, rewrap.fromCreator)
    }

    @Test
    fun aParentHoldingOnlyAnotherCreatorsKeyReWrapsThatKeyUnderItsOwnDocument() {
        val latest = keyDoc(2, phone, tablet)
        val decision = ThreadKeyPolicy.decide(everyone, tablet.deviceId, listOf(latest), setOf(phone.deviceId))
        val rewrap = assertIs<ThreadKeyDecision.ReWrapCurrent>(decision)
        assertEquals(phone.deviceId, rewrap.fromCreator)
        assertEquals(setOf(kidTablet.deviceId), rewrap.forDevices)
    }

    @Test
    fun aParentWithoutTheKeyWaitsForOneThatHasItOrRotatesWhenNoneCan() {
        val latest = listOf(keyDoc(2, phone))
        // The tablet cannot unwrap epoch 2, but the phone (in the wrap set) can re-wrap: wait.
        assertEquals(ThreadKeyDecision.NoAction, ThreadKeyPolicy.decide(everyone, tablet.deviceId, latest, emptySet()))
        // Only the phone was in the wrap set and it is gone (revoked): rotate.
        val phoneRevoked = membership(
            phone.registered(DeviceStatus.Revoked(atLamport = 10)),
            tablet.registered(),
            kidTablet.registered(),
        )
        assertEquals(
            ThreadKeyDecision.Rotate(3, phoneRevoked.members),
            ThreadKeyPolicy.decide(phoneRevoked, tablet.deviceId, latest, emptySet()),
        )
        // The wrap set's only parent is now unknown to this registry (not excluded) and nobody else can re-wrap.
        val oldParent = ChatDevice("old-parent", Role.PARENT)
        val strangerWrapped = ThreadKeyDocument(thread, 2, oldParent.deviceId, wrapsFor(oldParent))
        assertEquals(
            ThreadKeyDecision.Rotate(3, everyone.members),
            ThreadKeyPolicy.decide(everyone, tablet.deviceId, listOf(strangerWrapped), emptySet()),
        )
    }

    @Test
    fun aRevokedDeviceInTheWrapSetForcesARotationWithoutIt() {
        val latest = listOf(keyDoc(4, phone, tablet, kidTablet))
        val kidRevoked = membership(
            phone.registered(),
            tablet.registered(),
            kidTablet.registered(DeviceStatus.Revoked(atLamport = 50)),
        )
        assertFalse(kidTablet.deviceId in kidRevoked.memberIds)
        assertTrue(kidTablet.deviceId in kidRevoked.excluded)
        val decision = assertIs<ThreadKeyDecision.Rotate>(
            ThreadKeyPolicy.decide(kidRevoked, tablet.deviceId, latest, setOf(phone.deviceId)),
        )
        assertEquals(5, decision.newEpoch)
        assertEquals(listOf(phone.deviceId, tablet.deviceId), decision.members.map { it.deviceId })
        // Senders stop using epoch 4 until a parent has rotated.
        assertNull(ThreadKeyPolicy.sendingEpoch(latest, holdsUsableKey = true, excluded = kidRevoked.excluded))
    }

    @Test
    fun anExcludedDeviceInAnyCreatorsWrapSetOfTheLatestEpochForcesARotation() {
        // Two parents rotated to epoch 4 at once; only the tablet's document still wraps for the revoked kid tablet.
        val latest = listOf(keyDoc(4, phone, tablet), keyDoc(4, tablet, phone, kidTablet))
        val kidRevoked = membership(
            phone.registered(),
            tablet.registered(),
            kidTablet.registered(DeviceStatus.Revoked(atLamport = 50)),
        )
        assertEquals(
            ThreadKeyDecision.Rotate(5, kidRevoked.members),
            ThreadKeyPolicy.decide(kidRevoked, phone.deviceId, latest, setOf(phone.deviceId)),
        )
        assertNull(ThreadKeyPolicy.sendingEpoch(latest, holdsUsableKey = true, excluded = kidRevoked.excluded))
    }

    @Test
    fun twoCreatorsOfOneEpochAreStableWhenBothWrapForEveryone() {
        val latest = listOf(keyDoc(1, phone, tablet, kidTablet), keyDoc(1, tablet, phone, kidTablet))
        val members = membership(phone.registered(), tablet.registered(), kidTablet.registered())
        assertEquals(
            ThreadKeyDecision.NoAction,
            ThreadKeyPolicy.decide(members, phone.deviceId, latest, setOf(phone.deviceId, tablet.deviceId)),
        )
        assertEquals(
            ThreadKeyDecision.NoAction,
            ThreadKeyPolicy.decide(members, tablet.deviceId, latest, setOf(phone.deviceId, tablet.deviceId)),
        )
    }

    @Test
    fun aDeviceDroppedByALaterEpochIsNeverReWrapped() {
        // Epoch 1 dropped the kid tablet; a parent whose mirror still lists it as ACTIVE must not add it back.
        val documents = listOf(keyDoc(0, phone, tablet, kidTablet), keyDoc(1, phone, tablet))
        val stale = membership(phone.registered(), tablet.registered(), kidTablet.registered())
        assertEquals(
            ThreadKeyDecision.NoAction,
            ThreadKeyPolicy.decide(stale, tablet.deviceId, documents, setOf(phone.deviceId)),
        )
        // A new device is still added, without the dropped one.
        val withNew = membership(phone.registered(), tablet.registered(), kidTablet.registered(), kidPhone.registered())
        val rewrap = assertIs<ThreadKeyDecision.ReWrapCurrent>(
            ThreadKeyPolicy.decide(withNew, phone.deviceId, documents, setOf(phone.deviceId)),
        )
        assertEquals(setOf(kidPhone.deviceId), rewrap.forDevices)
        assertFalse(rewrap.members.any { it.deviceId == kidTablet.deviceId })
    }

    @Test
    fun anEpochFarAboveTheHighestKnownIsIgnored() {
        val forged = keyDoc(Long.MAX_VALUE - 1, tablet, tablet)
        val documents = listOf(keyDoc(0, phone, tablet, kidTablet), forged)
        assertEquals(listOf(0L), ThreadKeyPolicy.plausibleEpochs(documents.map { it.epoch }))
        assertEquals(0L, ThreadKeyPolicy.latestEpoch(documents))
        assertEquals(
            ThreadKeyDecision.NoAction,
            ThreadKeyPolicy.decide(everyone, phone.deviceId, documents, setOf(phone.deviceId)),
        )
        assertEquals(listOf(0L, 1000L, 1500L), ThreadKeyPolicy.plausibleEpochs(listOf(1500, 0, 1000, 2501)))
        // Only implausible documents: as if there were none.
        assertEquals(
            ThreadKeyDecision.Rotate(0, everyone.members),
            ThreadKeyPolicy.decide(everyone, phone.deviceId, listOf(keyDoc(5000, phone, phone)), emptySet()),
        )
    }

    @Test
    fun archivingTheKidRotatesItsDevicesOut() {
        val latest = listOf(keyDoc(1, phone, kidTablet))
        val archived = membership(phone.registered(), kidTablet.registered(), kidArchived = true)
        assertEquals(listOf(phone.deviceId), archived.members.map { it.deviceId })
        assertEquals(
            ThreadKeyDecision.Rotate(2, archived.members),
            ThreadKeyPolicy.decide(archived, phone.deviceId, latest, setOf(phone.deviceId)),
        )
    }

    @Test
    fun aDeviceWhoseEncryptionKeyChangedIsExcluded() {
        val conflicted = kidTablet.registered().let {
            dev.kpetriv.sprout.core.domainledger.RegisteredDevice(
                it.deviceId,
                it.owner,
                it.publicKey,
                it.status,
                it.attestation,
                it.encryptionKey,
                encryptionKeyConflict = true,
            )
        }
        val members = membership(phone.registered(), conflicted)
        assertEquals(setOf(phone.deviceId), members.memberIds)
        assertTrue(kidTablet.deviceId in members.excluded)
    }

    @Test
    fun aDeviceWithoutAVerifiedEncryptionKeyIsNeitherMemberNorReasonToRotate() {
        val withoutKey =
            membership(phone.registered(), kidPhone.registered(encryptionKey = null), kidTablet.registered())
        assertEquals(setOf(phone.deviceId, kidTablet.deviceId), withoutKey.memberIds)
        assertTrue(withoutKey.excluded.isEmpty())
        val latest = listOf(keyDoc(0, phone, kidTablet))
        val held = setOf(phone.deviceId)
        assertEquals(ThreadKeyDecision.NoAction, ThreadKeyPolicy.decide(withoutKey, phone.deviceId, latest, held))

        // A key whose binding was signed by another device key is ignored the same way.
        val forged = SoftwareKey().bindEncryptionKey(household, kidPhone.deviceId, kidPhone.encryptionKey)
        val withForged =
            membership(phone.registered(), kidPhone.registered(encryptionKey = forged), kidTablet.registered())
        assertEquals(setOf(phone.deviceId, kidTablet.deviceId), withForged.memberIds)
        assertEquals(ThreadKeyDecision.NoAction, ThreadKeyPolicy.decide(withForged, phone.deviceId, latest, held))
    }

    @Test
    fun pendingDevicesAreNotMembersAndNeverTriggerARotation() {
        val pending = membership(phone.registered(), tablet.registered(DeviceStatus.Pending))
        assertEquals(setOf(phone.deviceId), pending.memberIds)
        assertTrue(pending.excluded.isEmpty())
        val latest = listOf(keyDoc(0, phone, tablet))
        assertEquals(
            ThreadKeyDecision.NoAction,
            ThreadKeyPolicy.decide(pending, phone.deviceId, latest, setOf(phone.deviceId)),
        )
    }

    @Test
    fun aCompleteWrapSetNeedsNothing() {
        val latest = listOf(keyDoc(3, phone, tablet, kidTablet))
        assertEquals(
            ThreadKeyDecision.NoAction,
            ThreadKeyPolicy.decide(everyone, phone.deviceId, latest, setOf(phone.deviceId)),
        )
        assertEquals(ThreadKeyDecision.NoAction, ThreadKeyPolicy.decide(everyone, tablet.deviceId, latest, emptySet()))
    }

    @Test
    fun sendersUseTheLatestEpochOnlyWhenTheyHoldAUsableKey() {
        val latest = listOf(keyDoc(2, phone, tablet), keyDoc(3, phone, tablet, kidTablet))
        assertEquals(3, ThreadKeyPolicy.sendingEpoch(latest, holdsUsableKey = true, excluded = everyone.excluded))
        assertNull(ThreadKeyPolicy.sendingEpoch(latest, holdsUsableKey = false, excluded = everyone.excluded))
        assertNull(ThreadKeyPolicy.sendingEpoch(emptyList(), holdsUsableKey = true, excluded = everyone.excluded))
    }
}

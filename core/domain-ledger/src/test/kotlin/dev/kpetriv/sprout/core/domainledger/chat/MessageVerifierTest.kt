package dev.kpetriv.sprout.core.domainledger.chat

import dev.kpetriv.sprout.core.domainledger.DeviceStatus
import dev.kpetriv.sprout.core.domainledger.InMemoryDeviceRegistry
import dev.kpetriv.sprout.core.domainledger.QuarantineReason
import dev.kpetriv.sprout.core.domainledger.SoftwareKey
import dev.kpetriv.sprout.core.domainledger.SoftwareSignatureCheck
import dev.kpetriv.sprout.core.model.DeviceId
import dev.kpetriv.sprout.core.model.HouseholdId
import dev.kpetriv.sprout.core.model.MessageId
import dev.kpetriv.sprout.core.model.Role
import dev.kpetriv.sprout.core.model.ThreadId
import dev.kpetriv.sprout.core.model.UserId
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame

class MessageVerifierTest {
    private val parent = ChatDevice("parent-phone", Role.PARENT)
    private val kid = ChatDevice("kid-tablet", Role.KID, thread.kidId)
    private val sibling = ChatDevice("sibling-tablet", Role.KID, otherKid)
    private val registry = InMemoryDeviceRegistry().apply {
        register(parent.registered())
        register(kid.registered())
        register(sibling.registered())
    }
    private val verifier =
        MessageVerifier(registry, SoftwareSignatureCheck, maxLamportJump = 100, householdId = household)

    private suspend fun verify(envelope: MessageEnvelope, expectedSeq: Long = envelope.seq, localMaxLamport: Long = 0) =
        verifier.verify(MessageJson.toJsonObject(envelope), localMaxLamport) { _, _ -> expectedSeq }

    private suspend fun reasonFor(envelope: MessageEnvelope, localMaxLamport: Long = 0): QuarantineReason =
        assertIs<VerifiedMessage.Quarantined>(verify(envelope, localMaxLamport = localMaxLamport)).reason

    @Test
    fun signedMessagesFromMembersAreAccepted() = runTest {
        for (envelope in listOf(
            parent.message(),
            kid.message(),
            parent.message(header = parent.header(threadId = ThreadId.of(otherKid))),
        )) {
            val ok = assertIs<VerifiedMessage.Ok>(verify(envelope))
            assertEquals(envelope, ok.envelope)
        }
        // Kid mode on a parent phone writes KID-role messages from the parent device (D-M3-12).
        assertIs<VerifiedMessage.Ok>(verify(parent.message(header = parent.header(authorRole = Role.KID))))
    }

    @Test
    fun theExpectedSeqIsLookedUpPerAuthorAndThread() = runTest {
        val envelope = kid.message(seq = 4)
        var asked: Pair<DeviceId, ThreadId>? = null
        val result = verifier.verify(MessageJson.toJsonObject(envelope), 0) { device, thread ->
            asked = device to thread
            4
        }
        assertIs<VerifiedMessage.Ok>(result)
        assertEquals(kid.deviceId to thread, asked)
    }

    @Test
    fun aKidDeviceMayOnlyWriteIntoItsOwnThread() = runTest {
        val intoSibling = kid.message(header = kid.header(threadId = ThreadId.of(otherKid)))
        assertEquals(QuarantineReason.WRONG_THREAD, reasonFor(intoSibling))
        val intoKid = sibling.message(header = sibling.header(threadId = thread))
        assertEquals(QuarantineReason.WRONG_THREAD, reasonFor(intoKid))
    }

    @Test
    fun seqGapsAreHeldAndOldSeqsAreDuplicates() = runTest {
        val third = kid.message(seq = 3)
        val held = assertIs<VerifiedMessage.Held>(verify(third, expectedSeq = 2))
        assertEquals(QuarantineReason.SEQ_GAP, held.reason)
        assertEquals(third, held.envelope)
        assertEquals(VerifiedMessage.Duplicate(MessageId("m-3")), verify(third, expectedSeq = 4))
        assertIs<VerifiedMessage.Ok>(verify(third, expectedSeq = 3))
    }

    @Test
    fun aGapIsOnlyHeldForAnAuthenticatedMessage() = runTest {
        val forged = kid.message(seq = 9).copy(signature = parent.message().signature)
        assertEquals(
            QuarantineReason.BAD_DEVICE_SIGNATURE,
            assertIs<VerifiedMessage.Quarantined>(verify(forged, expectedSeq = 1)).reason,
        )
    }

    @Test
    fun badSignaturesAreQuarantined() = runTest {
        val resigned = kid.message().copy(lamport = 5)
        assertEquals(QuarantineReason.BAD_DEVICE_SIGNATURE, reasonFor(resigned))
        val unsigned = kid.message().copy(signature = null)
        assertEquals(QuarantineReason.BAD_DEVICE_SIGNATURE, reasonFor(unsigned))
        val otherKey = MessageEnvelope.of(kid.header(), 1, 1, sentAt, ByteArray(8)).signWith(SoftwareKey()::sign)
        assertEquals(QuarantineReason.BAD_DEVICE_SIGNATURE, reasonFor(otherKey))
    }

    @Test
    fun unknownAndPendingDevicesAreHeldRevokedOnesQuarantined() = runTest {
        val stranger = ChatDevice("stranger", Role.PARENT)
        assertEquals(QuarantineReason.UNKNOWN_DEVICE, assertIs<VerifiedMessage.Held>(verify(stranger.message())).reason)
        registry.register(stranger.registered(DeviceStatus.Pending))
        assertEquals(
            QuarantineReason.DEVICE_NOT_ACTIVE,
            assertIs<VerifiedMessage.Held>(verify(stranger.message())).reason,
        )
        registry.register(kid.registered(DeviceStatus.Revoked(atLamport = 10)))
        assertIs<VerifiedMessage.Ok>(verify(kid.message(seq = 1, lamport = 9)))
        assertEquals(QuarantineReason.DEVICE_NOT_ACTIVE, reasonFor(kid.message(seq = 2, lamport = 10)))
    }

    @Test
    fun claimsMustMatchTheRegistry() = runTest {
        val wrongUid = kid.message(header = kid.header().copy(authorUid = UserId("parent-uid")))
        assertEquals(QuarantineReason.UID_MISMATCH, reasonFor(wrongUid))
        val kidAsParent = kid.message(header = kid.header(authorRole = Role.PARENT))
        assertEquals(QuarantineReason.ROLE_NOT_ALLOWED, reasonFor(kidAsParent))
        val otherHousehold = parent.message(header = parent.header().copy(householdId = HouseholdId("house-2")))
        assertEquals(QuarantineReason.WRONG_HOUSEHOLD, reasonFor(otherHousehold))
    }

    @Test
    fun theLamportJumpIsBounded() = runTest {
        assertIs<VerifiedMessage.Ok>(verify(parent.message(seq = 1, lamport = 150), localMaxLamport = 50))
        assertEquals(QuarantineReason.LAMPORT_TOO_FAR_AHEAD, reasonFor(parent.message(seq = 1, lamport = 151), 50))
    }

    @Test
    fun undecodableAndNewerSchemaTreesAreQuarantined() = runTest {
        val tree = MessageJson.toJsonObject(kid.message())
        val newer = JsonObject(tree + ("schemaVersion" to JsonPrimitive(2)) + ("reactions" to JsonPrimitive(1)))
        val schema = assertIs<VerifiedMessage.Quarantined>(verifier.verify(newer, 0) { _, _ -> 1 })
        assertEquals(QuarantineReason.SCHEMA_UNSUPPORTED, schema.reason)
        assertEquals(MessageId("m-1"), schema.messageId)
        val newerDecodable = MessageJson.toJsonObject(kid.message().copy(schemaVersion = 2))
        assertEquals(
            QuarantineReason.SCHEMA_UNSUPPORTED,
            assertIs<VerifiedMessage.Quarantined>(verifier.verify(newerDecodable, 0) { _, _ -> 1 }).reason,
        )
        val broken = JsonObject(tree + ("seq" to JsonPrimitive("one")))
        val undecodable =
            assertIs<VerifiedMessage.Quarantined>(verifier.verify(broken, 0) { _, _ -> error("not asked") })
        assertEquals(QuarantineReason.UNDECODABLE, undecodable.reason)
        assertEquals(kid.deviceId, undecodable.authorDeviceId)
        val empty = assertIs<VerifiedMessage.Quarantined>(verifier.verify(JsonObject(emptyMap()), 0) { _, _ -> 1 })
        assertNull(empty.messageId)
    }

    @Test
    fun theVerifiedDeviceIsTheRegisteredOne() = runTest {
        val ok = assertIs<VerifiedMessage.Ok>(verify(parent.message()))
        assertSame(registry.device(parent.deviceId), ok.device)
    }
}

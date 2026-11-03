package dev.kpetriv.sprout.core.sync

import dev.kpetriv.sprout.core.domainledger.DeviceRegistry
import dev.kpetriv.sprout.core.domainledger.DeviceStatus
import dev.kpetriv.sprout.core.domainledger.RegisteredDevice
import dev.kpetriv.sprout.core.domainledger.RegisteredParentKey
import dev.kpetriv.sprout.core.model.DeviceId
import dev.kpetriv.sprout.core.model.Role
import dev.kpetriv.sprout.core.model.SubmissionStatus
import dev.kpetriv.sprout.core.model.UserId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame

class RemoteDocumentVerifierTest {
    private val parent = TestIdentity("parent-device", "parent-uid", Role.PARENT)
    private val kid = TestIdentity("kid-device", "kid-uid", Role.KID, kidId = KID_1)
    private val pending = TestIdentity("pending-device", "parent-uid", Role.PARENT, status = DeviceStatus.Pending)
    private val revoked = TestIdentity("revoked-device", "parent-uid", Role.PARENT, status = DeviceStatus.Revoked(10))

    private val registry = object : DeviceRegistry {
        private val devices = listOf(parent, kid, pending, revoked).associate { it.deviceId to it.registered }

        override suspend fun device(deviceId: DeviceId): RegisteredDevice? = devices[deviceId]

        override suspend fun parentKeys(): List<RegisteredParentKey> = emptyList()
    }
    private val verifier = RemoteDocumentVerifier(registry, SoftwareSignatureCheck, maxLamportJump = 100)

    private suspend fun verify(envelope: DocumentEnvelope, localMax: Long = 0) = verifier.verify(envelope, localMax)

    private suspend fun assertRejected(reason: DocumentRejection, envelope: DocumentEnvelope, localMax: Long = 0) {
        assertEquals(VerifiedDocument.Rejected(reason), verify(envelope, localMax))
    }

    @Test
    fun parentDocumentVerifies() = runTest {
        val envelope = parent.signed(DocTypes.REWARD, "reward-1", lamport = 5)
        val ok = assertIs<VerifiedDocument.Ok>(verify(envelope))
        assertSame(envelope, ok.envelope)
        assertEquals(parent.deviceId, ok.device.deviceId)
    }

    @Test
    fun unsigned() = runTest {
        assertRejected(DocumentRejection.UNSIGNED, parent.envelope(DocTypes.REWARD, "reward-1", lamport = 5))
    }

    @Test
    fun unknownDevice() = runTest {
        val stranger = TestIdentity("stranger", "stranger-uid", Role.PARENT)
        assertRejected(DocumentRejection.UNKNOWN_DEVICE, stranger.signed(DocTypes.REWARD, "reward-1", lamport = 5))
    }

    @Test
    fun deviceNotActive() = runTest {
        assertRejected(DocumentRejection.DEVICE_NOT_ACTIVE, pending.signed(DocTypes.REWARD, "reward-1", lamport = 5))
        assertRejected(DocumentRejection.DEVICE_NOT_ACTIVE, revoked.signed(DocTypes.REWARD, "reward-1", lamport = 10))
        // Written before the revocation: still valid.
        assertIs<VerifiedDocument.Ok>(verify(revoked.signed(DocTypes.REWARD, "reward-1", lamport = 9)))
    }

    @Test
    fun uidMismatch() = runTest {
        val claimed = parent.envelope(DocTypes.REWARD, "reward-1", lamport = 5).copy(authorUid = UserId("kid-uid"))
        assertRejected(DocumentRejection.UID_MISMATCH, claimed.signWith(parent.key::sign))
    }

    @Test
    fun roleNotAllowed() = runTest {
        DocTypes.all.filter { it != DocTypes.CHORE_SUBMISSION }.forEach { type ->
            assertRejected(DocumentRejection.ROLE_NOT_ALLOWED, kid.signed(type, "doc-1", lamport = 5))
        }
    }

    @Test
    fun badSignature() = runTest {
        val signedByOther = parent.envelope(DocTypes.REWARD, "reward-1", lamport = 5).signWith(kid.key::sign)
        assertRejected(DocumentRejection.BAD_SIGNATURE, signedByOther)
        val altered = parent.signed(DocTypes.REWARD, "reward-1", lamport = 5).copy(lamport = 6)
        assertRejected(DocumentRejection.BAD_SIGNATURE, altered)
    }

    @Test
    fun lamportTooFarAhead() = runTest {
        assertIs<VerifiedDocument.Ok>(verify(parent.signed(DocTypes.REWARD, "reward-1", lamport = 150), localMax = 50))
        assertRejected(
            DocumentRejection.LAMPORT_TOO_FAR_AHEAD,
            parent.signed(DocTypes.REWARD, "reward-1", lamport = 151),
            localMax = 50,
        )
        assertRejected(
            DocumentRejection.LAMPORT_TOO_FAR_AHEAD,
            parent.signed(DocTypes.REWARD, "reward-1", lamport = Long.MAX_VALUE),
            localMax = 50,
        )
    }

    @Test
    fun unknownDocType() = runTest {
        assertRejected(DocumentRejection.UNKNOWN_DOC_TYPE, parent.signed("ledger_override", "doc-1", lamport = 5))
    }

    @Test
    fun kidMayWriteItsOwnSubmittedSubmission() = runTest {
        val own = kid.signed(DocTypes.CHORE_SUBMISSION, "sub-1", lamport = 5, payload = submission().toPayload())
        assertIs<VerifiedDocument.Ok>(verify(own))
    }

    @Test
    fun kidMayNotWriteAnotherKidsSubmission() = runTest {
        val payload = submission(kidId = KID_2).toPayload()
        assertRejected(
            DocumentRejection.ROLE_NOT_ALLOWED,
            kid.signed(DocTypes.CHORE_SUBMISSION, "sub-1", lamport = 5, payload = payload),
        )
    }

    @Test
    fun kidMayNotDecideASubmission() = runTest {
        listOf(SubmissionStatus.APPROVED, SubmissionStatus.REJECTED).forEach { status ->
            val payload = submission(status = status).toPayload()
            assertRejected(
                DocumentRejection.ROLE_NOT_ALLOWED,
                kid.signed(DocTypes.CHORE_SUBMISSION, "sub-1", lamport = 5, payload = payload),
            )
        }
    }

    @Test
    fun kidMayNotWriteUnderAnotherIdOrDelete() = runTest {
        val payload = submission().toPayload()
        assertRejected(
            DocumentRejection.ROLE_NOT_ALLOWED,
            kid.signed(DocTypes.CHORE_SUBMISSION, "sub-other", lamport = 5, payload = payload),
        )
        assertRejected(
            DocumentRejection.ROLE_NOT_ALLOWED,
            kid.signed(DocTypes.CHORE_SUBMISSION, "sub-1", lamport = 5, payload = payload, deleted = true),
        )
    }

    @Test
    fun parentMayDecideASubmission() = runTest {
        val payload = submission(status = SubmissionStatus.APPROVED).toPayload()
        assertIs<VerifiedDocument.Ok>(verify(parent.signed(DocTypes.CHORE_SUBMISSION, "sub-1", 5, payload)))
    }

    @Test
    fun writerRoles() {
        DocTypes.all.forEach { type ->
            val expected = if (type == DocTypes.CHORE_SUBMISSION) Role.KID else Role.PARENT
            assertEquals(expected, DocTypes.writerRole(type))
        }
        assertEquals(10, DocTypes.all.toSet().size)
        assertFailsWith<IllegalArgumentException> { DocTypes.writerRole("nope") }
    }
}

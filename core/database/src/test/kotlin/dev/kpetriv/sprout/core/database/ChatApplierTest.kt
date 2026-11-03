package dev.kpetriv.sprout.core.database

import dev.kpetriv.sprout.core.database.entity.AuditAction
import dev.kpetriv.sprout.core.database.entity.MessageStatus
import dev.kpetriv.sprout.core.domainledger.AttestationLevel
import dev.kpetriv.sprout.core.domainledger.DeviceOwner
import dev.kpetriv.sprout.core.domainledger.DeviceStatus
import dev.kpetriv.sprout.core.domainledger.QuarantineReason
import dev.kpetriv.sprout.core.domainledger.RegisteredDevice
import dev.kpetriv.sprout.core.domainledger.chat.MessageJson
import dev.kpetriv.sprout.core.domainledger.chat.ThreadKeyDocument
import dev.kpetriv.sprout.core.model.DeviceId
import dev.kpetriv.sprout.core.model.HouseholdId
import dev.kpetriv.sprout.core.model.MessageId
import dev.kpetriv.sprout.core.model.Role
import dev.kpetriv.sprout.core.model.UserId
import dev.kpetriv.sprout.core.sync.DocTypes
import dev.kpetriv.sprout.core.sync.MessageApplyReport
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import kotlin.io.encoding.Base64
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours

@RunWith(RobolectricTestRunner::class)
class ChatApplierTest {
    private val parent = parentDevice()
    private val kid = kidDevice()

    @After
    fun close() {
        parent.close()
        kid.close()
    }

    /** Both devices registered with encryption keys; the parent knows the kid and published epoch 0. */
    private suspend fun linked() {
        registerChatDevices(parent, kid)
        parent.addKid(KID_X.value)
        assertEquals(1, parent.threadKeyMaintenance())
    }

    private suspend fun auditRows(action: AuditAction) =
        parent.database.auditLogDao().all().filter { it.action == action } +
            kid.database.auditLogDao().all().filter { it.action == action }

    @Test
    fun aMessageFromTheParentIsVerifiedDecryptedAndAdvancesTheSequence() = runTest {
        linked()
        val id = parent.messageWrites.send(THREAD_X, text("Dinner at six"))

        kid.applier.applyDocuments(parent.queuedDocuments())
        val report = kid.applier.applyMessages(parent.queuedMessages())

        assertEquals(MessageApplyReport(applied = 1), report)
        val row = assertNotNull(kid.row(id.value))
        assertEquals(MessageStatus.RECEIVED, row.status)
        assertEquals(text("Dinner at six"), row.content())
        assertEquals(2, kid.applier.expectedSeq(parent.deviceId, THREAD_X))
        // A second pull of the same page is a duplicate.
        assertEquals(MessageApplyReport(duplicates = 1), kid.applier.applyMessages(parent.queuedMessages()))
        assertFalse(row.toString().contains("Dinner"))
    }

    @Test
    fun aMessageWithoutItsKeyIsLockedAndDecryptedWhenTheKeyArrives() = runTest {
        linked()
        val id = parent.messageWrites.send(THREAD_X, text("Locked for now"))

        kid.applier.applyMessages(parent.queuedMessages())
        val locked = assertNotNull(kid.row(id.value))
        assertEquals(MessageStatus.LOCKED, locked.status)
        assertNull(locked.plaintext)

        kid.applier.applyDocuments(parent.queuedDocuments())
        val opened = assertNotNull(kid.row(id.value))
        assertEquals(MessageStatus.RECEIVED, opened.status)
        assertEquals(text("Locked for now"), opened.content())
    }

    @Test
    fun theWrapIsOpenedWithTheRegisteredKeyOnceTheRegistryHasIt() = runTest {
        // The kid's mirror lists the parent without an encryption key at first.
        val parentWithoutKey = parent.registered()
        registerChatDevices(parent, kid)
        kid.applier.applyRegistry(listOf(parentWithoutKey), emptyList())
        parent.addKid(KID_X.value)
        parent.threadKeyMaintenance()
        val id = parent.messageWrites.send(THREAD_X, text("Hi"))

        kid.pullChatFrom(parent)
        assertEquals(MessageStatus.LOCKED, kid.row(id.value)?.status)
        assertNull(kid.database.threadKeyDao().all().single().key)

        kid.applier.applyRegistry(listOf(parent.registeredWithKey()), emptyList())
        assertNotNull(kid.database.threadKeyDao().all().single().key)
        assertEquals(MessageStatus.RECEIVED, kid.row(id.value)?.status)
    }

    @Test
    fun aGapIsHeldUntilTheMissingMessageArrives() = runTest {
        linked()
        val first = parent.messageWrites.send(THREAD_X, text("one"))
        val second = parent.messageWrites.send(THREAD_X, text("two"))
        val (m1, m2) = parent.queuedMessages()
        kid.applier.applyDocuments(parent.queuedDocuments())

        assertEquals(MessageApplyReport(held = 1), kid.applier.applyMessages(listOf(m2)))
        val held = assertNotNull(kid.row(second.value))
        assertEquals(MessageStatus.HELD, held.status)
        assertEquals(QuarantineReason.SEQ_GAP.name, held.reason)

        assertEquals(MessageApplyReport(applied = 2), kid.applier.applyMessages(listOf(m1)))
        assertEquals(MessageStatus.RECEIVED, kid.row(first.value)?.status)
        assertEquals(text("two"), kid.row(second.value)?.content())
        assertEquals(3, kid.applier.expectedSeq(parent.deviceId, THREAD_X))
    }

    @Test
    fun aTamperedMessageIsQuarantinedWithoutItsPayloadAndAudited() = runTest {
        linked()
        val id = parent.messageWrites.send(THREAD_X, text("secret words"))
        val envelope = parent.queuedMessages().single().envelope()
        // Any change after signing breaks the device signature.
        val tampered = envelope.copy(lamport = envelope.lamport + 1)

        val report = kid.applier.applyMessages(listOf(MessageJson.toJsonObject(tampered)))

        assertEquals(MessageApplyReport(quarantined = 1), report)
        val row = assertNotNull(kid.row(id.value))
        assertEquals(MessageStatus.QUARANTINED, row.status)
        assertEquals("", row.ciphertext)
        assertNull(row.plaintext)
        assertNull(row.signature)
        val audit = auditRows(AuditAction.MESSAGE_QUARANTINED).single()
        assertEquals(id.value, audit.target)
        assertTrue(audit.detailsJson.contains(QuarantineReason.BAD_DEVICE_SIGNATURE.name))
        assertFalse(audit.detailsJson.contains(envelope.ciphertext))
        // The genuine copy still applies over the quarantined row.
        kid.applier.applyDocuments(parent.queuedDocuments())
        kid.applier.applyMessages(parent.queuedMessages())
        assertEquals(MessageStatus.RECEIVED, kid.row(id.value)?.status)
    }

    @Test
    fun aGapBehindAQuarantinedMessageIsSkippedAfterTheTimeout() = runTest {
        linked()
        parent.messageWrites.send(THREAD_X, text("one"))
        val second = parent.messageWrites.send(THREAD_X, text("two"))
        val (m1, m2) = parent.queuedMessages().map { it.envelope() }
        kid.applier.applyDocuments(parent.queuedDocuments())
        kid.applier.applyMessages(listOf(MessageJson.toJsonObject(m1.copy(lamport = m1.lamport + 1))))
        kid.applier.applyMessages(listOf(MessageJson.toJsonObject(m2)))
        assertEquals(MessageStatus.HELD, kid.row(second.value)?.status)

        kid.applier.retryHeld()
        assertEquals(MessageStatus.HELD, kid.row(second.value)?.status, "still within the timeout")

        kid.clock.advanceBy(25.hours)
        kid.applier.retryHeld()

        assertEquals(MessageStatus.RECEIVED, kid.row(second.value)?.status)
        val skipped = auditRows(AuditAction.MESSAGE_GAP_SKIPPED).single()
        assertTrue(skipped.detailsJson.contains("\"fromSeq\":1") && skipped.detailsJson.contains("\"toSeq\":2"))
        // The genuine first message arriving late is now a duplicate.
        assertEquals(
            MessageApplyReport(duplicates = 1),
            kid.applier.applyMessages(listOf(MessageJson.toJsonObject(m1))),
        )
    }

    @Test
    fun heldMessagesAreCappedPerAuthor() = runTest {
        linked()
        parent.messageWrites.send(THREAD_X, text("template"))
        val template = parent.queuedMessages().single().envelope()
        val crafted = (2L..ChatApplier.MAX_HELD_PER_AUTHOR + 2).map { seq ->
            MessageJson.toJsonObject(parent.resign(template) { it.copy(seq = seq, messageId = MessageId("m-$seq")) })
        }

        val report = kid.applier.applyMessages(crafted)

        assertEquals(ChatApplier.MAX_HELD_PER_AUTHOR + 1, report.held)
        assertEquals(ChatApplier.MAX_HELD_PER_AUTHOR, kid.database.messageDao().heldCountFor(parent.deviceId))
        assertNull(kid.row("m-${ChatApplier.MAX_HELD_PER_AUTHOR + 2}"), "the one farthest ahead was dropped")
        assertNotNull(kid.row("m-2"))
        assertEquals(1, auditRows(AuditAction.HELD_DROPPED).size)
    }

    @Test
    fun aMessageFromAnUnknownDeviceIsHeldUntilTheRegistryKnowsIt() = runTest {
        registerChatDevices(parent)
        parent.addKid(KID_X.value)
        parent.threadKeyMaintenance()
        val id = parent.messageWrites.send(THREAD_X, text("hello"))
        kid.applier.applyRegistry(listOf(kid.registeredWithKey()), emptyList())

        assertEquals(MessageApplyReport(held = 1), kid.applier.applyMessages(parent.queuedMessages()))
        assertEquals(QuarantineReason.UNKNOWN_DEVICE.name, kid.row(id.value)?.reason)

        kid.applier.applyRegistry(listOf(parent.registeredWithKey()), emptyList())
        kid.applier.retryHeld()
        assertEquals(MessageStatus.LOCKED, kid.row(id.value)?.status, "verified, but no wrap for the kid yet")
    }

    @Test
    fun aThreadKeyDocumentWhoseCreatorIsNotItsAuthorIsRefused() = runTest {
        linked()
        val genuine = parent.queuedDocuments().single { it.docType == DocTypes.THREAD_KEY }
        val document = assertNotNull(ThreadKeyDocument.fromPayload(genuine.payload))
        val other = DeviceId("other-parent")
        val forged = ThreadKeyDocument(
            document.threadId,
            document.epoch,
            other,
            document.wraps + (other to Base64.Default.encode(ByteArray(48))),
        )
        val unsigned = genuine.copy(payload = forged.toPayload(), signature = null, lamport = genuine.lamport + 1)
        val envelope = unsigned.copy(signature = Base64.Default.encode(parent.signer.sign(unsigned.signedPayload())))

        kid.applier.applyDocuments(listOf(envelope))

        assertTrue(kid.database.threadKeyDao().all().isEmpty())
        assertTrue(
            kid.database.auditLogDao().all().any {
                it.action == AuditAction.DOCUMENT_REJECTED &&
                    it.detailsJson.contains(RoomRemoteApplier.MALFORMED_PAYLOAD)
            },
        )
    }

    @Test
    fun aRevokedKidDeviceCannotDecryptTheNextEpoch() = runTest {
        linked()
        val before = parent.messageWrites.send(THREAD_X, text("before"))
        kid.pullChatFrom(parent)
        assertEquals(MessageStatus.RECEIVED, kid.row(before.value)?.status)

        val revoked = kid.registeredWithKey(DeviceStatus.Revoked(atLamport = 1_000))
        parent.applier.applyRegistry(listOf(revoked), emptyList())
        assertEquals(1, parent.threadKeyMaintenance())
        val rotated = parent.database.threadKeyDao().all().single { it.epoch == 1L }
        assertFalse(rotated.wrapSetJson.contains(kid.deviceId.value))
        val after = parent.messageWrites.send(THREAD_X, text("after"))
        assertEquals(1, parent.queuedMessages().last().envelope().epoch)

        kid.pullChatFrom(parent)

        assertEquals(MessageStatus.LOCKED, kid.row(after.value)?.status)
        assertNull(kid.database.threadKeyDao().all().single { it.epoch == 1L }.key)
        assertEquals(text("before"), kid.row(before.value)?.content(), "old epochs stay readable")
    }

    @Test
    fun theRegistryMirrorKeepsOnlyKeysBoundToTheSessionHousehold() = runTest {
        val foreign = RegisteredDevice(
            deviceId = DeviceId("foreign"),
            owner = DeviceOwner(UserId("x"), Role.PARENT),
            publicKey = parent.keyStore.publicKey().encoded,
            status = DeviceStatus.Active,
            attestation = AttestationLevel.NONE,
            encryptionKey = TestHousehold(
                RuntimeEnvironment.getApplication(),
                sessionFor = TestHousehold.parentSession(HouseholdId("elsewhere"), "x"),
            ).use { it.boundEncryptionKey() },
        )
        kid.applier.applyRegistry(listOf(foreign, parent.registeredWithKey()), emptyList())

        val devices = kid.registry.devicesOf(HOME)
        assertNull(devices.single { it.deviceId == foreign.deviceId }.encryptionKey)
        assertNotNull(devices.single { it.deviceId == parent.deviceId }.verifiedEncryptionKey(kid.signatureCheck))
        assertTrue(kid.registry.devicesOf(HouseholdId("elsewhere")).isEmpty())
    }
}

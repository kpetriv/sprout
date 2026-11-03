package dev.kpetriv.sprout.core.database

import dev.kpetriv.sprout.core.crypto.AesGcmAead
import dev.kpetriv.sprout.core.crypto.SymmetricKey
import dev.kpetriv.sprout.core.database.entity.AuditAction
import dev.kpetriv.sprout.core.database.entity.MessageStatus
import dev.kpetriv.sprout.core.domainledger.AttestationLevel
import dev.kpetriv.sprout.core.domainledger.BoundEncryptionKey
import dev.kpetriv.sprout.core.domainledger.DeviceOwner
import dev.kpetriv.sprout.core.domainledger.DeviceStatus
import dev.kpetriv.sprout.core.domainledger.EncryptionKeyBindingPayload
import dev.kpetriv.sprout.core.domainledger.QuarantineReason
import dev.kpetriv.sprout.core.domainledger.RegisteredDevice
import dev.kpetriv.sprout.core.domainledger.chat.MessageEnvelope
import dev.kpetriv.sprout.core.domainledger.chat.MessageJson
import dev.kpetriv.sprout.core.domainledger.chat.MessageKind
import dev.kpetriv.sprout.core.domainledger.chat.MessagePadding
import dev.kpetriv.sprout.core.domainledger.chat.ThreadKeyDocument
import dev.kpetriv.sprout.core.domainledger.chat.ThreadMembers
import dev.kpetriv.sprout.core.model.DeviceId
import dev.kpetriv.sprout.core.model.HouseholdId
import dev.kpetriv.sprout.core.model.MessageId
import dev.kpetriv.sprout.core.model.Role
import dev.kpetriv.sprout.core.model.UserId
import dev.kpetriv.sprout.core.sync.DocTypes
import dev.kpetriv.sprout.core.sync.MessageApplyReport
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.security.SecureRandom
import kotlin.io.encoding.Base64
import kotlin.test.assertContentEquals
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
        parent.confirmThreadKeys()
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
        parent.applier.applyRegistry(listOf(parent.registeredWithKey(), kid.registeredWithKey()), emptyList())
        kid.applier.applyRegistry(listOf(parentWithoutKey, kid.registeredWithKey()), emptyList())
        parent.addKid(KID_X.value)
        parent.threadKeyMaintenance()
        parent.confirmThreadKeys()
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
        // The genuine first message arriving late is accepted for the skipped seq, marked late (audit L3).
        assertEquals(
            MessageApplyReport(applied = 1),
            kid.applier.applyMessages(listOf(MessageJson.toJsonObject(m1))),
        )
        val late = assertNotNull(kid.row(m1.messageId.value))
        assertEquals(MessageStatus.RECEIVED, late.status)
        assertTrue(late.late)
        assertEquals("", kid.database.messageDao().seq(THREAD_X.value, parent.deviceId)?.skippedRanges)
        // Once more: now a plain duplicate.
        assertEquals(
            MessageApplyReport(duplicates = 1),
            kid.applier.applyMessages(listOf(MessageJson.toJsonObject(m1))),
        )
    }

    /**
     * The parent sends three messages; the kid gets the first and the third, and after the gap timeout skips seq 2.
     * Returns the three envelopes as sent.
     */
    private suspend fun withSkippedSecond(): List<MessageEnvelope> {
        linked()
        listOf("one", "two", "three").forEach { parent.messageWrites.send(THREAD_X, text(it)) }
        val sent = parent.queuedMessages().map { it.envelope() }
        kid.applier.applyDocuments(parent.queuedDocuments())
        kid.applier.applyMessages(listOf(sent[0], sent[2]).map(MessageJson::toJsonObject))
        kid.clock.advanceBy(25.hours)
        kid.applier.retryHeld()
        assertEquals(MessageStatus.RECEIVED, kid.row(sent[2].messageId.value)?.status)
        return sent
    }

    @Test
    fun aLateMessageIsShownNoEarlierThanTheAuthorsPreviousMessage() = runTest {
        val (first, second, _) = withSkippedSecond()
        // The skipped seq arrives late, signed with a createdAt a day before the first message.
        val backdated = parent.resign(second) { it.copy(createdAt = first.createdAt - 24.hours) }

        assertEquals(
            MessageApplyReport(applied = 1),
            kid.applier.applyMessages(listOf(MessageJson.toJsonObject(backdated))),
        )

        val late = assertNotNull(kid.row(second.messageId.value))
        assertTrue(late.late)
        assertEquals(text("two"), late.content())
        assertEquals(kid.row(first.messageId.value)?.displayedAtEpochMs, late.displayedAtEpochMs, "clamped (A2-L1)")
        val shown = kid.messageReader.observe(THREAD_X, 10).first().messages
        assertEquals(listOf("one", "two", "three").map(::text), shown.map { it.content })
        assertEquals(listOf(false, true, false), shown.map { it.late })
    }

    @Test
    fun aLateMessageBelowThePreviousMessageIsQuarantinedAndItsSeqUsedUp() = runTest {
        val (first, second, _) = withSkippedSecond()
        val below = parent.resign(second) { it.copy(lamport = first.lamport) }

        assertEquals(
            MessageApplyReport(quarantined = 1),
            kid.applier.applyMessages(listOf(MessageJson.toJsonObject(below))),
        )
        val row = assertNotNull(kid.row(second.messageId.value))
        assertEquals(MessageStatus.QUARANTINED, row.status)
        assertEquals(ChatApplier.OUT_OF_ORDER, row.reason)
        // The seq is used up: another copy never comes back through the late path.
        assertEquals("", kid.database.messageDao().seq(THREAD_X.value, parent.deviceId)?.skippedRanges)
        kid.applier.applyMessages(listOf(MessageJson.toJsonObject(second)))
        assertEquals(MessageStatus.QUARANTINED, kid.row(second.messageId.value)?.status)
    }

    @Test
    fun aLateMessageAboveTheNextMessageIsQuarantined() = runTest {
        val (_, second, third) = withSkippedSecond()
        val above = parent.resign(second) { it.copy(lamport = third.lamport + 1) }

        assertEquals(
            MessageApplyReport(quarantined = 1),
            kid.applier.applyMessages(listOf(MessageJson.toJsonObject(above))),
        )
        assertEquals(ChatApplier.OUT_OF_ORDER, kid.row(second.messageId.value)?.reason)
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
        parent.confirmThreadKeys()
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
        parent.confirmThreadKeys()
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
        val elsewhere = TestHousehold(
            RuntimeEnvironment.getApplication(),
            sessionFor = TestHousehold.parentSession(HouseholdId("elsewhere"), "x"),
        )
        val foreign = RegisteredDevice(
            deviceId = elsewhere.deviceId,
            owner = DeviceOwner(UserId("x"), Role.PARENT),
            publicKey = elsewhere.keyStore.publicKey().encoded,
            status = DeviceStatus.Active,
            attestation = AttestationLevel.NONE,
            encryptionKey = elsewhere.use { it.boundEncryptionKey() },
        )
        kid.applier.applyRegistry(listOf(foreign, parent.registeredWithKey()), emptyList())

        val devices = kid.registry.devicesOf(HOME)
        assertNull(devices.single { it.deviceId == foreign.deviceId }.encryptionKey)
        assertNotNull(devices.single { it.deviceId == parent.deviceId }.verifiedEncryptionKey(kid.signatureCheck))
        assertTrue(kid.registry.devicesOf(HouseholdId("elsewhere")).isEmpty())
    }

    /** [template] re-signed by the parent with [plaintext] encrypted (padded) under its epoch 0 key, unchecked. */
    private suspend fun craftedWithPlaintext(
        template: MessageEnvelope,
        plaintext: String,
        seq: Long,
        id: String,
        change: (MessageEnvelope) -> MessageEnvelope = { it },
    ) = requireNotNull(parent.database.threadKeyDao().all().single().key).let { key ->
        parent.resign(template) { envelope -> craft(change(envelope), key, plaintext, seq, id) }
    }

    private fun craft(envelope: MessageEnvelope, key: ByteArray, plaintext: String, seq: Long, id: String) = run {
        val changed = envelope.copy(seq = seq, messageId = MessageId(id))
        val padded = MessagePadding.pad(MessageKind.TEXT, plaintext.encodeToByteArray())
        val ciphertext = AesGcmAead(SecureRandom()).encrypt(
            SymmetricKey.copyOf(key),
            padded,
            changed.header.associatedData(),
        )
        changed.copy(ciphertext = Base64.Default.encode(ciphertext))
    }

    private fun later(by: Long): (MessageEnvelope) -> MessageEnvelope = { it.copy(lamport = it.lamport + by) }

    @Test
    fun anAuthenticatedPlaintextThatCannotBeEncodedIsQuarantinedWithoutStallingTheBatch() = runTest {
        linked()
        parent.messageWrites.send(THREAD_X, text("one"))
        val template = parent.queuedMessages().single().envelope()
        // A lone surrogate escaped in JSON, and a text over the length limit: both authenticate, neither decodes.
        val surrogate = craftedWithPlaintext(template, """{"text":"\ud800"}""", seq = 2, id = "m-surrogate", later(2))
        val oversized =
            craftedWithPlaintext(template, """{"text":"${"x".repeat(1_001)}"}""", seq = 3, id = "m-long", later(3))
        val fine = craftedWithPlaintext(template, """{"text":"fine"}""", seq = 4, id = "m-fine", later(4))
        kid.applier.applyDocuments(parent.queuedDocuments())

        val report = kid.applier.applyMessages(
            (listOf(template, surrogate, oversized, fine)).map(MessageJson::toJsonObject),
        )

        assertEquals(MessageApplyReport(applied = 2, quarantined = 2), report)
        for (id in listOf("m-surrogate", "m-long")) {
            val row = assertNotNull(kid.row(id))
            assertEquals(MessageStatus.QUARANTINED, row.status)
            assertEquals(QuarantineReason.UNDECODABLE.name, row.reason)
        }
        assertEquals(text("fine"), kid.row("m-fine")?.content())
        assertEquals(5, kid.applier.expectedSeq(parent.deviceId, THREAD_X))
    }

    @Test
    fun aLockedMessageThatTurnsOutUndecodableIsQuarantinedWhenTheKeyArrives() = runTest {
        linked()
        parent.messageWrites.send(THREAD_X, text("one"))
        val template = parent.queuedMessages().single().envelope()
        val surrogate = craftedWithPlaintext(template, """{"text":"\ud800"}""", seq = 2, id = "m-surrogate", later(2))
        val fine = craftedWithPlaintext(template, """{"text":"fine"}""", seq = 3, id = "m-fine", later(3))
        kid.applier.applyMessages(listOf(template, surrogate, fine).map(MessageJson::toJsonObject))
        assertEquals(MessageStatus.LOCKED, kid.row("m-surrogate")?.status)

        kid.applier.applyDocuments(parent.queuedDocuments())

        assertEquals(MessageStatus.QUARANTINED, kid.row("m-surrogate")?.status)
        assertEquals("", kid.row("m-surrogate")?.ciphertext)
        assertEquals(text("fine"), kid.row("m-fine")?.content())
        assertEquals(text("one"), kid.row(template.messageId.value)?.content())
    }

    @Test
    fun aMessageWhoseLamportDoesNotGrowWithItsSeqIsQuarantinedAndItsTimeIsClamped() = runTest {
        linked()
        parent.messageWrites.send(THREAD_X, text("one"))
        val first = parent.queuedMessages().single().envelope()
        kid.applier.applyDocuments(parent.queuedDocuments())
        // Seq 2 claims an older lamport than seq 1; seq 3 is in order but backdated an hour.
        val backwards = craftedWithPlaintext(first, """{"text":"back"}""", seq = 2, id = "m-back") {
            it.copy(lamport = first.lamport - 1)
        }
        val backdated = craftedWithPlaintext(first, """{"text":"old"}""", seq = 3, id = "m-old") {
            it.copy(lamport = first.lamport + 5, createdAt = first.createdAt - 1.hours)
        }

        kid.applier.applyMessages(listOf(first, backwards, backdated).map(MessageJson::toJsonObject))

        assertEquals(MessageStatus.QUARANTINED, kid.row("m-back")?.status)
        assertEquals(ChatApplier.OUT_OF_ORDER, kid.row("m-back")?.reason)
        val shown = assertNotNull(kid.row("m-old"))
        assertEquals(MessageStatus.RECEIVED, shown.status)
        assertEquals(first.createdAt.toEpochMilliseconds(), shown.displayedAtEpochMs, "never shown before seq 1")
        assertEquals(4, kid.applier.expectedSeq(parent.deviceId, THREAD_X))
    }

    @Test
    fun twoMessagesForOneSeqAreAuditedAsEquivocationOnce() = runTest {
        linked()
        parent.messageWrites.send(THREAD_X, text("one"))
        val first = parent.queuedMessages().single().envelope()
        kid.applier.applyDocuments(parent.queuedDocuments())
        kid.applier.applyMessages(listOf(MessageJson.toJsonObject(first)))
        val twin = parent.resign(first) { it.copy(messageId = MessageId("m-twin")) }

        repeat(2) { kid.applier.applyMessages(listOf(MessageJson.toJsonObject(twin))) }

        assertNull(kid.row("m-twin"))
        val audit = auditRows(AuditAction.MESSAGE_EQUIVOCATION).single()
        assertEquals("m-twin", audit.target)
        assertTrue(audit.detailsJson.contains(first.messageId.value))
    }

    @Test
    fun aNewerDocumentOfTheSameCreatorWithAnotherKeyKeepsBothKeys() = runTest {
        linked()
        val firstKeyMessage = parent.messageWrites.send(THREAD_X, text("under the first key"))
        kid.pullChatFrom(parent)
        // The parent (a bug, or a lost local row) publishes epoch 0 again with a different key.
        val members = ThreadMembers.resolve(THREAD_X, parent.registry.devicesOf(HOME), parent.signatureCheck)
        val document = parent.encryptionKeys.keyPair(createIfMissing = false).use { pair ->
            parent.chatCrypto.keyring.newKey(THREAD_X, 0).use {
                parent.chatCrypto.keyring.wrapFor(HOME, it, parent.deviceId, pair.privateKey, members.members)
            }
        }
        parent.threadKeyWrites.publish(document)
        parent.confirmThreadKeys()
        val secondKeyMessage = parent.messageWrites.send(THREAD_X, text("under the second key"))

        kid.pullChatFrom(parent)

        val row = kid.database.threadKeyDao().all().single()
        assertEquals(2, row.allKeys().size)
        assertEquals(text("under the first key"), kid.row(firstKeyMessage.value)?.content())
        assertEquals(text("under the second key"), kid.row(secondKeyMessage.value)?.content())
    }

    @Test
    fun theRegistryPinsKnownKeysAndRevocationsAndDropsRowsWhoseIdIsNotTheirKeysHash() = runTest {
        linked()
        val registered = parent.registeredWithKey()
        kid.applier.applyRegistry(listOf(registered), emptyList())
        // The registry now claims another encryption key for the parent device.
        val other = TestHousehold(
            RuntimeEnvironment.getApplication(),
            sessionFor = TestHousehold.parentSession(HOME, PARENT_UID),
        )
        val otherKey = other.encryptionKeys.publicKey().bytes
        val rebound = BoundEncryptionKey(
            HOME,
            otherKey,
            parent.signer.sign(EncryptionKeyBindingPayload.of(HOME, parent.deviceId, otherKey)),
        )
        val swapped = RegisteredDevice(
            registered.deviceId,
            registered.owner,
            registered.publicKey,
            registered.status,
            registered.attestation,
            rebound,
        )
        repeat(2) { kid.applier.applyRegistry(listOf(swapped), emptyList()) }

        val pinned = assertNotNull(kid.registry.device(parent.deviceId))
        assertTrue(pinned.encryptionKeyConflict)
        assertContentEquals(registered.encryptionKey?.publicKey, pinned.encryptionKey?.publicKey)
        assertEquals(1, auditRows(AuditAction.DEVICE_KEY_CHANGED).size)
        val membership = ThreadMembers.resolve(THREAD_X, kid.registry.devicesOf(HOME), kid.signatureCheck)
        assertTrue(parent.deviceId in membership.excluded)

        // A revoked device never comes back, and its revocation lamport never moves up.
        kid.applier.applyRegistry(listOf(other.registeredWithKey(DeviceStatus.Revoked(atLamport = 50))), emptyList())
        kid.applier.applyRegistry(listOf(other.registeredWithKey(DeviceStatus.Active)), emptyList())
        kid.applier.applyRegistry(listOf(other.registeredWithKey(DeviceStatus.Revoked(atLamport = 500))), emptyList())
        assertEquals(DeviceStatus.Revoked(50), kid.registry.device(other.deviceId)?.status)
        assertEquals(1, auditRows(AuditAction.DEVICE_REVOCATION_KEPT).size)

        // A row whose id is not the SHA-256 of its key is never stored.
        val impostor = RegisteredDevice(
            DeviceId("a".repeat(64)),
            registered.owner,
            registered.publicKey,
            DeviceStatus.Active,
            registered.attestation,
        )
        kid.applier.applyRegistry(listOf(impostor), emptyList())
        assertNull(kid.registry.device(impostor.deviceId))
        assertEquals(1, auditRows(AuditAction.DEVICE_REJECTED).size)
        other.close()
    }

    @Test
    fun aWrapThatDoesNotOpenIsAuditedOncePerDocument() = runTest {
        val devices = listOf(parent.registeredWithKey(), kid.registeredWithKey())
        parent.applier.applyRegistry(devices, emptyList())
        parent.addKid(KID_X.value)
        assertEquals(1, parent.threadKeyMaintenance())
        // The kid's mirror first saw a wrong (but validly bound) key for the parent: the wrap cannot open.
        val other = TestHousehold(
            RuntimeEnvironment.getApplication(),
            sessionFor = TestHousehold.parentSession(HOME, PARENT_UID),
        )
        val otherKey = other.encryptionKeys.publicKey().bytes
        val wrong = RegisteredDevice(
            parent.deviceId,
            DeviceOwner(parent.session.uid, Role.PARENT),
            parent.keyStore.publicKey().encoded,
            DeviceStatus.Active,
            AttestationLevel.NONE,
            BoundEncryptionKey(
                HOME,
                otherKey,
                parent.signer.sign(EncryptionKeyBindingPayload.of(HOME, parent.deviceId, otherKey)),
            ),
        )
        kid.applier.applyRegistry(listOf(wrong, kid.registeredWithKey()), emptyList())
        kid.applier.applyDocuments(parent.queuedDocuments())
        repeat(3) { kid.applier.applyRegistry(listOf(kid.registeredWithKey()), emptyList()) }

        assertEquals(1, auditRows(AuditAction.THREAD_KEY_UNWRAP_FAILED).size)
        other.close()
    }
}

package dev.kpetriv.sprout.core.domainledger

import dev.kpetriv.sprout.core.model.DeviceId
import dev.kpetriv.sprout.core.model.Role
import dev.kpetriv.sprout.core.model.UserId
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RemoteEventVerifierTest {
    private val registry = InMemoryDeviceRegistry()
    private val parentDeviceKey = SoftwareKey()
    private val kidDeviceKey = SoftwareKey()
    private val parentAuthKey = SoftwareKey()
    private val policy = LedgerPolicy(maxLamportJump = 100)
    private val verifier = RemoteEventVerifier(registry, SoftwareSignatureCheck, policy)

    private val parent =
        DeviceWriter(Fixtures.parentPhone, parentKey = parentAuthKey, deviceKey = parentDeviceKey.signer)
    private val kid = DeviceWriter(Fixtures.kidTablet, deviceKey = kidDeviceKey.signer)

    init {
        registry.register(Fixtures.parentPhone, parentDeviceKey)
        registry.register(Fixtures.kidTablet, kidDeviceKey)
        registry.addParentKey(Fixtures.parentPhone, parentAuthKey)
    }

    private suspend fun verify(event: LedgerEvent, localMaxLamport: Long = 0) =
        verifier.verify(LedgerJson.encodeToJsonObject(event), localMaxLamport)

    private suspend fun reasonFor(event: LedgerEvent, localMaxLamport: Long = 0): QuarantineReason =
        assertIs<VerifiedRemote.Quarantined>(verify(event, localMaxLamport)).reason

    @Test
    fun signedEventsFromRegisteredDevicesAreAccepted() = runTest {
        val chore = parent.chore(150)
        val game = kid.game(10)
        for (event in listOf(chore, game)) {
            val ok = assertIs<VerifiedRemote.Ok>(verify(event))
            assertEquals(event, ok.event)
            assertEquals(LedgerJson.encodeToJsonObject(event), ok.json)
        }
        assertTrue(chore.isParentOnly())
    }

    @Test
    fun undecodableTreesAreQuarantinedWithWhateverIdsTheyCarry() = runTest {
        val game = kid.game(10)
        val unknownType = JsonObject(LedgerJson.encodeToJsonObject(game) + ("type" to JsonPrimitive("teleported")))
        val quarantined = assertIs<VerifiedRemote.Quarantined>(verifier.verify(unknownType, 0))
        assertEquals(QuarantineReason.UNDECODABLE, quarantined.reason)
        assertEquals(game.eventId, quarantined.eventId)
        assertEquals(game.envelope.hash, quarantined.hash)
        assertEquals(Fixtures.kidTablet.deviceId, quarantined.authorDeviceId)

        val empty = assertIs<VerifiedRemote.Quarantined>(verifier.verify(JsonObject(emptyMap()), 0))
        assertEquals(VerifiedRemote.Quarantined(null, null, null, QuarantineReason.UNDECODABLE), empty)
    }

    @Test
    fun aVersion1TreeWhoseTypeNoLongerDecodesIsUndecodable() = runTest {
        // The M1 chore had no dueOn, which ChoreRewarded now requires.
        val v1Chore = parse(CanonicalEncoderTest.V1_CHORE_DOCUMENT)
        val quarantined = assertIs<VerifiedRemote.Quarantined>(verifier.verify(v1Chore, 0))
        assertEquals(QuarantineReason.UNDECODABLE, quarantined.reason)
        assertEquals(CanonicalEncoderTest.V1_GOLDEN_HASH, quarantined.hash)
    }

    @Test
    fun aSchemaVersion1EventIsAccepted() = runTest {
        // The M1 purchase exactly as stored, device-signed over its own tree by the registered kid device.
        val v1 = parse(CanonicalEncoderTest.V1_PURCHASE_DOCUMENT)
        val signed = v1.withEnvelopeField("signature", base64(kidDeviceKey.sign(signedPayloadOf(v1))))

        val ok = assertIs<VerifiedRemote.Ok>(verifier.verify(signed, 0))
        assertEquals(1, ok.event.envelope.schemaVersion)
        assertEquals(signed, ok.json)
    }

    @Test
    fun aNewerSchemaVersionIsUnsupported() = runTest {
        val v3 = reissue(kid.game(10), kidDeviceKey) { it.copy(schemaVersion = 3) }
        assertEquals(QuarantineReason.SCHEMA_UNSUPPORTED, reasonFor(v3))

        // Also when the newer schema brings an event type this client cannot decode at all.
        val unknown = LedgerJson.encodeToJsonObject(v3).let { JsonObject(it + ("type" to JsonPrimitive("future"))) }
        assertEquals(
            QuarantineReason.SCHEMA_UNSUPPORTED,
            assertIs<VerifiedRemote.Quarantined>(verifier.verify(unknown, 0)).reason,
        )
    }

    @Test
    fun theHashIsCheckedOverTheStoredTree() = runTest {
        val game = kid.game(10)
        val json = LedgerJson.encodeToJsonObject(game)
        val edited = JsonObject(json + ("amount" to JsonPrimitive(40)))
        assertEquals(QuarantineReason.HASH_MISMATCH, reasonOf(edited))

        // A field this client does not know is still covered by the hash...
        val extra = JsonObject(json + ("bonus" to JsonPrimitive(1)))
        assertEquals(QuarantineReason.HASH_MISMATCH, reasonOf(extra))

        // ...and accepted once the author hashed and signed it (forward compatibility).
        val rehashed = extra.withEnvelopeField("hash", JsonPrimitive(HashChain().hashOf(extra, null)))
        val resigned = rehashed.withEnvelopeField("signature", base64(kidDeviceKey.sign(signedPayloadOf(rehashed))))
        val ok = assertIs<VerifiedRemote.Ok>(verifier.verify(resigned, 0))
        assertEquals(resigned, ok.json)
    }

    @Test
    fun anUnregisteredDeviceIsUnknown() = runTest {
        val strangerKey = SoftwareKey()
        val stranger = DeviceWriter(
            Fixtures.kidTablet.copy(deviceId = DeviceId("stranger-phone")),
            deviceKey = strangerKey.signer,
        )
        assertEquals(QuarantineReason.UNKNOWN_DEVICE, reasonFor(stranger.game(10)))
    }

    @Test
    fun aPendingDeviceIsNotActive() = runTest {
        registry.register(Fixtures.kidTablet, kidDeviceKey, status = DeviceStatus.Pending)
        assertEquals(QuarantineReason.DEVICE_NOT_ACTIVE, reasonFor(kid.game(10)))
    }

    @Test
    fun aRevokedDeviceKeepsOnlyEventsBelowItsRevocationLamport() = runTest {
        val before = kid.game(10)
        val at = kid.game(10)
        val after = kid.game(10)
        registry.register(Fixtures.kidTablet, kidDeviceKey, status = DeviceStatus.Revoked(atLamport = at.lamport))

        assertIs<VerifiedRemote.Ok>(verify(before))
        assertEquals(QuarantineReason.DEVICE_NOT_ACTIVE, reasonFor(at))
        assertEquals(QuarantineReason.DEVICE_NOT_ACTIVE, reasonFor(after))
    }

    @Test
    fun theAuthorUidMustBeTheDeviceOwners() = runTest {
        val impostor = DeviceWriter(
            Fixtures.kidTablet.copy(uid = UserId("someone-else")),
            deviceKey = kidDeviceKey.signer,
        )
        assertEquals(QuarantineReason.UID_MISMATCH, reasonFor(impostor.game(10)))
    }

    @Test
    fun aKidDeviceCannotAuthorParentRoleEvents() = runTest {
        val claimsParent = DeviceWriter(
            Fixtures.kidTablet.copy(role = Role.PARENT),
            deviceKey = kidDeviceKey.signer,
        )
        // Not even an event any device could write: the PARENT role itself is refused.
        assertEquals(QuarantineReason.ROLE_NOT_ALLOWED, reasonFor(claimsParent.game(10)))
    }

    @Test
    fun aParentDeviceMayAuthorKidRoleEvents() = runTest {
        // Kid mode on a parent device (D-M3-12): the parent uid and device with role KID.
        val kidMode = DeviceWriter(Fixtures.parentPhone.copy(role = Role.KID), deviceKey = parentDeviceKey.signer)
        assertIs<VerifiedRemote.Ok>(verify(kidMode.game(10)))
    }

    @Test
    fun theDeviceSignatureMustVerifyAgainstTheRegisteredKey() = runTest {
        val unsigned = DeviceWriter(Fixtures.kidTablet).game(10)
        assertNull(unsigned.envelope.signature)
        assertEquals(QuarantineReason.BAD_DEVICE_SIGNATURE, reasonFor(unsigned))

        val otherKey = DeviceWriter(Fixtures.kidTablet, deviceKey = SoftwareKey().signer).game(10)
        assertEquals(QuarantineReason.BAD_DEVICE_SIGNATURE, reasonFor(otherKey))

        val notBase64 = kid.game(10).mutateEnvelope { it.copy(signature = "%%%") }
        assertEquals(QuarantineReason.BAD_DEVICE_SIGNATURE, reasonFor(notBase64))

        // The parent key's signature over the device payload is not a device signature.
        val wrongSigner = DeviceWriter(Fixtures.kidTablet, deviceKey = parentAuthKey.signer).game(10)
        assertEquals(QuarantineReason.BAD_DEVICE_SIGNATURE, reasonFor(wrongSigner))
    }

    @Test
    fun aSignatureCheckThatThrowsQuarantinesInsteadOfCrashing() = runTest {
        val throwing = RemoteEventVerifier(registry, { _, _, _ -> error("provider bug") }, policy)
        val result = throwing.verify(LedgerJson.encodeToJsonObject(kid.game(10)), 0)
        assertEquals(QuarantineReason.BAD_DEVICE_SIGNATURE, assertIs<VerifiedRemote.Quarantined>(result).reason)
    }

    @Test
    fun parentOnlyEventsAlwaysNeedAParentSignature() = runTest {
        val unsignedParent = DeviceWriter(Fixtures.parentPhone, deviceKey = parentDeviceKey.signer)
        val chore = unsignedParent.chore(100)
        // The policy's switch and from-lamport exemption are not consulted for pulled events.
        for (lenient in listOf(LedgerPolicy(), LedgerPolicy(parentSignatureRequiredFromLamport = Long.MAX_VALUE))) {
            val result = RemoteEventVerifier(registry, SoftwareSignatureCheck, lenient)
                .verify(LedgerJson.encodeToJsonObject(chore), 0)
            assertEquals(
                QuarantineReason.PARENT_SIGNATURE_MISSING,
                assertIs<VerifiedRemote.Quarantined>(result).reason,
            )
        }
        val emptySignature = parent.chore(100).mutateEnvelope { it.copy(parentSignature = "") }
            .let { it.signWith(parentDeviceKey.signer) }
        assertEquals(QuarantineReason.PARENT_SIGNATURE_MISSING, reasonFor(emptySignature))

        // A manual time grant is parent-only too; a reward-referenced one is not.
        assertEquals(QuarantineReason.PARENT_SIGNATURE_MISSING, reasonFor(unsignedParent.timeCredit(15)))
        val purchase = kid.screenTime(cost = 50, minutes = 20)
        assertIs<VerifiedRemote.Ok>(verify(kid.timeCredit(20, grantedBy = purchase)))
    }

    @Test
    fun aParentSignatureByAnUnregisteredKeyIsInvalid() = runTest {
        val fake = DeviceWriter(
            Fixtures.parentPhone,
            parentKey = FakeParentAuthorization(),
            deviceKey = parentDeviceKey.signer,
        )
        assertEquals(QuarantineReason.PARENT_SIGNATURE_INVALID, reasonFor(fake.chore(100)))

        val stranger = DeviceWriter(Fixtures.parentPhone, parentKey = SoftwareKey(), deviceKey = parentDeviceKey.signer)
        assertEquals(QuarantineReason.PARENT_SIGNATURE_INVALID, reasonFor(stranger.allowance(500)))

        // The device key cannot stand in for the parent key (different domain and key).
        val deviceAsParent =
            DeviceWriter(Fixtures.parentPhone, parentKey = parentDeviceKey, deviceKey = parentDeviceKey.signer)
        assertEquals(QuarantineReason.PARENT_SIGNATURE_INVALID, reasonFor(deviceAsParent.adjust(-5)))
    }

    @Test
    fun aParentKeyOnlyCoversItsLamportRange() = runTest {
        val laterKey = SoftwareKey()
        val writer = DeviceWriter(Fixtures.parentPhone, parentKey = laterKey, deviceKey = parentDeviceKey.signer)
        val early = writer.chore(100)
        val inRange = writer.chore(100)
        val late = writer.chore(100)
        registry.addParentKey(
            Fixtures.parentPhone,
            laterKey,
            validFromLamport = inRange.lamport,
            revokedAtLamport = late.lamport,
        )

        assertTrue(early.lamport < inRange.lamport)
        assertEquals(QuarantineReason.PARENT_SIGNATURE_INVALID, reasonFor(early))
        assertIs<VerifiedRemote.Ok>(verify(inRange))
        assertEquals(QuarantineReason.PARENT_SIGNATURE_INVALID, reasonFor(late))
    }

    @Test
    fun aLamportTooFarAheadOfTheLocalMaximumIsQuarantined() = runTest {
        val ahead =
            DeviceWriter(Fixtures.kidTablet, lamport = LamportClock(start = 150), deviceKey = kidDeviceKey.signer)
        val event = ahead.game(10)
        assertEquals(151, event.lamport)
        assertEquals(QuarantineReason.LAMPORT_TOO_FAR_AHEAD, reasonFor(event, localMaxLamport = 50))
        assertIs<VerifiedRemote.Ok>(verify(event, localMaxLamport = 51))
        // No overflow near the top of the range.
        assertIs<VerifiedRemote.Ok>(verify(event, localMaxLamport = Long.MAX_VALUE - 1))
    }

    @Test
    fun theFirstFailingCheckWins() = runTest {
        // Unknown device and a broken hash: the hash is checked first.
        val stranger = DeviceWriter(Fixtures.kidTablet.copy(deviceId = DeviceId("stranger-phone"))).game(10)
        assertEquals(QuarantineReason.HASH_MISMATCH, reasonFor(stranger.mutateEnvelope { it.copy(lamport = 9) }))

        // Revoked and impersonating another uid: the device status is checked first.
        registry.register(Fixtures.kidTablet, kidDeviceKey, status = DeviceStatus.Revoked(atLamport = 0))
        val impostor =
            DeviceWriter(Fixtures.kidTablet.copy(uid = UserId("someone-else")), deviceKey = kidDeviceKey.signer)
        assertEquals(QuarantineReason.DEVICE_NOT_ACTIVE, reasonFor(impostor.game(10)))

        // A missing parent signature is reported before the lamport bound.
        val far = DeviceWriter(Fixtures.parentPhone, lamport = LamportClock(500), deviceKey = parentDeviceKey.signer)
        assertEquals(QuarantineReason.PARENT_SIGNATURE_MISSING, reasonFor(far.chore(100)))
    }

    private suspend fun reasonOf(json: JsonObject): QuarantineReason =
        assertIs<VerifiedRemote.Quarantined>(verifier.verify(json, 0)).reason

    /** [event] with its envelope changed, rehashed and device-signed again with [key], as its author would. */
    private fun reissue(event: LedgerEvent, key: SoftwareKey, change: (Envelope) -> Envelope): LedgerEvent {
        val changed = event.mutateEnvelope(change)
        return changed.mutateEnvelope { it.copy(hash = HashChain().hashOf(changed)) }.signWith(key.signer)
    }

    private fun parse(text: String): JsonObject = LedgerJson.json.parseToJsonElement(text).jsonObject

    private fun base64(bytes: ByteArray) = JsonPrimitive(Base64.Default.encode(bytes))

    private fun JsonObject.withEnvelopeField(key: String, value: JsonElement): JsonObject =
        JsonObject(this + ("envelope" to JsonObject(getValue("envelope").jsonObject + (key to value))))
}

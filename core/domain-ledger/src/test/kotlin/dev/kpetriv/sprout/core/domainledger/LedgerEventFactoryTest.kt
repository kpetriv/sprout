package dev.kpetriv.sprout.core.domainledger

import dev.kpetriv.sprout.core.time.FakeClock
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Instant

class LedgerEventFactoryTest {
    private val clock = FakeClock(Instant.fromEpochMilliseconds(1_000))
    private val lamport = LamportClock()
    private val factory = LedgerEventFactory(clock, lamport, Fixtures.seededIds(3))

    private fun chore(head: String?) = factory.create(Fixtures.parentPhone, Fixtures.kid, Fixtures.main.id, head) {
        ChoreRewarded(it, Fixtures.gbp, Money(100), "dishes", Fixtures.today)
    }

    @Test
    fun buildsAChainedHashedUnsignedEvent() {
        val first = chore(null)
        val second = chore(first.envelope.hash)
        assertEquals(1, first.lamport)
        assertEquals(2, second.lamport)
        assertNull(first.envelope.prevHash)
        assertEquals(first.envelope.hash, second.envelope.prevHash)
        assertNull(first.envelope.signature)
        assertEquals(Envelope.CURRENT_SCHEMA_VERSION, first.envelope.schemaVersion)
        assertEquals(Fixtures.parentPhone.deviceId, first.envelope.authorDeviceId)
        assertEquals(HashChain().hashOf(first), first.envelope.hash)
        assertIs<ChainVerification.Valid>(HashChain().verifyChain(listOf(first, second)))
    }

    @Test
    fun createdAtIsTruncatedToMilliseconds() {
        clock.advanceBy(123_456.nanoseconds)
        assertEquals(Instant.fromEpochMilliseconds(1_000), chore(null).envelope.createdAt)
    }

    @Test
    fun bodyMustUseTheEnvelopeItIsGiven() {
        assertFailsWith<IllegalArgumentException> {
            factory.create(Fixtures.parentPhone, Fixtures.kid, Fixtures.main.id, null) {
                ChoreRewarded(it.copy(lamport = 99), Fixtures.gbp, Money(1), "x", Fixtures.today)
            }
        }
    }

    @Test
    fun signingAttachesBase64WithoutChangingTheHash() {
        val event = chore(null)
        var signedBytes: ByteArray? = null
        val signed = event.signWith { payload ->
            signedBytes = payload
            byteArrayOf(0x30, 0x45, -1, 0)
        }
        assertContentEquals(
            "sprout/ledger-event/v1".encodeToByteArray() + byteArrayOf(0) + CanonicalEncoder.encode(event),
            signedBytes,
        )
        assertContentEquals(event.signedPayload(), signed.signedPayload())
        assertEquals("MEX/AA==", signed.envelope.signature)
        assertContentEquals(byteArrayOf(0x30, 0x45, -1, 0), signed.signatureBytes())
        assertEquals(event.envelope.hash, signed.envelope.hash)
        assertNull(event.signatureBytes())
        assertIs<ChoreRewarded>(signed)
    }

    @Test
    fun theSignedPayloadIsDomainSeparatedFromTheHashInput() {
        val event = chore(null)
        val payload = event.signedPayload()
        val prefix = LEDGER_SIGNATURE_DOMAIN.encodeToByteArray()
        assertContentEquals(prefix, payload.copyOfRange(0, prefix.size))
        assertEquals(0, payload[prefix.size].toInt())
        assertContentEquals(CanonicalEncoder.encode(event), payload.copyOfRange(prefix.size + 1, payload.size))
        // Stored documents give the same payload from their own fields.
        val stored = LedgerJson.json.parseToJsonElement(LedgerJson.encode(event)).jsonObject
        assertContentEquals(payload, signedPayloadOf(stored))
        // The hash chain input stays unprefixed.
        assertEquals(HashChain().computeHash(null, CanonicalEncoder.encode(event)), event.envelope.hash)
    }

    @Test
    fun parentSigningUsesItsOwnDomainAndChangesNothingElse() {
        val event = chore(null)
        val key = FakeParentAuthorization(singleUse = true)
        val signed = event.signWith { byteArrayOf(1) }.parentSignWith(key)
        // One sign call, over the parent payload, which differs from the device payload only in its prefix.
        val payload = key.signed.single()
        assertContentEquals(event.parentSignedPayload(), payload)
        assertContentEquals(
            LEDGER_PARENT_SIGNATURE_DOMAIN.encodeToByteArray() + byteArrayOf(0) + CanonicalEncoder.encode(event),
            payload,
        )
        assertFalse(payload.contentEquals(event.signedPayload()))
        assertTrue(key.verify(payload, assertNotNull(signed.parentSignatureBytes())))
        // Hash, device signature and both payloads are unchanged by the parent signature.
        assertEquals(event.envelope.hash, signed.envelope.hash)
        assertContentEquals(byteArrayOf(1), signed.signatureBytes())
        assertContentEquals(event.signedPayload(), signed.signedPayload())
        assertContentEquals(event.parentSignedPayload(), signed.parentSignedPayload())
        assertIs<ChoreRewarded>(signed)
        // A per-use authorisation cannot sign a second event.
        assertFailsWith<IllegalStateException> { chore(event.envelope.hash).parentSignWith(key) }
        // Malformed parent signatures read as absent.
        assertNull(event.parentSignatureBytes())
        assertNull(event.mutateEnvelope { it.copy(parentSignature = "not base64!") }.parentSignatureBytes())
        // Two copies that differ only in the parent signature are different events (a stripped copy conflicts).
        assertFalse(signed.isSameEventAs(signed.mutateEnvelope { it.copy(parentSignature = null) }))
        assertTrue(signed.isSameEventAs(signed.withSignature(byteArrayOf(2))))
    }

    @Test
    fun malformedSignaturesDecodeToNull() {
        val event = chore(null)
        for (bad in listOf("not base64!", "QQ=", "====", "\u00e9t\u00e9")) {
            assertNull(event.mutateEnvelope { it.copy(signature = bad) }.signatureBytes(), bad)
        }
        assertContentEquals(byteArrayOf(1, 2), event.withSignature(byteArrayOf(1, 2)).signatureBytes())
    }

    @Test
    fun lamportClockIgnoresValuesTooFarAhead() {
        val clock = LamportClock(start = 10, maxJump = 100)
        assertTrue(clock.observe(110))
        assertEquals(110, clock.current)
        assertFalse(clock.observe(211))
        assertEquals(110, clock.current)
        assertFalse(clock.observe(Long.MAX_VALUE))
        assertEquals(110, clock.current)
        assertTrue(clock.observe(5))
        assertEquals(110, clock.current)
        assertEquals(111, clock.next())
        val defaults = LamportClock()
        assertTrue(defaults.observe(LedgerPolicy.DEFAULT_MAX_LAMPORT_JUMP))
        assertFalse(LamportClock().observe(LedgerPolicy.DEFAULT_MAX_LAMPORT_JUMP + 1))
        assertFailsWith<IllegalArgumentException> { LamportClock(maxJump = 0) }
    }

    @Test
    fun lamportClockMergesRemoteValues() {
        val clock = LamportClock(start = 5)
        assertEquals(6, clock.next())
        clock.observe(3)
        assertEquals(7, clock.next())
        clock.observe(40)
        assertEquals(40, clock.current)
        assertEquals(41, clock.next())
        assertFailsWith<ArithmeticException> { LamportClock(Long.MAX_VALUE).next() }
        assertFailsWith<IllegalArgumentException> { LamportClock(-1) }
    }

    @Test
    fun eventIdsAreVersion4Uuids() {
        val ids = UuidV4EventIdGenerator()
        val generated = List(200) { ids.next().value }
        assertEquals(200, generated.toSet().size)
        for (id in generated) {
            assertEquals('4', id[14])
            assertTrue(id[19] in "89ab", id)
        }
        assertFailsWith<IllegalArgumentException> { EventId("not-a-uuid") }
        assertFailsWith<IllegalArgumentException> { EventId("00000000-0000-4000-8000-00000000000A") }
    }

    @Test
    fun envelopeRejectsSubMillisecondTimesAndBadPrevHash() {
        val event = chore(null)
        assertFailsWith<IllegalArgumentException> {
            event.mutateEnvelope { it.copy(createdAt = it.createdAt + 1.nanoseconds) }
        }
        assertFailsWith<IllegalArgumentException> { event.mutateEnvelope { it.copy(prevHash = "XYZ") } }
        assertFailsWith<IllegalArgumentException> { event.mutateEnvelope { it.copy(lamport = -1) } }
    }
}

package dev.kpetriv.sprout.core.domainledger

import dev.kpetriv.sprout.core.model.AccountId
import dev.kpetriv.sprout.core.model.DeviceId
import dev.kpetriv.sprout.core.model.HouseholdId
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.Role
import dev.kpetriv.sprout.core.model.UserId
import dev.kpetriv.sprout.core.time.Clock
import kotlinx.serialization.json.JsonObject
import kotlin.io.encoding.Base64
import kotlin.time.Instant

/** Signs a payload with the device key (provided by `core/crypto`); returns the raw signature bytes. */
typealias Signer = (ByteArray) -> ByteArray

/** Source of lamport values for new local events. */
fun interface LamportSource {
    fun next(): Long
}

/**
 * A device's Lamport clock (ADR-0002): incremented for every local event, and merged with [observe] on every
 * pull so the next local event sorts after everything seen.
 *
 * @param maxJump how far above [current] an observed value may be (see [LedgerPolicy.maxLamportJump]). A larger
 *   value is ignored: one forged event with a lamport near `Long.MAX_VALUE` must not drag the clock to the end of
 *   time and stop the device from ever writing again.
 */
class LamportClock(start: Long = 0, private val maxJump: Long = LedgerPolicy.DEFAULT_MAX_LAMPORT_JUMP) :
    LamportSource {
    init {
        require(start >= 0) { "start must not be negative" }
        require(maxJump > 0) { "maxJump must be positive" }
    }

    private var value = start

    val current: Long
        @Synchronized get() = value

    @Synchronized
    override fun next(): Long {
        if (value == Long.MAX_VALUE) throw ArithmeticException("Lamport clock overflow")
        value += 1
        return value
    }

    /**
     * Merges a lamport seen on a remote or stored event: the clock never runs behind it.
     *
     * @return false if [remote] was more than `maxJump` above [current] and was ignored (report it: the event that
     *   carried it is suspect); true otherwise, whether or not the clock moved.
     */
    @Synchronized
    fun observe(remote: Long): Boolean {
        // Both are non-negative, so the subtraction cannot overflow.
        if (remote - value > maxJump) return false
        if (remote > value) value = remote
        return true
    }
}

/** Who is writing: the signed-in user, their role, and the registered device. */
data class EventAuthor(val householdId: HouseholdId, val uid: UserId, val role: Role, val deviceId: DeviceId)

/**
 * Builds correctly chained and hashed, unsigned events. Sign the result with [signWith] using the device key.
 *
 * ```
 * val event = factory.create(author, kidId, accountId, repository.headHash(author.deviceId)) { envelope ->
 *     ChoreRewarded(envelope, currency, Money(150), choreId)
 * }.signWith(deviceSigner)
 * ```
 */
class LedgerEventFactory(
    private val clock: Clock,
    private val lamport: LamportSource,
    private val ids: EventIdGenerator = UuidV4EventIdGenerator(),
    private val hashChain: HashChain = HashChain(),
) {
    /**
     * @param headHash the hash of this device's newest event ([LedgerRepository.headHash]); null for its first.
     * @param body builds the event around the envelope it is given, which it must use unchanged.
     */
    fun <E : LedgerEvent> create(
        author: EventAuthor,
        kidId: KidId,
        accountId: AccountId,
        headHash: String?,
        body: (Envelope) -> E,
    ): E {
        val envelope = Envelope(
            eventId = ids.next(),
            householdId = author.householdId,
            kidId = kidId,
            accountId = accountId,
            authorUid = author.uid,
            authorRole = author.role,
            authorDeviceId = author.deviceId,
            createdAt = Instant.fromEpochMilliseconds(clock.now().toEpochMilliseconds()),
            lamport = lamport.next(),
            prevHash = headHash,
            hash = Envelope.UNHASHED,
        )
        val draft = body(envelope)
        require(draft.envelope == envelope) { "body must use the envelope it is given" }
        val hashed = draft.withEnvelope(envelope.copy(hash = hashChain.hashOf(draft)))
        @Suppress("UNCHECKED_CAST") // withEnvelope is a data class copy, so the type is preserved.
        return hashed as E
    }
}

/**
 * Domain-separation prefix of [signedPayload]: the device key signs ledger events and other payloads (THREAT_MODEL),
 * so every ledger signature input starts with this tag and a zero byte, and can never be replayed as, or confused
 * with, a signature over some other kind of message.
 */
const val LEDGER_SIGNATURE_DOMAIN: String = "sprout/ledger-event/v1"

private val SIGNATURE_PREFIX = LEDGER_SIGNATURE_DOMAIN.encodeToByteArray() + 0.toByte()

/**
 * The bytes the device key signs: `UTF-8("sprout/ledger-event/v1") || 0x00 || canonicalBytes`. The canonical
 * encoding already commits to `prevHash`. (The hash chain input has no prefix; see [HashChain].)
 *
 * @throws CharacterCodingException if a string field holds an unpaired surrogate.
 */
fun LedgerEvent.signedPayload(): ByteArray = SIGNATURE_PREFIX + CanonicalEncoder.encode(this)

/**
 * [signedPayload] for a stored or pulled event document, from its own fields. Use this to verify the signature of
 * what was stored or pulled (see [LedgerJson]).
 *
 * @throws IllegalArgumentException if [json] is not canonicalisable.
 * @throws CharacterCodingException if a string holds an unpaired surrogate.
 */
fun signedPayloadOf(json: JsonObject): ByteArray = SIGNATURE_PREFIX + CanonicalEncoder.encode(json)

/** This event carrying [sig] as its base64 signature. Hash and canonical encoding are unchanged. */
fun LedgerEvent.withSignature(sig: ByteArray): LedgerEvent =
    withEnvelope(envelope.copy(signature = Base64.Default.encode(sig)))

/** Signs [signedPayload] with [signer] and attaches the signature. */
fun LedgerEvent.signWith(signer: Signer): LedgerEvent = withSignature(signer(signedPayload()))

/** The raw signature bytes, or null if unsigned or if the stored signature is not valid base64. */
fun LedgerEvent.signatureBytes(): ByteArray? = envelope.signature?.let {
    try {
        Base64.Default.decode(it)
    } catch (expected: IllegalArgumentException) {
        null
    }
}

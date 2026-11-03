package dev.kpetriv.sprout.core.domainledger

import kotlinx.serialization.json.JsonObject
import kotlin.io.encoding.Base64

/**
 * Proof that a parent is present for exactly one ledger event (ADR-0004, section 1): the data layer wraps a
 * `Signature` from the parent authentication key, unlocked by a successful `BiometricPrompt` bound to it through a
 * per-use `CryptoObject`. Such an authorisation is typically **single-use**, so a [LedgerWriter] calls [sign]
 * exactly once, with [LedgerEvent.parentSignedPayload] of the event it is about to append.
 */
fun interface ParentAuthorization {
    /** Signs [payload] with the parent authentication key; returns the raw (DER) signature bytes. */
    fun sign(payload: ByteArray): ByteArray
}

/**
 * Obtains a [ParentAuthorization] for one parent-only ledger event: one strong-biometric prompt per call (ADR-0004
 * section 1, D-M2-2). The feature layer asks for it right before the event is signed; the app binds it to
 * `feature/lock`'s prompt, which shows [request]'s `reason` (what is about to be signed, for example
 * "Pay Leo £2.50 for Make bed") as the prompt's subtitle.
 *
 * On a device without a usable strong biometric, [request] either throws
 * `dev.kpetriv.sprout.core.crypto.ParentAuthUnavailableException` or returns an authorisation whose
 * [ParentAuthorization.sign] throws it; the data-layer use cases treat both the same way and fall back per the
 * household policy (D-M2-1). Any other exception (the parent dismissed the prompt, a biometric error, an invalidated
 * key) means "not authorised".
 */
fun interface ParentAuthorizationRequester {
    /** Prompts once; [reason] says what is being authorised. */
    suspend fun request(reason: String): ParentAuthorization
}

/**
 * Domain-separation prefix of [parentSignedPayload]. Different from [LEDGER_SIGNATURE_DOMAIN], so a device
 * signature can never stand in for a parent signature over the same event or the reverse, and versioned with the
 * envelope schema that introduced it.
 */
const val LEDGER_PARENT_SIGNATURE_DOMAIN: String = "sprout/ledger-event-parent/v2"

private val PARENT_SIGNATURE_PREFIX = LEDGER_PARENT_SIGNATURE_DOMAIN.encodeToByteArray() + 0.toByte()

/**
 * The bytes the parent authentication key signs: `UTF-8("sprout/ledger-event-parent/v2") || 0x00 ||
 * canonicalBytes`. The canonical encoding excludes both signatures, so the parent and the device signature can be
 * made in either order and neither covers the other.
 *
 * @throws CharacterCodingException if a string field holds an unpaired surrogate.
 */
fun LedgerEvent.parentSignedPayload(): ByteArray = PARENT_SIGNATURE_PREFIX + CanonicalEncoder.encode(this)

/**
 * [parentSignedPayload] for a stored or pulled event document, from its own fields. Use this to verify the parent
 * signature of what was stored or pulled (see [LedgerJson]).
 *
 * @throws IllegalArgumentException if [json] is not canonicalisable.
 * @throws CharacterCodingException if a string holds an unpaired surrogate.
 */
fun parentSignedPayloadOf(json: JsonObject): ByteArray = PARENT_SIGNATURE_PREFIX + CanonicalEncoder.encode(json)

/** This event carrying [sig] as its base64 parent signature. Hash, canonical form and device signature unchanged. */
fun LedgerEvent.withParentSignature(sig: ByteArray): LedgerEvent =
    withEnvelope(envelope.copy(parentSignature = Base64.Default.encode(sig)))

/** Signs [parentSignedPayload] with [authorization] (one call to [ParentAuthorization.sign]) and attaches it. */
fun LedgerEvent.parentSignWith(authorization: ParentAuthorization): LedgerEvent =
    withParentSignature(authorization.sign(parentSignedPayload()))

/** The raw parent signature bytes, or null if there is none or the stored value is not valid base64. */
fun LedgerEvent.parentSignatureBytes(): ByteArray? = envelope.parentSignature?.let {
    try {
        Base64.Default.decode(it)
    } catch (expected: IllegalArgumentException) {
        null
    }
}

/**
 * True when only a parent may author this event: every [LedgerRules.PARENT_ONLY] type, and a [TimeBankCredited]
 * that does not reference a reward purchase ([TimeBankCredited.grantedBy] null: a manual grant). A reward-referenced
 * credit is authorised by the purchase it names instead (see [LedgerRules]).
 */
fun LedgerEvent.isParentOnly(): Boolean = when (this) {
    is TimeBankCredited -> grantedBy == null
    else -> this::class in LedgerRules.PARENT_ONLY
}

/**
 * True when [policy] requires this event to carry a parent signature: [LedgerPolicy.requireParentSignature] is set,
 * the event [isParentOnly], and its lamport is at or above [LedgerPolicy.parentSignatureRequiredFromLamport] (when
 * that is set). Such an event without a decodable, non-empty [Envelope.parentSignature] breaks
 * [Violation.ParentSignatureMissing]. Whether the signature verifies against a registered parent key is checked by
 * the data layer (M2: the local parent key; M3: the household's parent-key registry), not here.
 */
fun LedgerEvent.requiresParentSignature(policy: LedgerPolicy): Boolean {
    val from = policy.parentSignatureRequiredFromLamport
    return policy.requireParentSignature && isParentOnly() && (from == null || lamport >= from)
}

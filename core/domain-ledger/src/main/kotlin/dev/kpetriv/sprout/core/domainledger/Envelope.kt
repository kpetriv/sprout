package dev.kpetriv.sprout.core.domainledger

import dev.kpetriv.sprout.core.model.AccountId
import dev.kpetriv.sprout.core.model.DeviceId
import dev.kpetriv.sprout.core.model.HouseholdId
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.Role
import dev.kpetriv.sprout.core.model.UserId
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlin.time.Instant

/**
 * Fields every [LedgerEvent] carries (ADR-0002, "Envelope fields").
 *
 * @property eventId identity and idempotency key.
 * @property accountId the account the event posts to (the source account for [TransferToSavings]).
 * @property createdAt wall-clock time for display only; never used for ordering. Millisecond precision, so it
 *   survives every storage and wire format unchanged and the hash stays stable.
 * @property lamport logical clock for ordering; strictly increasing per author device.
 * @property prevHash [hash] of the previous event from the same [authorDeviceId]; null for the first event of
 *   that author's stream (the chain then starts from [HashChain.GENESIS]).
 * @property hash SHA-256 hex over the canonical encoding and [prevHash]; see [HashChain]. Excluded from the
 *   canonical encoding itself.
 * @property signature base64 ECDSA signature over [LedgerEvent.signedPayload] by the device key; null until
 *   signed. Excluded from the canonical encoding.
 * @property parentSignature (schema version 2) base64 ECDSA P-256 signature over [LedgerEvent.parentSignedPayload]
 *   by the parent authentication key (ADR-0004). Carried by parent-only events ([LedgerEvent.isParentOnly]) and
 *   required on them when [LedgerPolicy.requireParentSignature] is set. Excluded from the canonical encoding
 *   exactly like [signature], so a v1 document (which has no such key), an explicit `null` and any value all hash
 *   and verify the same; the hash and the device signature never depend on it. It is still part of the event's
 *   identity: two copies that differ only here are different events ([isSameEventAs]).
 * @property schemaVersion version of the event schema and canonical encoding; part of the hashed form. New events
 *   are written with [CURRENT_SCHEMA_VERSION]; version 1 documents (M1) decode with [parentSignature] null.
 */
@Serializable
data class Envelope(
    val eventId: EventId,
    val householdId: HouseholdId,
    val kidId: KidId,
    val accountId: AccountId,
    val authorUid: UserId,
    val authorRole: Role,
    val authorDeviceId: DeviceId,
    @Serializable(with = EpochMillisInstantSerializer::class)
    val createdAt: Instant,
    val lamport: Long,
    val prevHash: String?,
    val hash: String,
    val signature: String? = null,
    val parentSignature: String? = null,
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
) {
    init {
        require(lamport >= 0) { "lamport must not be negative" }
        require(schemaVersion >= 1) { "schemaVersion must be at least 1" }
        require(createdAt.nanosecondsOfSecond % NANOS_PER_MILLI == 0) { "createdAt must have millisecond precision" }
        require(prevHash == null || HEX_SHA256.matches(prevHash)) { "prevHash must be 64 lower-case hex digits" }
    }

    companion object {
        /**
         * The schema version new events are written with. Version 2 (M2) added [parentSignature],
         * [TimeBankCredited], [RewardPurchased.minutesGranted] and the period keys of [ChoreRewarded],
         * [AllowancePaid] and [InterestAccrued]. The canonical encoding rules did not change.
         */
        const val CURRENT_SCHEMA_VERSION: Int = 2

        /** Placeholder [hash] before [LedgerEventFactory] computes the real one. */
        const val UNHASHED: String = ""

        private const val NANOS_PER_MILLI = 1_000_000
        private val HEX_SHA256 = Regex("[0-9a-f]{64}")
    }
}

/**
 * Encodes an [Instant] as integer epoch milliseconds. Integer form keeps the canonical encoding independent of
 * any library's date formatting. Rejects instants with sub-millisecond precision rather than silently truncating.
 */
object EpochMillisInstantSerializer : KSerializer<Instant> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("dev.kpetriv.sprout.core.domainledger.EpochMillisInstant", PrimitiveKind.LONG)

    override fun serialize(encoder: Encoder, value: Instant) {
        val millis = value.toEpochMilliseconds()
        require(Instant.fromEpochMilliseconds(millis) == value) { "Instant must have millisecond precision" }
        encoder.encodeLong(millis)
    }

    override fun deserialize(decoder: Decoder): Instant = Instant.fromEpochMilliseconds(decoder.decodeLong())
}

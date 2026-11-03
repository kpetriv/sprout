package dev.kpetriv.sprout.core.database

import dev.kpetriv.sprout.core.crypto.DeviceSigner
import dev.kpetriv.sprout.core.database.entity.AuditAction
import dev.kpetriv.sprout.core.database.entity.HouseholdPolicyEntity
import dev.kpetriv.sprout.core.database.entity.ParentKeyEntity
import dev.kpetriv.sprout.core.domainledger.Currency
import dev.kpetriv.sprout.core.domainledger.EventAuthor
import dev.kpetriv.sprout.core.domainledger.JvmSha256Hasher
import dev.kpetriv.sprout.core.domainledger.LedgerPolicy
import dev.kpetriv.sprout.core.domainledger.toHex
import dev.kpetriv.sprout.core.sync.DocTypes
import dev.kpetriv.sprout.core.time.Clock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.put
import javax.inject.Inject
import kotlin.time.Instant

/**
 * The household's ledger policy as this device sees it (ADR-0004, D-M2-1).
 *
 * @property requireParentSignature parent-only events need a parent signature that verifies against
 *   [parentPublicKey].
 * @property parentSignatureFromLamport the signature is required from this lamport on (the registration point).
 * @property fiatCurrency the household's money currency.
 */
class HouseholdPolicy(
    val requireParentSignature: Boolean,
    val parentSignatureFromLamport: Long?,
    parentPublicKey: ByteArray?,
    val fiatCurrency: Currency.Fiat,
    val createdAt: Instant,
) {
    private val key: ByteArray? = parentPublicKey?.copyOf()

    /** A copy of the registered parent public key (X.509 SPKI DER), or null if none is registered. */
    val parentPublicKey: ByteArray? get() = key?.copyOf()

    val hasParentKey: Boolean get() = key != null

    /**
     * True while parent money events are accepted without a parent signature: no parent key is registered, for
     * example because this device has no strong biometric (D-M2-1). Settings shows a warning for it.
     */
    val parentSignaturesOff: Boolean get() = !requireParentSignature

    /** The [LedgerPolicy] this policy configures. */
    fun toLedgerPolicy(): LedgerPolicy = LedgerPolicy(
        requireParentSignature = requireParentSignature,
        parentSignatureRequiredFromLamport = parentSignatureFromLamport,
    )

    override fun equals(other: Any?): Boolean = other is HouseholdPolicy &&
        requireParentSignature == other.requireParentSignature &&
        parentSignatureFromLamport == other.parentSignatureFromLamport &&
        (key?.contentEquals(other.key) ?: (other.key == null)) &&
        fiatCurrency == other.fiatCurrency && createdAt == other.createdAt

    override fun hashCode(): Int = listOf(requireParentSignature, parentSignatureFromLamport, fiatCurrency).hashCode()

    override fun toString(): String = "HouseholdPolicy(requireParentSignature=$requireParentSignature, " +
        "parentSignatureFromLamport=$parentSignatureFromLamport, hasParentKey=$hasParentKey, " +
        "fiatCurrency=$fiatCurrency)"
}

/** The household policy row. Every change is audited (no key bytes in the audit row). */
interface HouseholdPolicyRepository {
    fun observe(): Flow<HouseholdPolicy?>

    suspend fun get(): HouseholdPolicy?

    /** The policy, creating it with [fiatCurrency] and no parent key if there is none yet. */
    suspend fun ensure(fiatCurrency: Currency.Fiat): HouseholdPolicy

    /**
     * Registers [publicKeyDer] (X.509 SPKI DER of the parent authentication key) and turns
     * `requireParentSignature` on. The signature is required from the next lamport this device will use, so parent
     * events written before the key existed stay valid; re-registering (a new key after a biometric enrolment change)
     * keeps the original from-lamport.
     *
     * The key is also published as a `parent_key` document (fingerprint, public key, uid, device, `validFromLamport`
     * = the next lamport) so other devices can verify this device's parent signatures (M3 §1), and the policy
     * document is republished with `requireParentSignature` on.
     */
    suspend fun registerParentKey(publicKeyDer: ByteArray): HouseholdPolicy
}

/**
 * [HouseholdPolicyRepository] on Room. The fiat currency and `requireParentSignature` are the synced
 * `household_policy` document (docId = household id); the registered parent key is this device's own and is
 * published as a `parent_key` document.
 */
class RoomHouseholdPolicyRepository @Inject constructor(
    private val database: SproutDatabase,
    private val authors: SessionAuthorProvider,
    private val currency: FiatCurrencyProvider,
    private val clock: Clock,
    signer: DeviceSigner,
) : HouseholdPolicyRepository {
    private val policies = database.householdPolicyDao()
    private val writes = DocumentWrites(database, clock, signer)

    override fun observe(): Flow<HouseholdPolicy?> = policies.observe().map { it?.toDomain() }

    override suspend fun get(): HouseholdPolicy? = policies.get()?.toDomain()

    override suspend fun ensure(fiatCurrency: Currency.Fiat): HouseholdPolicy {
        val author = authors.currentAuthor()
        return writes.transaction { policies.get()?.toDomain() ?: create(author, fiatCurrency) }
    }

    override suspend fun registerParentKey(publicKeyDer: ByteArray): HouseholdPolicy {
        require(publicKeyDer.isNotEmpty()) { "parent public key must not be empty" }
        val author = authors.currentAuthor()
        return writes.transaction {
            val current = policies.get()?.toDomain() ?: create(author, currency.fiatCurrency())
            val validFrom = writes.peekLamport()
            val from = current.parentSignatureFromLamport ?: validFrom
            val fingerprint = JvmSha256Hasher.sha256(publicKeyDer).toHex()
            val key = DocumentPayloads.ParentKeyPayload(
                fingerprint,
                publicKeyDer,
                author.uid,
                author.deviceId,
                validFrom,
            )
            val keyLamport = writes.nextLamport(author.deviceId)
            writes.record(
                author,
                DocTypes.PARENT_KEY,
                fingerprint,
                keyLamport,
                DocumentPayloads.parentKey(key),
                AuditAction.PARENT_KEY_REGISTERED,
            ) {
                put("fromLamport", from)
                put("validFromLamport", validFrom)
                put("replacedKey", current.hasParentKey)
            }
            database.parentKeyDao().upsert(
                ParentKeyEntity(
                    fingerprint = fingerprint,
                    publicKey = publicKeyDer.copyOf(),
                    uid = author.uid,
                    deviceId = author.deviceId,
                    validFromLamport = validFrom,
                    revokedAtLamport = null,
                    registeredAtEpochMs = clock.now().toEpochMilliseconds(),
                ),
            )
            write(
                author,
                HouseholdPolicyEntity(
                    requireParentSignature = true,
                    parentSignatureFromLamport = from,
                    parentPublicKey = publicKeyDer.copyOf(),
                    fiatCurrency = current.fiatCurrency,
                    createdAtEpochMs = current.createdAt.toEpochMilliseconds(),
                ),
            ).toDomain()
        }
    }

    private suspend fun create(author: EventAuthor, fiatCurrency: Currency.Fiat): HouseholdPolicy = write(
        author,
        HouseholdPolicyEntity(
            requireParentSignature = false,
            parentSignatureFromLamport = null,
            parentPublicKey = null,
            fiatCurrency = fiatCurrency,
            createdAtEpochMs = clock.now().toEpochMilliseconds(),
        ),
    ).toDomain()

    /** Stores [row] and publishes its synced part as the `household_policy` document. Inside the transaction. */
    private suspend fun write(author: EventAuthor, row: HouseholdPolicyEntity): HouseholdPolicyEntity {
        val fiat = row.fiatCurrency as? Currency.Fiat ?: error("household currency must be fiat")
        val lamport = writes.nextLamport(author.deviceId)
        val stamp = writes.record(
            author,
            DocTypes.HOUSEHOLD_POLICY,
            author.householdId.value,
            lamport,
            DocumentPayloads.householdPolicy(fiat, row.requireParentSignature),
            AuditAction.HOUSEHOLD_POLICY_UPDATED,
        ) {
            put("fiatCurrency", fiat.isoCode)
            put("requireParentSignature", row.requireParentSignature)
        }
        return row.copy(lamport = lamport, stamp = stamp).also { policies.upsert(it) }
    }
}

internal fun HouseholdPolicyEntity.toDomain() = HouseholdPolicy(
    requireParentSignature = requireParentSignature,
    parentSignatureFromLamport = parentSignatureFromLamport,
    parentPublicKey = parentPublicKey,
    fiatCurrency = fiatCurrency as? Currency.Fiat ?: error("household currency must be fiat"),
    createdAt = Instant.fromEpochMilliseconds(createdAtEpochMs),
)

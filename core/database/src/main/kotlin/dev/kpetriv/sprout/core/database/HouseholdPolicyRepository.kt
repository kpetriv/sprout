package dev.kpetriv.sprout.core.database

import dev.kpetriv.sprout.core.database.entity.AuditAction
import dev.kpetriv.sprout.core.database.entity.HouseholdPolicyEntity
import dev.kpetriv.sprout.core.domainledger.Currency
import dev.kpetriv.sprout.core.domainledger.LedgerPolicy
import dev.kpetriv.sprout.core.time.Clock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.put
import javax.inject.Inject
import kotlin.time.Instant

/**
 * The household's local ledger policy (ADR-0004, D-M2-1). Device-local in M2.
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
     */
    suspend fun registerParentKey(publicKeyDer: ByteArray): HouseholdPolicy
}

/** [HouseholdPolicyRepository] on Room. */
class RoomHouseholdPolicyRepository @Inject constructor(
    private val database: SproutDatabase,
    private val authors: SessionAuthorProvider,
    private val currency: FiatCurrencyProvider,
    private val clock: Clock,
) : HouseholdPolicyRepository {
    private val policies = database.householdPolicyDao()
    private val writes = DocumentWrites(database, clock)

    override fun observe(): Flow<HouseholdPolicy?> = policies.observe().map { it?.toDomain() }

    override suspend fun get(): HouseholdPolicy? = policies.get()?.toDomain()

    override suspend fun ensure(fiatCurrency: Currency.Fiat): HouseholdPolicy {
        val author = authors.currentAuthor()
        return writes.transaction {
            policies.get()?.toDomain() ?: create(fiatCurrency).also {
                writes.auditOnly(author, AuditAction.HOUSEHOLD_POLICY_UPDATED, POLICY_TARGET) {
                    put("fiatCurrency", fiatCurrency.isoCode)
                    put("requireParentSignature", false)
                }
            }
        }
    }

    override suspend fun registerParentKey(publicKeyDer: ByteArray): HouseholdPolicy {
        require(publicKeyDer.isNotEmpty()) { "parent public key must not be empty" }
        val author = authors.currentAuthor()
        return writes.transaction {
            val current = policies.get()?.toDomain() ?: create(currency.fiatCurrency())
            val from = current.parentSignatureFromLamport ?: writes.peekLamport()
            val updated = HouseholdPolicyEntity(
                requireParentSignature = true,
                parentSignatureFromLamport = from,
                parentPublicKey = publicKeyDer.copyOf(),
                fiatCurrency = current.fiatCurrency,
                createdAtEpochMs = current.createdAt.toEpochMilliseconds(),
            )
            policies.upsert(updated)
            writes.auditOnly(author, AuditAction.PARENT_KEY_REGISTERED, POLICY_TARGET) {
                put("fromLamport", from)
                put("replacedKey", current.hasParentKey)
            }
            updated.toDomain()
        }
    }

    private suspend fun create(fiatCurrency: Currency.Fiat): HouseholdPolicy {
        val row = HouseholdPolicyEntity(
            requireParentSignature = false,
            parentSignatureFromLamport = null,
            parentPublicKey = null,
            fiatCurrency = fiatCurrency,
            createdAtEpochMs = clock.now().toEpochMilliseconds(),
        )
        policies.upsert(row)
        return row.toDomain()
    }

    private companion object {
        const val POLICY_TARGET = "household_policy"
    }
}

internal fun HouseholdPolicyEntity.toDomain() = HouseholdPolicy(
    requireParentSignature = requireParentSignature,
    parentSignatureFromLamport = parentSignatureFromLamport,
    parentPublicKey = parentPublicKey,
    fiatCurrency = fiatCurrency as? Currency.Fiat ?: error("household currency must be fiat"),
    createdAt = Instant.fromEpochMilliseconds(createdAtEpochMs),
)

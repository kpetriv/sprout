package dev.kpetriv.sprout.core.database

import dev.kpetriv.sprout.core.crypto.DeviceSigner
import dev.kpetriv.sprout.core.database.entity.AuditAction
import dev.kpetriv.sprout.core.database.entity.AuditLogEntity
import dev.kpetriv.sprout.core.database.entity.HouseholdPolicyEntity
import dev.kpetriv.sprout.core.database.entity.ParentKeyEntity
import dev.kpetriv.sprout.core.domainledger.Currency
import dev.kpetriv.sprout.core.domainledger.EventAuthor
import dev.kpetriv.sprout.core.domainledger.JvmSha256Hasher
import dev.kpetriv.sprout.core.domainledger.LedgerJson
import dev.kpetriv.sprout.core.domainledger.LedgerPolicy
import dev.kpetriv.sprout.core.domainledger.Money
import dev.kpetriv.sprout.core.domainledger.toHex
import dev.kpetriv.sprout.core.model.Role
import dev.kpetriv.sprout.core.sync.DocTypes
import dev.kpetriv.sprout.core.time.Clock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
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
 * @property chatRetentionDays chat messages are kept this many days ([HouseholdPolicyLimits.CHAT_RETENTION_DAYS]);
 *   null keeps them (D-M7-8, D-M7-10).
 * @property dailyGameCapMinor the daily game cap in minor units ([HouseholdPolicyLimits.DAILY_GAME_CAP_MINOR]); null
 *   is the ledger default, [LedgerPolicy.DEFAULT_DAILY_GAME_CAP] (D-M5-4).
 */
class HouseholdPolicy(
    val requireParentSignature: Boolean,
    val parentSignatureFromLamport: Long?,
    parentPublicKey: ByteArray?,
    val fiatCurrency: Currency.Fiat,
    val createdAt: Instant,
    val chatRetentionDays: Int? = null,
    val dailyGameCapMinor: Long? = null,
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

    /** The daily game cap in force: [dailyGameCapMinor], or the ledger default. */
    val dailyGameCap: Money get() = dailyGameCapMinor?.let(::Money) ?: LedgerPolicy.DEFAULT_DAILY_GAME_CAP

    /** The [LedgerPolicy] this policy configures. */
    fun toLedgerPolicy(): LedgerPolicy = LedgerPolicy(
        dailyGameCap = dailyGameCap,
        requireParentSignature = requireParentSignature,
        parentSignatureRequiredFromLamport = parentSignatureFromLamport,
    )

    override fun equals(other: Any?): Boolean = other is HouseholdPolicy &&
        requireParentSignature == other.requireParentSignature &&
        parentSignatureFromLamport == other.parentSignatureFromLamport &&
        (key?.contentEquals(other.key) ?: (other.key == null)) &&
        fiatCurrency == other.fiatCurrency && createdAt == other.createdAt &&
        chatRetentionDays == other.chatRetentionDays && dailyGameCapMinor == other.dailyGameCapMinor

    override fun hashCode(): Int = listOf(requireParentSignature, parentSignatureFromLamport, fiatCurrency).hashCode()

    override fun toString(): String = "HouseholdPolicy(requireParentSignature=$requireParentSignature, " +
        "parentSignatureFromLamport=$parentSignatureFromLamport, hasParentKey=$hasParentKey, " +
        "fiatCurrency=$fiatCurrency, chatRetentionDays=$chatRetentionDays, dailyGameCapMinor=$dailyGameCapMinor)"
}

/** The household policy row. Every change is audited (no key bytes in the audit row). */
interface HouseholdPolicyRepository {
    fun observe(): Flow<HouseholdPolicy?>

    suspend fun get(): HouseholdPolicy?

    /**
     * The policy, creating it with [fiatCurrency] and no parent key if there is none yet. Only household creation and
     * the trial call it: on a real household's phone without its policy row it would publish defaults over the
     * household's real policy (D-REL-11).
     */
    suspend fun ensure(fiatCurrency: Currency.Fiat): HouseholdPolicy

    /**
     * Registers [publicKeyDer] (X.509 SPKI DER of the parent authentication key) **locally** and turns
     * `requireParentSignature` on. The signature is required from the next lamport this device will use, so parent
     * events written before the key existed stay valid; re-registering (a new key after a biometric enrolment change)
     * keeps the original from-lamport. The policy document is republished with `requireParentSignature` on.
     *
     * Other devices learn the key only from the server registry: the app registers it with the `registerParentKey`
     * callable (D-M3-34; clients cannot write `parentKeys`) and then calls [recordServerRegisteredKey]. The local
     * `parent_keys` row written here (valid from the next local lamport) only lets this device verify its own parent
     * signatures before the first registry pull. No `parent_key` document is queued.
     *
     * Never creates a real household's policy (D-REL-11): a phone restored or linked before its first pull would
     * publish its locale's currency and signatures off over the household's real policy. Only the trial household's
     * policy is created here (with the locale's currency); the overload with a recovered currency is the one exception
     * for a real household (D-REL-14).
     *
     * @throws NoHouseholdPolicyYetException when this device has no policy row yet in a real household; nothing is
     *   written or queued.
     */
    suspend fun registerParentKey(publicKeyDer: ByteArray): HouseholdPolicy

    /**
     * [registerParentKey], recovering a real household's missing policy first (D-REL-14): when this device has no
     * policy row, [recoveredCurrency] is not null, the session is a parent's and no pulled `household_policy` is held
     * in `document_inbox`, the policy is created with [recoveredCurrency] and no parent key, and the key is registered
     * over it in the same transaction, so the policy document is published once, with signatures on. The caller
     * vouches for [recoveredCurrency]: the currency the server wrote on the household at creation, read after a pull
     * that completed without an error, while the server stores no policy document (never the locale's). Otherwise
     * as [registerParentKey].
     *
     * The default ignores [recoveredCurrency] (test fakes).
     */
    suspend fun registerParentKey(publicKeyDer: ByteArray, recoveredCurrency: Currency.Fiat?): HouseholdPolicy =
        registerParentKey(publicKeyDer)

    /**
     * True when a pulled `household_policy` document is held in `document_inbox` (its author device unknown or
     * pending, or its lamport too far ahead, D-M3-31): the household has a policy this device has not applied yet, so
     * nothing may recover one (D-REL-14). The default is never held (test fakes).
     */
    suspend fun policyHeld(): Boolean = false

    /**
     * Records the server's registration of the parent key [fingerprint] (the `registerParentKey` callable's response,
     * D-M3-34): the local `parent_keys` row takes the server's [validFromLamport], and this device's clock moves up so
     * the next local lamport is at least [validFromLamport] (bounded by `maxLamportJump`): a parent signature made
     * after this call is covered by the key on every device. Audited `PARENT_KEY_REGISTERED`. Returns false (and does
     * nothing) when no local key has that fingerprint.
     *
     * The default does nothing (test fakes); the Room repository implements it.
     */
    suspend fun recordServerRegisteredKey(fingerprint: String, validFromLamport: Long): Boolean = false

    /**
     * True while this device's registered parent key is not known to the household (D-M3-39): a real household
     * (not the trial), a key in the policy, and no [recordServerRegisteredKey] for that key's fingerprint, because the
     * `registerParentKey` call after the local enrolment failed (offline, refused). Other devices then refuse this
     * phone's parent signatures. It cannot be retried silently, since the attestation is bound to a consumed
     * challenge: the parent re-enrols (Settings → Security, "Retry", which renews the key). False otherwise.
     *
     * The default is never pending (test fakes); the Room repository derives it from the policy, the session and the
     * `PARENT_KEY_REGISTERED` audit rows with `server: true`.
     */
    fun observeServerRegistrationPending(): Flow<Boolean> = flowOf(false)

    /**
     * Sets how long chat messages are kept (M7 §2, D-M7-10): [days] within [HouseholdPolicyLimits.CHAT_RETENTION_DAYS],
     * or null to keep them. Parent sessions only ([IllegalStateException] otherwise; the UI asks for parent
     * authorisation first). Republishes the synced `household_policy` document, audited `HOUSEHOLD_POLICY_UPDATED`.
     * Never creates the policy (D-M7-57): a device without its policy row yet would publish defaults over the
     * household's real policy (signatures off, its own currency).
     *
     * @throws IllegalArgumentException for [days] outside the bounds.
     * @throws NoHouseholdPolicyYetException when this device has no policy row yet (it has not synced it).
     */
    suspend fun setChatRetention(days: Int?): HouseholdPolicy =
        throw UnsupportedOperationException("this repository cannot change the chat retention")

    /**
     * Sets the daily game cap (D-M5-4, D-M7-10): [minor] units within [HouseholdPolicyLimits.DAILY_GAME_CAP_MINOR], or
     * null for the ledger default. Parent sessions only, like [setChatRetention]. The cap is synced household policy,
     * so every device projects the same exclusions (ADR-0009); it applies to every day's plays, past days included, and
     * the balances are recomputed at once.
     *
     * @throws IllegalArgumentException for [minor] outside the bounds.
     * @throws NoHouseholdPolicyYetException when this device has no policy row yet, as for [setChatRetention].
     */
    suspend fun setDailyGameCap(minor: Long?): HouseholdPolicy =
        throw UnsupportedOperationException("this repository cannot change the game cap")
}

/**
 * [HouseholdPolicyRepository] on Room. The fiat currency, `requireParentSignature`, the chat retention and the game
 * cap are the synced `household_policy` document (docId = household id); the registered parent key is this device's
 * own and reaches other devices through the server registry (`registerParentKey`, D-M3-34), never as a document.
 * Every write keeps the settings it does not change. A changed game cap rebuilds the ledger's balance caches through
 * [ledger]. [trialCurrency] is the currency of a trial policy created by [registerParentKey] (never a
 * [FiatCurrencyProvider], which reads this repository).
 */
class RoomHouseholdPolicyRepository @Inject constructor(
    private val database: SproutDatabase,
    private val authors: SessionAuthorProvider,
    private val trialCurrency: LocaleFiatCurrencyProvider,
    private val clock: Clock,
    signer: DeviceSigner,
    private val ledger: RoomLedgerRepository,
) : HouseholdPolicyRepository {
    private val policies = database.householdPolicyDao()
    private val writes = DocumentWrites(database, clock, signer)

    override fun observe(): Flow<HouseholdPolicy?> = policies.observe().map { it?.toDomain() }

    override suspend fun get(): HouseholdPolicy? = policies.get()?.toDomain()

    override suspend fun ensure(fiatCurrency: Currency.Fiat): HouseholdPolicy {
        val author = authors.currentAuthor()
        return writes.transaction { policies.get()?.toDomain() ?: create(author, fiatCurrency) }
    }

    override suspend fun registerParentKey(publicKeyDer: ByteArray): HouseholdPolicy =
        registerParentKey(publicKeyDer, recoveredCurrency = null)

    override suspend fun registerParentKey(
        publicKeyDer: ByteArray,
        recoveredCurrency: Currency.Fiat?,
    ): HouseholdPolicy {
        require(publicKeyDer.isNotEmpty()) { "parent public key must not be empty" }
        val author = authors.currentAuthor()
        return writes.transaction {
            val current = policies.get()?.toDomain() ?: when {
                author.householdId == LocalTrial.HOUSEHOLD -> create(author, trialCurrency.fiatCurrency())

                // D-REL-14: a household whose policy was never pushed (the creating phone died before its outbox did).
                recoveredCurrency != null && author.role == Role.PARENT && !heldPolicy(author.householdId.value) ->
                    create(author, recoveredCurrency)

                else -> throw NoHouseholdPolicyYetException()
            }
            val validFrom = writes.peekLamport()
            val from = current.parentSignatureFromLamport ?: validFrom
            val fingerprint = JvmSha256Hasher.sha256(publicKeyDer).toHex()
            writes.auditOnly(author, AuditAction.PARENT_KEY_REGISTERED, fingerprint) {
                put("fromLamport", from)
                put("validFromLamport", validFrom)
                put("replacedKey", current.hasParentKey)
                put(SERVER_DETAIL, false)
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
                    chatRetentionDays = current.chatRetentionDays,
                    dailyGameCapMinor = current.dailyGameCapMinor,
                ),
            ).toDomain()
        }
    }

    override suspend fun policyHeld(): Boolean = heldPolicy(authors.currentAuthor().householdId.value)

    private suspend fun heldPolicy(householdId: String): Boolean =
        database.documentInboxDao().get(DocTypes.HOUSEHOLD_POLICY, householdId) != null

    override fun observeServerRegistrationPending(): Flow<Boolean> = combine(
        policies.observe(),
        database.sessionDao().observe(),
        database.auditLogDao().observeAction(AuditAction.PARENT_KEY_REGISTERED),
    ) { policy, session, registrations ->
        val key = policy?.parentPublicKey
        if (key == null || session == null || session.householdId == LocalTrial.HOUSEHOLD) {
            false
        } else {
            val fingerprint = JvmSha256Hasher.sha256(key).toHex()
            registrations.none { it.target == fingerprint && it.deviceId == session.deviceId && it.isServerRecord() }
        }
    }.distinctUntilChanged()

    override suspend fun recordServerRegisteredKey(fingerprint: String, validFromLamport: Long): Boolean {
        require(validFromLamport >= 0) { "validFromLamport must not be negative" }
        val author = authors.currentAuthor()
        return writes.transaction {
            val keys = database.parentKeyDao()
            val key = keys.get(fingerprint)
            if (key == null || key.deviceId != author.deviceId) {
                false
            } else {
                keys.upsert(key.copy(validFromLamport = validFromLamport))
                // The next local lamport must be covered by the key everywhere: at least validFromLamport.
                val current = writes.peekLamport() - 1
                val target = minOf(validFromLamport - 1, current + LedgerPolicy.DEFAULT_MAX_LAMPORT_JUMP)
                if (target > current) database.advanceDeviceLamport(author.deviceId, target)
                writes.auditOnly(author, AuditAction.PARENT_KEY_REGISTERED, fingerprint) {
                    put("validFromLamport", validFromLamport)
                    put(SERVER_DETAIL, true)
                }
                true
            }
        }
    }

    override suspend fun setChatRetention(days: Int?): HouseholdPolicy {
        require(days == null || days in HouseholdPolicyLimits.CHAT_RETENTION_DAYS) { "chat retention out of range" }
        return update { it.copy(chatRetentionDays = days) }
    }

    override suspend fun setDailyGameCap(minor: Long?): HouseholdPolicy {
        require(minor == null || minor in HouseholdPolicyLimits.DAILY_GAME_CAP_MINOR) { "game cap out of range" }
        var before: Long? = null
        val updated = update { current ->
            before = current.dailyGameCapMinor
            current.copy(dailyGameCapMinor = minor)
        }
        // The cap decides which plays count on every day: the cached balances follow it at once. The rebuild binds the
        // local check, so it cannot join the write's transaction; it is retried instead (review L8).
        if (before != minor) ledger.rebuildCachesRetrying()
        return updated
    }

    /**
     * Rewrites the existing policy row with [change] applied (read inside the transaction); parent sessions only.
     * Refuses with [NoHouseholdPolicyYetException] when there is no row (D-M7-57).
     */
    private suspend fun update(change: (HouseholdPolicyEntity) -> HouseholdPolicyEntity): HouseholdPolicy {
        val author = authors.currentAuthor()
        check(author.role == Role.PARENT) { "only a parent changes the household policy" }
        return writes.transaction {
            val current = policies.get() ?: throw NoHouseholdPolicyYetException()
            write(author, change(current)).toDomain()
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
            DocumentPayloads.householdPolicy(
                fiat,
                row.requireParentSignature,
                row.chatRetentionDays,
                row.dailyGameCapMinor,
            ),
            AuditAction.HOUSEHOLD_POLICY_UPDATED,
        ) {
            put("fiatCurrency", fiat.isoCode)
            put("requireParentSignature", row.requireParentSignature)
            put("chatRetentionDays", row.chatRetentionDays)
            put("dailyGameCapMinor", row.dailyGameCapMinor)
        }
        return row.copy(lamport = lamport, stamp = stamp).also { policies.upsert(it) }
    }
}

/** A `PARENT_KEY_REGISTERED` row written by [RoomHouseholdPolicyRepository.recordServerRegisteredKey]. */
private fun AuditLogEntity.isServerRecord(): Boolean = try {
    val details = LedgerJson.json.parseToJsonElement(detailsJson) as? JsonObject
    (details?.get(SERVER_DETAIL) as? JsonPrimitive)?.booleanOrNull == true
} catch (expected: SerializationException) {
    false
}

private const val SERVER_DETAIL = "server"

internal fun HouseholdPolicyEntity.toDomain() = HouseholdPolicy(
    requireParentSignature = requireParentSignature,
    parentSignatureFromLamport = parentSignatureFromLamport,
    parentPublicKey = parentPublicKey,
    fiatCurrency = fiatCurrency as? Currency.Fiat ?: error("household currency must be fiat"),
    createdAt = Instant.fromEpochMilliseconds(createdAtEpochMs),
    chatRetentionDays = chatRetentionDays,
    dailyGameCapMinor = dailyGameCapMinor,
)

/**
 * A policy setting was changed, or a parent key registered, on a device that has no `household_policy` row yet (a
 * newly linked or restored phone before its first pull, or a pulled policy that is still held): refused, so it never
 * publishes defaults over the household's real policy (D-M7-57, D-REL-11). The screen says it is waiting for the
 * household policy to sync.
 */
class NoHouseholdPolicyYetException : IllegalStateException("no household policy yet")

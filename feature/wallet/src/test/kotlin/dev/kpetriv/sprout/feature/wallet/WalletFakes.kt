package dev.kpetriv.sprout.feature.wallet

import dev.kpetriv.sprout.core.domainledger.Account
import dev.kpetriv.sprout.core.domainledger.AccountKind
import dev.kpetriv.sprout.core.domainledger.AppendResult
import dev.kpetriv.sprout.core.domainledger.Balance
import dev.kpetriv.sprout.core.domainledger.BatchAppendResult
import dev.kpetriv.sprout.core.domainledger.BatchEntry
import dev.kpetriv.sprout.core.domainledger.Currency
import dev.kpetriv.sprout.core.domainledger.Envelope
import dev.kpetriv.sprout.core.domainledger.EventId
import dev.kpetriv.sprout.core.domainledger.LedgerAnomaly
import dev.kpetriv.sprout.core.domainledger.LedgerEvent
import dev.kpetriv.sprout.core.domainledger.LedgerRepository
import dev.kpetriv.sprout.core.domainledger.LedgerWriter
import dev.kpetriv.sprout.core.domainledger.Money
import dev.kpetriv.sprout.core.domainledger.ParentAuthorization
import dev.kpetriv.sprout.core.domainledger.ValidationResult
import dev.kpetriv.sprout.core.domainledger.parentSignedPayload
import dev.kpetriv.sprout.core.model.AccountId
import dev.kpetriv.sprout.core.model.DeviceId
import dev.kpetriv.sprout.core.model.HouseholdId
import dev.kpetriv.sprout.core.model.Kid
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.KidUiTier
import dev.kpetriv.sprout.core.model.Role
import dev.kpetriv.sprout.core.model.UserId
import dev.kpetriv.sprout.core.model.YearMonth
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlin.time.Instant

/** In-memory [WalletDataSource]. Set [failing] to make every read throw. */
class InMemoryWalletDataSource : WalletDataSource {
    val kids = MutableStateFlow<Map<KidId, Kid>>(emptyMap())
    val accounts = MutableStateFlow<Map<KidId, List<Account>>>(emptyMap())
    var failing = false

    override fun observeKid(kidId: KidId): Flow<Kid?> = guarded(kids.map { it[kidId] })

    override fun accountsFor(kidId: KidId): Flow<List<Account>> = guarded(accounts.map { it[kidId].orEmpty() })

    private fun <T> guarded(source: Flow<T>): Flow<T> = flow {
        check(!failing) { "storage unavailable" }
        source.collect { emit(it) }
    }
}

/** [LedgerRepository] backed by state flows; records the history limits it was asked for. */
class FakeLedgerRepository : LedgerRepository {
    val balances = MutableStateFlow<List<Balance>>(emptyList())
    val events = MutableStateFlow<List<LedgerEvent>>(emptyList())
    val anomalies = MutableStateFlow<List<LedgerAnomaly>>(emptyList())
    val requestedLimits = mutableListOf<Int>()

    override fun observeBalances(kidId: KidId): Flow<List<Balance>> = balances

    override fun observeAnomalies(kidId: KidId): Flow<List<LedgerAnomaly>> = anomalies

    override fun observeEvents(kidId: KidId, limit: Int): Flow<List<LedgerEvent>> {
        requestedLimits += limit
        return events.map { it.take(limit) }
    }

    override suspend fun append(event: LedgerEvent): AppendResult = AppendResult.Accepted

    override suspend fun latestLamport(): Long = 0

    override suspend fun headHash(authorDeviceId: DeviceId): String? = null
}

/**
 * [LedgerWriter] that builds the event with a test envelope and returns [nextResult] (or throws [failure]). When
 * [gate] is set, each append waits for it first, so a test can act while the write is in flight.
 */
class FakeLedgerWriter : LedgerWriter {
    val appended = mutableListOf<LedgerEvent>()
    var nextResult: AppendResult = AppendResult.Accepted
    var failure: Exception? = null
    var gate: CompletableDeferred<Unit>? = null

    /** Events appended through [appendAsParent], with the authorization each one was signed with. */
    val parentSigned = mutableListOf<Pair<LedgerEvent, ParentAuthorization>>()

    override suspend fun append(kidId: KidId, accountId: AccountId, body: (Envelope) -> LedgerEvent): AppendResult {
        gate?.await()
        failure?.let { throw it }
        val event = body(TestData.envelope(appended.size + 100, kidId, accountId))
        appended += event
        return nextResult
    }

    /** Like [append], and calls [ParentAuthorization.sign] once, as the real writer does. */
    override suspend fun appendAsParent(
        kidId: KidId,
        accountId: AccountId,
        authorization: ParentAuthorization,
        body: (Envelope) -> LedgerEvent,
    ): AppendResult {
        gate?.await()
        failure?.let { throw it }
        val event = body(TestData.envelope(appended.size + 100, kidId, accountId))
        authorization.sign(event.parentSignedPayload())
        appended += event
        parentSigned += event to authorization
        return nextResult
    }

    /** What [validateAsParent] answers; checked before any prompt by the use cases. */
    var nextValidation: ValidationResult = ValidationResult.Valid

    override suspend fun appendAsKid(
        kidId: KidId,
        accountId: AccountId,
        body: (Envelope) -> LedgerEvent,
    ): AppendResult = append(kidId, accountId, body)

    override suspend fun appendAllAsKid(kidId: KidId, entries: List<BatchEntry>): BatchAppendResult {
        gate?.await()
        failure?.let { throw it }
        val built = mutableListOf<LedgerEvent>()
        entries.forEach { entry ->
            built += entry.body(TestData.envelope(appended.size + built.size + 100, kidId, entry.accountId), built)
        }
        if (nextResult != AppendResult.Accepted) return BatchAppendResult.Rejected(0, nextResult)
        appended += built
        return BatchAppendResult.Accepted(built)
    }

    override suspend fun validateAsParent(
        kidId: KidId,
        accountId: AccountId,
        body: (Envelope) -> LedgerEvent,
    ): ValidationResult {
        body(TestData.envelope(appended.size + 100, kidId, accountId))
        return nextValidation
    }
}

object TestData {
    val gbp = Currency.Fiat("GBP")
    val kidId = KidId("kid-1")
    val mainId = AccountId("acc-main")
    val minutesId = AccountId("acc-minutes")
    val savingsId = AccountId("acc-savings")

    val accounts = listOf(
        Account(savingsId, kidId, gbp, AccountKind.SAVINGS),
        Account(minutesId, kidId, Currency.Minutes, AccountKind.MAIN),
        Account(mainId, kidId, gbp, AccountKind.MAIN),
    )

    fun kid(tier: KidUiTier, name: String = "Mia") = Kid(
        id = kidId,
        householdId = HouseholdId("household-1"),
        firstName = name,
        avatarId = "fox",
        birthYearMonth = YearMonth(2018, 3),
        uiTier = tier,
    )

    fun balance(accountId: AccountId, currency: Currency, minor: Long) =
        Balance(accountId, currency, Money(minor), Instant.fromEpochMilliseconds(0), lastLamport = 1)

    fun envelope(index: Int, kidId: KidId = this.kidId, accountId: AccountId = mainId) = Envelope(
        eventId = EventId("00000000-0000-4000-8000-%012d".format(index)),
        householdId = HouseholdId("household-1"),
        kidId = kidId,
        accountId = accountId,
        authorUid = UserId("parent-1"),
        authorRole = Role.PARENT,
        authorDeviceId = DeviceId("device-1"),
        createdAt = Instant.fromEpochMilliseconds(1_700_000_000_000L + index),
        lamport = index.toLong(),
        prevHash = null,
        hash = Envelope.UNHASHED,
    )

    /** Seeds [source] and [repository] with one kid on [tier]: £7.45 main, 80 min, £12.50 savings. */
    fun seed(source: InMemoryWalletDataSource, repository: FakeLedgerRepository, tier: KidUiTier) {
        source.kids.value = mapOf(kidId to kid(tier))
        source.accounts.value = mapOf(kidId to accounts)
        repository.balances.value = listOf(
            balance(mainId, gbp, 745),
            balance(minutesId, Currency.Minutes, 80),
            balance(savingsId, gbp, 1_250),
        )
    }

    fun setTier(source: InMemoryWalletDataSource, tier: KidUiTier) =
        source.kids.update { kids -> kids.mapValues { it.value.copy(uiTier = tier) } }
}

class FakeReceiptScanner : ReceiptScanner {
    var result: AdjustmentPrefill? = null
    var failure: Throwable? = null

    /** When set, [scan] suspends until it completes. */
    var gate: CompletableDeferred<Unit>? = null

    /** How long each scan takes (virtual time). */
    var delayMillis: Long = 0
    val scanned = mutableListOf<String>()

    override suspend fun scan(imageUri: String): AdjustmentPrefill? {
        scanned += imageUri
        gate?.await()
        if (delayMillis > 0) delay(delayMillis)
        failure?.let { throw it }
        return result
    }
}

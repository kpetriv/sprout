package dev.kpetriv.sprout.core.database.usecase

import dev.kpetriv.sprout.core.database.AccountRepository
import dev.kpetriv.sprout.core.database.RoomLedgerRepository
import dev.kpetriv.sprout.core.database.SessionStore
import dev.kpetriv.sprout.core.domainledger.Account
import dev.kpetriv.sprout.core.domainledger.AppendResult
import dev.kpetriv.sprout.core.domainledger.Currency
import dev.kpetriv.sprout.core.domainledger.LedgerWriter
import dev.kpetriv.sprout.core.domainledger.Money
import dev.kpetriv.sprout.core.domainledger.Projection
import dev.kpetriv.sprout.core.domainledger.TimeBankStarted
import dev.kpetriv.sprout.core.domainledger.TimeBankState
import dev.kpetriv.sprout.core.domainledger.TimeBankStopped
import dev.kpetriv.sprout.core.model.DeviceId
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.Role
import dev.kpetriv.sprout.core.time.Clock
import kotlinx.coroutines.flow.first
import java.util.UUID
import javax.inject.Inject
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * A kid's time bank right now, from the ledger projection (M4 §4, D-M4-8): [state] is the projection's
 * [TimeBankState] (null before the first session), [balance] the whole minutes in the kid's minutes account.
 * [startedAt] is the `TimeBankStarted` envelope's `createdAt`, so elapsed time survives process death.
 */
data class TimeBankStatus(val kidId: KidId, val account: Account, val state: TimeBankState?, val balance: Money) {
    val running: Boolean get() = state?.running == true
    val startedAt: Instant? get() = state?.startedAt?.takeIf { running }

    /** Whole minutes elapsed since [startedAt] at [now] (0 when not running or the clock is behind the start). */
    fun elapsedMinutes(now: Instant): Long {
        val start = startedAt ?: return 0
        return (now - start).coerceAtLeast(Duration.ZERO).inWholeMinutes
    }

    /** What stopping at [now] debits: `min(elapsed whole minutes, balance)`, never negative. */
    fun minutesToDebit(now: Instant): Long = minOf(elapsedMinutes(now), balance.minorUnits.coerceAtLeast(0))
}

/** Why a time bank use case refused. */
enum class TimeBankRefusal {
    /** A session is already running. */
    ALREADY_RUNNING,

    /** No session is running. */
    NOT_RUNNING,

    /** The balance is zero or less. */
    NO_BALANCE,

    /** The kid has no minutes account. */
    NO_ACCOUNT,
}

/** Outcome of [StartTimeBankUseCase] and [StopTimeBankUseCase]. */
sealed interface TimeBankResult {
    /** A session started at [startedAt] ([sessionId]). */
    data class Started(val sessionId: String, val startedAt: Instant) : TimeBankResult

    /** The session stopped; [minutesUsed] whole minutes were debited. */
    data class Stopped(val sessionId: String, val minutesUsed: Long) : TimeBankResult

    /** Refused before anything was written. */
    data class Refused(val reason: TimeBankRefusal) : TimeBankResult

    /** The ledger refused the event ([result]); nothing was written. */
    data class Rejected(val result: AppendResult) : TimeBankResult
}

/** Reads [TimeBankStatus] from the ledger projection. */
class TimeBankStatusReader @Inject constructor(
    private val ledger: RoomLedgerRepository,
    private val accounts: AccountRepository,
) {
    /** [kidId]'s time bank, or null when the kid has no minutes account. */
    suspend fun status(kidId: KidId): TimeBankStatus? = status(kidId, ledger.projection())

    internal suspend fun status(kidId: KidId, projection: Projection): TimeBankStatus? {
        val account = accounts.accountsFor(kidId).first().main(Currency.Minutes) ?: return null
        return TimeBankStatus(kidId, account, projection.timeBank(kidId), projection.balanceOf(account.id))
    }
}

/**
 * Opens a time bank session for [KidId] (M4 §4): a kid-authored `TimeBankStarted` (fresh session id) on the kid's
 * minutes account. Refused when a session is running or the balance is zero or less; the ledger checks the same rules
 * again when it appends.
 */
class StartTimeBankUseCase @Inject constructor(
    private val reader: TimeBankStatusReader,
    private val writer: LedgerWriter,
) {
    suspend operator fun invoke(kidId: KidId): TimeBankResult {
        val status = reader.status(kidId) ?: return TimeBankResult.Refused(TimeBankRefusal.NO_ACCOUNT)
        return when {
            status.running -> TimeBankResult.Refused(TimeBankRefusal.ALREADY_RUNNING)

            !status.balance.isPositive -> TimeBankResult.Refused(TimeBankRefusal.NO_BALANCE)

            else -> {
                val sessionId = UUID.randomUUID().toString()
                var startedAt: Instant? = null
                val result = writer.appendAsKid(kidId, status.account.id) { envelope ->
                    startedAt = envelope.createdAt
                    TimeBankStarted(envelope, sessionId)
                }
                if (result is AppendResult.Accepted) {
                    TimeBankResult.Started(sessionId, requireNotNull(startedAt))
                } else {
                    TimeBankResult.Rejected(result)
                }
            }
        }
    }
}

/**
 * Closes [KidId]'s running session (M4 §4): a kid-authored `TimeBankStopped` debiting `min(elapsed whole minutes,
 * balance)`, elapsed being `now - TimeBankState.startedAt` (the start event's `createdAt`), never a process timer.
 */
class StopTimeBankUseCase @Inject constructor(
    private val reader: TimeBankStatusReader,
    private val writer: LedgerWriter,
    private val clock: Clock,
) {
    @Suppress("ReturnCount") // One early return per refusal.
    suspend operator fun invoke(kidId: KidId): TimeBankResult {
        val status = reader.status(kidId) ?: return TimeBankResult.Refused(TimeBankRefusal.NO_ACCOUNT)
        if (!status.running) return TimeBankResult.Refused(TimeBankRefusal.NOT_RUNNING)
        return stop(writer, status, status.minutesToDebit(clock.now()))
    }
}

/**
 * The start-up reconcile (M4 §4, D-M4-8): every running session that has outlived its balance (elapsed whole minutes
 * at least the balance) is stopped with `minutesUsed = balance`, as if the auto-stop job had fired at zero. Only
 * sessions this device started are reconciled (on a KID device, only its kid's), so two devices never both stop the
 * same session; the kid's device covers its own. Returns the kids whose session was stopped.
 */
class ReconcileTimeBankUseCase @Inject constructor(
    private val ledger: RoomLedgerRepository,
    private val reader: TimeBankStatusReader,
    private val writer: LedgerWriter,
    private val sessions: SessionStore,
    private val clock: Clock,
) {
    suspend operator fun invoke(): List<KidId> {
        val session = sessions.get() ?: return emptyList()
        val snapshot = ledger.snapshot()
        val now = clock.now()
        return snapshot.projection.timeBanks.values
            .filter { it.running && (session.role != Role.KID || it.kidId == session.kidId) }
            .filter { startedOnThisDevice(snapshot.events, it, session.deviceId) }
            .mapNotNull { state ->
                val status = reader.status(state.kidId, snapshot.projection) ?: return@mapNotNull null
                val balance = status.balance.minorUnits.coerceAtLeast(0)
                val due = status.elapsedMinutes(now) >= balance
                state.kidId.takeIf { due && stop(writer, status, balance) is TimeBankResult.Stopped }
            }
    }

    private fun startedOnThisDevice(
        events: List<dev.kpetriv.sprout.core.domainledger.LedgerEvent>,
        state: TimeBankState,
        self: DeviceId,
    ): Boolean = events.any {
        it is TimeBankStarted && it.sessionId == state.sessionId && it.envelope.authorDeviceId == self
    }
}

/** Appends the kid-authored stop of [status]'s running session, debiting [minutes]. */
private suspend fun stop(writer: LedgerWriter, status: TimeBankStatus, minutes: Long): TimeBankResult {
    val sessionId = requireNotNull(status.state?.sessionId) { "no running session" }
    val result = writer.appendAsKid(status.kidId, status.account.id) { envelope ->
        TimeBankStopped(envelope, sessionId, Money(minutes))
    }
    return if (result is AppendResult.Accepted) {
        TimeBankResult.Stopped(sessionId, minutes)
    } else {
        TimeBankResult.Rejected(result)
    }
}

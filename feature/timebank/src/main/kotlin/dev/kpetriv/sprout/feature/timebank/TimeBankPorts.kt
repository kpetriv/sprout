package dev.kpetriv.sprout.feature.timebank

import dev.kpetriv.sprout.core.model.KidId
import kotlinx.coroutines.flow.Flow
import java.time.ZoneId
import kotlin.time.Instant

/*
 * Ports of the time bank screen. The app adapts them to the ledger projection (`TimeBankState`) and the time bank use
 * cases in `core/database` (M4 §4); this module depends on neither.
 */

/**
 * The kid's time bank as the ledger projects it: whether a session is running, since when (the `TimeBankStarted`
 * envelope's `createdAt`, so elapsed time survives process death), the balance, and the finished sessions.
 */
fun interface TimeBankStateSource {
    /** [kidId]'s time bank; null while the kid has no time bank account (shown as an empty balance). */
    fun observe(kidId: KidId): Flow<TimeBankSnapshot?>
}

/**
 * @property running a session is open (`TimeBankStarted` without its `TimeBankStopped`).
 * @property startedAt when the open session started; null when not [running].
 * @property balanceMinutes whole minutes in the time bank account. A running session is not debited until it stops,
 *   so the minutes left right now are `balanceMinutes` minus the session's elapsed whole minutes.
 * @property sessions finished sessions, newest first; the screen shows today's (Sprout) or the most recent (Sapling).
 */
data class TimeBankSnapshot(
    val running: Boolean,
    val startedAt: Instant?,
    val balanceMinutes: Long,
    val sessions: List<TimeBankSession> = emptyList(),
)

/** A finished session: when it started and the whole minutes it debited. */
data class TimeBankSession(val startedAt: Instant, val minutesUsed: Long)

/**
 * Starts and stops the kid's session (`StartTimeBankUseCase` / `StopTimeBankUseCase`). Both write a kid-authored
 * ledger event; the screen then follows [TimeBankStateSource]. A refusal is a failed [Result] holding a
 * [TimeBankRefusedException]; anything else is shown as a generic failure.
 */
interface TimeBankControl {
    /** Opens a session for [kidId]; refused when one is running or the balance is zero or less. */
    suspend fun start(kidId: KidId): Result<Unit>

    /** Closes [kidId]'s session; the result is the whole minutes debited (`min(elapsed, balance)`). */
    suspend fun stop(kidId: KidId): Result<Long>
}

/** Why [TimeBankControl] refused. */
enum class TimeBankRefusal {
    ALREADY_RUNNING,
    NOT_RUNNING,
    NO_BALANCE,
}

/** The failure in a refused [TimeBankControl] result. */
class TimeBankRefusedException(val reason: TimeBankRefusal) : Exception("Time bank refused: $reason")

/**
 * Time for the screen: the current instant, the household's zone (what "today" is), and a ticker while a session runs.
 * The screen ticks locally; elapsed time is always `now() - startedAt`, never a counter.
 */
interface TimeBankClock {
    fun now(): Instant

    fun zone(): ZoneId

    /** [now] at once and then about every second, for as long as it is collected. */
    fun ticks(): Flow<Instant>
}

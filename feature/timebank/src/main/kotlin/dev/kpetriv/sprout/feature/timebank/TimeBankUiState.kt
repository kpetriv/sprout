package dev.kpetriv.sprout.feature.timebank

import dev.kpetriv.sprout.core.model.KidUiTier
import java.time.LocalDateTime

/** Everything the time bank screen shows. One instance per emission of [TimeBankViewModel.state]. */
sealed interface TimeBankUiState {
    data object Loading : TimeBankUiState

    data object Error : TimeBankUiState

    /**
     * @property balanceMinutes the account balance; a running session is debited only when it stops.
     * @property elapsedSeconds how long the running session has run (0 when idle).
     * @property remainingSeconds what is left of the balance right now: `balanceMinutes * 60 - elapsedSeconds`, never
     *   negative.
     * @property today finished sessions that started today (household zone), newest first.
     * @property history the most recent finished sessions (at most [HISTORY_SIZE]), newest first.
     * @property isBusy a start or stop is being written.
     * @property message the outcome of the last start or stop, until [TimeBankEvent.MessageShown].
     */
    data class Ready(
        val tier: KidUiTier,
        val balanceMinutes: Long,
        val running: Boolean,
        val elapsedSeconds: Long,
        val remainingSeconds: Long,
        val today: List<SessionUi>,
        val history: List<SessionUi>,
        val isBusy: Boolean = false,
        val message: TimeBankMessage? = null,
    ) : TimeBankUiState {
        /** Minutes left, in whole minutes as the debit counts them (a started minute is still yours). */
        val remainingMinutes: Long
            get() = (remainingSeconds + SECONDS_PER_MINUTE - 1) / SECONDS_PER_MINUTE

        /** Nothing to spend and nothing running. */
        val isEmpty: Boolean get() = !running && balanceMinutes <= 0

        /** The session has used the whole balance; the app stops it, or the kid presses Stop. */
        val timeUp: Boolean get() = running && remainingSeconds <= 0

        val canStart: Boolean get() = !running && balanceMinutes > 0 && !isBusy

        val canStop: Boolean get() = running && !isBusy

        /** How full the Seedling ring is: the share of the balance still left (1 when idle, 0 when empty). */
        val ringFraction: Float
            get() = when {
                balanceMinutes <= 0 -> 0f
                !running -> 1f
                else -> remainingSeconds.toFloat() / (balanceMinutes * SECONDS_PER_MINUTE)
            }

        /** Minutes used today in finished sessions. */
        val todayMinutes: Long get() = today.sumOf { it.minutes }

        companion object {
            const val HISTORY_SIZE: Int = 20
            const val SECONDS_PER_MINUTE: Long = 60
        }
    }
}

/** A finished session, in household-local time. */
data class SessionUi(val startedAt: LocalDateTime, val minutes: Long)

/** The outcome of a start or stop, shown once. */
sealed interface TimeBankMessage {
    /** Stopped; [minutes] were taken from the balance. */
    data class Stopped(val minutes: Long) : TimeBankMessage

    /** Could not start; [refusal] when the ledger said why. */
    data class StartFailed(val refusal: TimeBankRefusal?) : TimeBankMessage

    data object StopFailed : TimeBankMessage
}

/** Everything the time bank screen can ask the [TimeBankViewModel] to do. */
sealed interface TimeBankEvent {
    data object Start : TimeBankEvent

    data object Stop : TimeBankEvent

    data object MessageShown : TimeBankEvent

    data object Retry : TimeBankEvent
}

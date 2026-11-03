package dev.kpetriv.sprout.feature.timebank

import dev.kpetriv.sprout.core.model.KidUiTier
import java.time.LocalDateTime

/** Fixed time bank states for previews, screen tests and screenshot goldens. */
internal object TimeBankPreviewData {
    private const val BALANCE = 45L
    private const val ELAPSED_SECONDS = 12L * 60 + 34
    private val today = LocalDateTime.of(2026, 11, 4, 0, 0)

    private val todaySessions = listOf(
        SessionUi(today.withHour(16).withMinute(5), 20),
        SessionUi(today.withHour(7).withMinute(40), 15),
    )
    private val history = todaySessions + listOf(
        SessionUi(today.minusDays(1).withHour(18).withMinute(30), 30),
        SessionUi(today.minusDays(2).withHour(17).withMinute(10), 25),
        SessionUi(today.minusDays(4).withHour(15).withMinute(45), 40),
    )

    fun idle(tier: KidUiTier) = TimeBankUiState.Ready(
        tier = tier,
        balanceMinutes = BALANCE,
        running = false,
        elapsedSeconds = 0,
        remainingSeconds = BALANCE * TimeBankUiState.Ready.SECONDS_PER_MINUTE,
        today = todaySessions,
        history = history,
    )

    fun running(tier: KidUiTier) = idle(tier).copy(
        running = true,
        elapsedSeconds = ELAPSED_SECONDS,
        remainingSeconds = BALANCE * TimeBankUiState.Ready.SECONDS_PER_MINUTE - ELAPSED_SECONDS,
    )

    fun empty(tier: KidUiTier) = idle(tier).copy(balanceMinutes = 0, remainingSeconds = 0)

    /** The whole balance is used up while the session still runs (the auto-stop or the kid stops it). */
    fun timeUp(tier: KidUiTier) = idle(tier).copy(
        running = true,
        elapsedSeconds = BALANCE * TimeBankUiState.Ready.SECONDS_PER_MINUTE,
        remainingSeconds = 0,
    )
}

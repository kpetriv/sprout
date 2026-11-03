package dev.kpetriv.sprout.core.domainledger

import dev.kpetriv.sprout.core.model.CalendarDate
import kotlin.time.Instant

/**
 * How far a [GamePlayed.playedOn] may be from the date the event reached the household (D-M5-5, THREAT_MODEL T12,
 * ADR-0009): at most [DAYS_AHEAD] day later and [DAYS_BEHIND] days earlier. `playedOn` is author-declared and the
 * daily game cap is counted per `playedOn`, so without a window a kid device could backdate plays onto every past
 * day's unused cap. Offline play for up to a week still syncs. The window holds nine dates (D-7..D+1), so a device
 * that moves its date (no root needed) can claim up to eight days of cap beyond today's.
 *
 * Receivers measure against the UTC date of the server's `serverReceivedAt` stamp: the projection excludes a pulled
 * game outside the window ([LedgerAnomaly.PlayedOnOutOfRange], [Ledger.project]'s `receivedAt`). The stamp is set by
 * the backend (the rules require `request.time`), so every receiver decides the same way whenever it pulls. The
 * authoring device refuses to append one outside the window of its household-local today ([LedgerRules.validate]'s
 * `today`). The author's window is measured in household-local days and the receivers' in UTC days; the one-day
 * slack ahead absorbs the difference between them, and west of UTC a week offline is six to seven local days.
 */
object PlayedOnWindow {
    /** Days `playedOn` may be after the reference date. */
    const val DAYS_AHEAD: Long = 1

    /** Days `playedOn` may be before the reference date. */
    const val DAYS_BEHIND: Long = 7

    private const val SECONDS_PER_DAY = 86_400L

    /** Whether [playedOn] is within [DAYS_BEHIND] days before to [DAYS_AHEAD] days after [reference]. */
    fun contains(playedOn: CalendarDate, reference: CalendarDate): Boolean =
        reference.daysUntil(playedOn) in -DAYS_BEHIND..DAYS_AHEAD

    /**
     * Whether [playedOn] is before the window of [serverDate], the UTC date of a server stamp the device already
     * holds (M5 audit L1). Every receiver's stamp for a play written now is at least that late, so such a play would
     * be excluded everywhere: the author refuses it even when its own (movable) date says otherwise. Only this edge
     * applies: a `playedOn` after the window of an old stamp is normal for a device that has not pulled for a while.
     */
    fun isBeforeWindowOf(playedOn: CalendarDate, serverDate: CalendarDate): Boolean =
        serverDate.daysUntil(playedOn) < -DAYS_BEHIND

    /** The UTC calendar date [instant] falls on, or null outside [CalendarDate.MIN]..[CalendarDate.MAX]. */
    fun utcDateOf(instant: Instant): CalendarDate? = try {
        CalendarDate.fromEpochDay(instant.epochSeconds.floorDiv(SECONDS_PER_DAY))
    } catch (expected: IllegalArgumentException) {
        null
    }
}

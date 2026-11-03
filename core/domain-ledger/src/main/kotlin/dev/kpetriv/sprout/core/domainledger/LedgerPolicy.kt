package dev.kpetriv.sprout.core.domainledger

/**
 * Household-tunable limits the ledger enforces.
 *
 * **Device-local for now.** A policy is configured on each device and is not yet part of the event stream, so two
 * devices with different settings can project the same events differently (most visibly for [dailyGameCap]).
 * Policy events that make every limit part of the signed history are planned for M2; until then keep the defaults
 * or configure every device of a household identically.
 *
 * @property dailyGameCap the most [GamePlayed] may credit per kid, per [GamePlayed.playedOn] date, per currency,
 *   in that currency's minor units (pence for GBP, minutes for [Currency.Minutes]). A game that would go over the
 *   cap is rejected at validation and excluded (not clamped) when projecting merged streams.
 * @property dailyGameCapOverrides per-currency caps that replace [dailyGameCap] for that currency.
 * @property maxAmount the largest absolute amount any single event may carry (and the largest interest principal).
 *   Keeps every sum a projection computes far away from `Long` overflow.
 * @property maxLamportJump how far above the highest lamport seen locally a new or pulled event may be. A forged
 *   lamport near `Long.MAX_VALUE` would otherwise sort after everything forever and exhaust every device's clock.
 */
data class LedgerPolicy(
    val dailyGameCap: Money = DEFAULT_DAILY_GAME_CAP,
    val dailyGameCapOverrides: Map<Currency, Money> = emptyMap(),
    val maxAmount: Money = DEFAULT_MAX_AMOUNT,
    val maxLamportJump: Long = DEFAULT_MAX_LAMPORT_JUMP,
) {
    init {
        require(!dailyGameCap.isNegative) { "dailyGameCap must not be negative" }
        require(dailyGameCapOverrides.values.none { it.isNegative }) { "daily game caps must not be negative" }
        require(maxAmount.isPositive) { "maxAmount must be positive" }
        require(maxLamportJump > 0) { "maxLamportJump must be positive" }
    }

    /** The daily game cap that applies to credits in [currency]. */
    fun dailyGameCapFor(currency: Currency): Money = dailyGameCapOverrides[currency] ?: dailyGameCap

    companion object {
        /** 50 minor units (50p, or 50 minutes) a day unless the household configures otherwise. */
        val DEFAULT_DAILY_GAME_CAP: Money = Money(50)

        /** 10^12 minor units (ten billion pounds, or about 1.9 million years of minutes). */
        val DEFAULT_MAX_AMOUNT: Money = Money(1_000_000_000_000)

        /** A million local events ahead of everything seen; far beyond any honest offline gap. */
        const val DEFAULT_MAX_LAMPORT_JUMP: Long = 1_000_000
    }
}

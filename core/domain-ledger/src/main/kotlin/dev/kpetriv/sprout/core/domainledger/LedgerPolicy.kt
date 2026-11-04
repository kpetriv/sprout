package dev.kpetriv.sprout.core.domainledger

/**
 * Household-tunable limits the ledger enforces.
 *
 * **Where the values come from.** The data layer builds the policy from the household's synced `household_policy`
 * document: [requireParentSignature] (M3) and, since M7, [dailyGameCap] (`dailyGameCapMinor`, D-M5-4, D-M7-10), so
 * every device of a household projects the same events the same way. The other limits are these defaults on every
 * device. Two devices with different values would project differently (most visibly for [dailyGameCap], and for
 * [requireParentSignature]: a device that requires it reports unsigned parent-only events as
 * [LedgerAnomaly.InvalidEvent], while one that does not applies them).
 *
 * @property dailyGameCap the most [GamePlayed] may credit per kid, per [GamePlayed.playedOn] date, per currency,
 *   in that currency's minor units (pence for GBP). One value for every date: a changed cap re-decides past days too
 *   (D-M7-11). Games credit a fiat currency only
 *   ([Violation.GameCurrencyNotAllowed]). A game that would go over the cap is rejected at validation and excluded
 *   (not clamped) when projecting merged streams.
 * @property dailyGameCapOverrides per-currency caps that replace [dailyGameCap] for that currency.
 * @property maxAmount the largest absolute amount any single event may carry (and the largest interest principal).
 *   Keeps every sum a projection computes far away from `Long` overflow.
 * @property maxLamportJump how far above the highest lamport seen locally a new or pulled event may be. A forged
 *   lamport near `Long.MAX_VALUE` would otherwise sort after everything forever and exhaust every device's clock.
 * @property requireParentSignature parent-only events ([isParentOnly]) must carry an [Envelope.parentSignature]
 *   (ADR-0004). Off by default; the data layer turns it on once a parent authentication key exists. When on, an
 *   unsigned parent-only event is refused at validation and excluded when projecting
 *   ([Violation.ParentSignatureMissing]). This check is presence only; the data layer verifies the signature.
 *   [RemoteEventVerifier] reads the household's **synced** value (D-M2-1): when false, a PARENT-role parent-only
 *   event from a PARENT device needs no parent signature; when true, every pulled parent-only event needs one that
 *   verifies, whatever [parentSignatureRequiredFromLamport] says.
 * @property parentSignatureRequiredFromLamport with [requireParentSignature], only parent-only events whose lamport
 *   is at least this value need the signature; null means every one does. The data layer sets it to the lamport at
 *   which the parent key was registered, so parent events written before a key existed stay valid. Local validation
 *   only: pulled events get no from-lamport exemption ([RemoteEventVerifier]).
 */
data class LedgerPolicy(
    val dailyGameCap: Money = DEFAULT_DAILY_GAME_CAP,
    val dailyGameCapOverrides: Map<Currency, Money> = emptyMap(),
    val maxAmount: Money = DEFAULT_MAX_AMOUNT,
    val maxLamportJump: Long = DEFAULT_MAX_LAMPORT_JUMP,
    val requireParentSignature: Boolean = false,
    val parentSignatureRequiredFromLamport: Long? = null,
) {
    init {
        require(!dailyGameCap.isNegative) { "dailyGameCap must not be negative" }
        require(dailyGameCapOverrides.values.none { it.isNegative }) { "daily game caps must not be negative" }
        require(maxAmount.isPositive) { "maxAmount must be positive" }
        require(maxLamportJump > 0) { "maxLamportJump must be positive" }
        require(parentSignatureRequiredFromLamport == null || parentSignatureRequiredFromLamport >= 0) {
            "parentSignatureRequiredFromLamport must not be negative"
        }
    }

    /** The daily game cap that applies to credits in [currency]. */
    fun dailyGameCapFor(currency: Currency): Money = dailyGameCapOverrides[currency] ?: dailyGameCap

    companion object {
        /** 50 minor units (50p) a day unless the household configures otherwise. */
        val DEFAULT_DAILY_GAME_CAP: Money = Money(50)

        /** 10^12 minor units (ten billion pounds, or about 1.9 million years of minutes). */
        val DEFAULT_MAX_AMOUNT: Money = Money(1_000_000_000_000)

        /** A million local events ahead of everything seen; far beyond any honest offline gap. */
        const val DEFAULT_MAX_LAMPORT_JUMP: Long = 1_000_000
    }
}

package dev.kpetriv.sprout.feature.parenthome

import dev.kpetriv.sprout.core.domainledger.Currency
import dev.kpetriv.sprout.core.domainledger.Money
import dev.kpetriv.sprout.core.domainledger.ParentAuthorizationRequester
import dev.kpetriv.sprout.core.model.CalendarDate
import dev.kpetriv.sprout.core.model.Kid

/** Everything the parent dashboard shows. */
sealed interface ParentHomeUiState {
    data object Loading : ParentHomeUiState

    data object Error : ParentHomeUiState

    /**
     * @property pendingApprovals chore submissions waiting for a parent's decision.
     * @property pendingActions allowance and interest payments waiting for the parent's signature.
     * @property attention scheduled payments that will not be paid on their own: failed ones (they break a ledger
     *   rule, or the ledger refused them three times in a row) and allowance periods skipped by the catch-up cap.
     * @property signing progress while "Sign now" runs; null otherwise.
     * @property signResult how the last "Sign now" ended, until acknowledged.
     * @property household the trial banner, the sync line, refused events and phones waiting for approval (M3).
     * @property summaryWeek the week the summary card shows.
     * @property summary the on-device weekly summary of [summaryWeek] (M6); its own failures never fail the dashboard.
     */
    data class Ready(
        val pendingApprovals: Int,
        val pendingActions: PendingActionsUi,
        val kids: List<KidSummaryUi>,
        val attention: List<AttentionItemUi> = emptyList(),
        val signing: SigningProgress? = null,
        val signResult: SignResult? = null,
        val household: HouseholdStatus = HouseholdStatus(),
        val summaryWeek: SummaryWeek = SummaryWeek.THIS_WEEK,
        val summary: WeeklySummaryUi = WeeklySummaryUi.Loading,
    ) : ParentHomeUiState {
        /** Ledger anomalies across every kid. */
        val anomaliesTotal: Int get() = kids.sumOf { it.anomalies }

        /** The first kid with anomalies, where the anomalies card's link goes. */
        val firstKidWithAnomalies: Kid? get() = kids.firstOrNull { it.anomalies > 0 }?.kid
    }
}

/** Payments planned by the scheduler and waiting for the parent's fingerprint (ADR-0004). */
data class PendingActionsUi(val allowances: Int, val interest: Int) {
    val total: Int get() = allowances + interest
}

/** What a scheduled payment pays. */
enum class PaymentKind {
    ALLOWANCE,
    INTEREST,
}

/** Why a scheduled payment needs the parent's attention. */
enum class AttentionReason {
    /** It breaks a ledger rule or the ledger kept refusing it: it will not be signed (ADR-0005, D-M2-12). */
    FAILED,

    /** An allowance period older than the catch-up window (D-M2-4): it was never queued. */
    SKIPPED,
}

/**
 * One row of the "Payments that need attention" card. [key] identifies the queued action for the dismiss event.
 *
 * @property kidName the kid's first name; empty when the kid is gone.
 * @property period the period key, for example `2026-W45` or `2026-11`.
 */
data class AttentionItemUi(
    val key: String,
    val kidName: String,
    val kind: PaymentKind,
    val amount: MoneyUi,
    val period: String,
    val reason: AttentionReason,
)

/** An amount and the currency it is in; formatted by the screen. */
data class MoneyUi(val amount: Money, val currency: Currency)

/**
 * One kid's card.
 *
 * @property money the main money balance; null when the kid has no money account.
 * @property minutes screen-time minutes; null when the kid has no minutes account.
 * @property nextAllowance the earliest next payment date of the kid's active allowances, on or after today.
 * @property anomalies ledger anomalies about this kid that need a look.
 */
data class KidSummaryUi(
    val kid: Kid,
    val money: MoneyUi?,
    val minutes: Money?,
    val nextAllowance: CalendarDate?,
    val anomalies: Int,
)

/** "[signed] of [total] signed" while "Sign now" runs. */
data class SigningProgress(val signed: Int, val total: Int)

/** How "Sign now" ended. */
sealed interface SignResult {
    /**
     * Every queued payment was processed: [signed] paid now (of which [unsigned] without a fingerprint signature,
     * D-M2-1), [alreadyPaid] found already in the ledger, [failed] refused by the ledger this time (still queued),
     * [needsAttention] failed for good (listed on the dashboard), [cancelled] withdrawn because their kid or schedule
     * changed, of which [replanned] were queued again from the current schedule.
     */
    data class Finished(
        val signed: Int,
        val alreadyPaid: Int,
        val failed: Int,
        val total: Int,
        val unsigned: Int = 0,
        val needsAttention: Int = 0,
        val cancelled: Int = 0,
        val replanned: Int = 0,
    ) : SignResult

    /**
     * This device has no usable fingerprint and the household requires fingerprint-signed payments (D-M2-1): signing
     * stopped after [signed] of [total]; the rest stay queued.
     */
    data class SignatureRequired(val signed: Int, val total: Int) : SignResult

    /**
     * The fingerprints on this device changed, which invalidated the parent key: signing stopped after [signed] of
     * [total]; the rest stay queued until parent security is renewed in Settings.
     */
    data class KeyInvalidated(val signed: Int, val total: Int) : SignResult

    /** A prompt was cancelled or failed after [signed] of [total]; the rest stay queued for the next "Sign now". */
    data class Stopped(val signed: Int, val total: Int) : SignResult

    /** Another signing run was already going (for example from a second window); nothing was done. */
    data object AlreadyRunning : SignResult
}

/** What the dashboard can ask the [ParentHomeViewModel] to do. */
sealed interface ParentHomeEvent {
    /**
     * Sign the queued payments. [requester] shows one strong-biometric prompt with the given reason and returns its
     * authorisation (the app passes `feature/lock`'s requester); it is called once per payment, and each reason names
     * the kid, the amount and the period.
     */
    class SignNow(val requester: ParentAuthorizationRequester) : ParentHomeEvent

    data object SignResultShown : ParentHomeEvent

    /** Withdraw the failed or skipped payment [key] from the "need attention" card. */
    data class Dismiss(val key: String) : ParentHomeEvent

    /** The summary card's "This week so far" / "Last week" toggle. */
    data class SummaryWeekChanged(val week: SummaryWeek) : ParentHomeEvent
}

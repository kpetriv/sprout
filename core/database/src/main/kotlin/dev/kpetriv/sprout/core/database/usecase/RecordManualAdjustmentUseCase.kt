package dev.kpetriv.sprout.core.database.usecase

import dev.kpetriv.sprout.core.database.AccountRepository
import dev.kpetriv.sprout.core.database.HouseholdPolicyRepository
import dev.kpetriv.sprout.core.domainledger.AppendResult
import dev.kpetriv.sprout.core.domainledger.LedgerWriter
import dev.kpetriv.sprout.core.domainledger.ManualAdjustment
import dev.kpetriv.sprout.core.domainledger.Money
import dev.kpetriv.sprout.core.domainledger.ParentAuthorization
import dev.kpetriv.sprout.core.model.KidId
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/** Outcome of [RecordManualAdjustmentUseCase]. */
sealed interface ManualAdjustmentResult {
    /** The ledger's answer to the parent-signed adjustment. */
    data class Signed(val result: AppendResult) : ManualAdjustmentResult

    /**
     * D-M2-1: this device has no usable strong biometric and the household policy has parent signatures off, so the
     * adjustment was appended without a parent signature; [result] is the ledger's answer. Show the unsigned warning.
     */
    data class Unsigned(val result: AppendResult) : ManualAdjustmentResult

    /**
     * This device has no usable strong biometric but the household policy requires parent signatures: nothing was
     * written.
     */
    data object SignatureRequired : ManualAdjustmentResult

    /**
     * The adjustment breaks a ledger rule (zero amount, blank or too long reason, a debit that would overdraw, ...):
     * [result] is the ledger's rejection. Checked before prompting, so the parent was not asked and nothing was
     * written.
     */
    data class Refused(val result: AppendResult.Rejected) : ManualAdjustmentResult

    /**
     * The parent key was invalidated by a biometric enrolment change: nothing was written. The parent key must be
     * reset and registered again (settings) before money can be signed.
     */
    data object KeyInvalidated : ManualAdjustmentResult

    /** The kid has no fiat MAIN account; nothing was asked or written. */
    data object NoAccount : ManualAdjustmentResult
}

/**
 * A parent's correction or receipt-based entry on a kid's money (fiat MAIN) account: a parent-signed
 * `ManualAdjustment` ([LedgerWriter.appendAsParent]), validated before the prompt, with the D-M2-1 unsigned fallback
 * and the no-parent-key unsigned path (see [ParentEventAppender]).
 */
class RecordManualAdjustmentUseCase @Inject constructor(
    private val accounts: AccountRepository,
    private val writer: LedgerWriter,
    private val policies: HouseholdPolicyRepository,
) {
    /**
     * Credits (positive) or debits (negative) [amount] with [reason] (trimmed; it may hold personal data and never
     * reaches a log or the audit trail). [authorize] is one strong-biometric prompt, called once right before signing;
     * not at all when the kid has no fiat MAIN account, when no parent key is registered (the adjustment goes in
     * unsigned) or when the adjustment breaks a ledger rule ([ManualAdjustmentResult.Refused]). What it throws, other
     * than `ParentAuthUnavailableException` and `ParentKeyInvalidatedException`, propagates (the parent cancelled).
     */
    suspend operator fun invoke(
        kidId: KidId,
        amount: Money,
        reason: String,
        authorize: suspend () -> ParentAuthorization,
    ): ManualAdjustmentResult {
        val account = accounts.accountsFor(kidId).first().fiatMain() ?: return ManualAdjustmentResult.NoAccount
        val appended = ParentEventAppender(writer, policies).append(kidId, account.id, authorize) { envelope ->
            ManualAdjustment(envelope, account.currency, amount, reason.trim())
        }
        return when (appended) {
            ParentAppend.SignatureRequired -> ManualAdjustmentResult.SignatureRequired

            ParentAppend.KeyInvalidated -> ManualAdjustmentResult.KeyInvalidated

            is ParentAppend.Invalid -> ManualAdjustmentResult.Refused(appended.asRejection)

            is ParentAppend.Appended -> if (appended.signed) {
                ManualAdjustmentResult.Signed(appended.result)
            } else {
                ManualAdjustmentResult.Unsigned(appended.result)
            }
        }
    }
}

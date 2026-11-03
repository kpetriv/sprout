package dev.kpetriv.sprout.core.database.usecase

import dev.kpetriv.sprout.core.crypto.ParentAuthUnavailableException
import dev.kpetriv.sprout.core.crypto.ParentKeyInvalidatedException
import dev.kpetriv.sprout.core.database.HouseholdPolicyRepository
import dev.kpetriv.sprout.core.domainledger.AppendResult
import dev.kpetriv.sprout.core.domainledger.Envelope
import dev.kpetriv.sprout.core.domainledger.LedgerEvent
import dev.kpetriv.sprout.core.domainledger.LedgerWriter
import dev.kpetriv.sprout.core.domainledger.ParentAuthorization
import dev.kpetriv.sprout.core.domainledger.ValidationResult
import dev.kpetriv.sprout.core.domainledger.Violation
import dev.kpetriv.sprout.core.model.AccountId
import dev.kpetriv.sprout.core.model.KidId
import kotlinx.coroutines.CancellationException
import kotlin.reflect.KClass

/** Outcome of [ParentEventAppender.append]. */
internal sealed interface ParentAppend {
    /**
     * The ledger answered [result]; [signed] is true only when the event went in with a parent signature that verified
     * against the registered parent key, false when it went in unsigned (no key registered, or D-M2-1).
     */
    data class Appended(val result: AppendResult, val signed: Boolean) : ParentAppend

    /**
     * This device cannot make a parent signature and the household policy requires one, so nothing was written and
     * no authorisation was spent.
     */
    data object SignatureRequired : ParentAppend

    /**
     * The event breaks [violations] ([LedgerWriter.validateAsParent]): the parent was not prompted and nothing was
     * written.
     */
    data class Invalid(val violations: List<Violation>) : ParentAppend {
        /** The same refusal as the ledger would give, for results that carry an [AppendResult]. */
        val asRejection: AppendResult.Rejected get() = AppendResult.Rejected(
            ParentEventAppender.INVALID_BEFORE_PROMPT,
            violations,
        )
    }

    /**
     * The parent key was invalidated by a biometric enrolment change (`ParentKeyInvalidatedException`, from the prompt
     * or from signing). Nothing was written; the key must be reset and registered again before anything is signed.
     */
    data object KeyInvalidated : ParentAppend
}

/**
 * Appends parent-only events: validates first, prompts once, signs, and falls back per the household policy.
 *
 * 1. **No parent key registered** (`household_policy` has none, for example D-M2-1: no strong biometric): nothing could
 *    verify a parent signature, so the parent is not prompted and the event is appended unsigned through
 *    [LedgerWriter.append] and reported as such ([ParentAppend.Appended.signed] false) so the UI can warn (audit M1).
 * 2. Otherwise the event is checked with [LedgerWriter.validateAsParent] **before** any prompt (R3): an event the
 *    ledger would refuse is [ParentAppend.Invalid] and the parent never sees a prompt for it.
 * 3. Each valid event is parent-signed with a fresh authorisation from the caller's provider (one strong-biometric
 *    prompt). A `ParentKeyInvalidatedException` (from the provider or from `sign`) is [ParentAppend.KeyInvalidated];
 *    a `ParentAuthUnavailableException` means the device cannot sign: when `requireParentSignature` is set the event
 *    is not written ([ParentAppend.SignatureRequired]), otherwise it is appended unsigned. Both are recognised anywhere
 *    in the cause chain. Anything else the provider throws (the parent cancelled) propagates.
 *
 * One instance per use-case call: once the device proved unable to sign, later events of the same batch skip the
 * provider instead of asking again.
 */
internal class ParentEventAppender(private val writer: LedgerWriter, private val policies: HouseholdPolicyRepository) {
    private var unavailable = false

    /**
     * Appends one event built by [body] for [kidId] on [accountId]. [authorize] is called at most once, right before
     * signing, and only for a valid event while a parent key is registered.
     */
    @Suppress("ReturnCount") // One early return per ParentAppend outcome.
    suspend fun append(
        kidId: KidId,
        accountId: AccountId,
        authorize: suspend () -> ParentAuthorization,
        body: (Envelope) -> LedgerEvent,
    ): ParentAppend {
        val policy = policies.get()
        if (policy?.hasParentKey != true) return unsigned(kidId, accountId, body)
        val validation = writer.validateAsParent(kidId, accountId, body)
        if (validation is ValidationResult.Invalid) return ParentAppend.Invalid(validation.violations)
        if (!unavailable) {
            when (val signed = trySigned(kidId, accountId, authorize, body)) {
                is Signing.Done -> return ParentAppend.Appended(signed.result, signed = true)
                Signing.KeyInvalidated -> return ParentAppend.KeyInvalidated
                Signing.Unavailable -> unavailable = true
            }
        }
        return if (policy.requireParentSignature) ParentAppend.SignatureRequired else unsigned(kidId, accountId, body)
    }

    private suspend fun unsigned(kidId: KidId, accountId: AccountId, body: (Envelope) -> LedgerEvent) =
        ParentAppend.Appended(writer.append(kidId, accountId, body), signed = false)

    @Suppress("TooGenericExceptionCaught") // Recognises the two crypto failures anywhere in the cause chain.
    private suspend fun trySigned(
        kidId: KidId,
        accountId: AccountId,
        authorize: suspend () -> ParentAuthorization,
        body: (Envelope) -> LedgerEvent,
    ): Signing = try {
        Signing.Done(writer.appendAsParent(kidId, accountId, authorize(), body))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        when {
            e.hasCause(ParentKeyInvalidatedException::class) -> Signing.KeyInvalidated
            e.hasCause(ParentAuthUnavailableException::class) -> Signing.Unavailable
            else -> throw e
        }
    }

    private sealed interface Signing {
        data class Done(val result: AppendResult) : Signing

        data object Unavailable : Signing

        data object KeyInvalidated : Signing
    }

    companion object {
        /**
         * [AppendResult.Rejected.reason] for a parent-only event refused by [LedgerWriter.validateAsParent] before the
         * parent was prompted.
         */
        const val INVALID_BEFORE_PROMPT: String = "invariant violated before signing"
    }
}

/** True when this throwable or one of its causes (at most [MAX_CAUSE_DEPTH] deep) is a [type]. */
internal fun Throwable.hasCause(type: KClass<out Throwable>): Boolean =
    generateSequence(this) { it.cause }.take(MAX_CAUSE_DEPTH).any { type.isInstance(it) }

private const val MAX_CAUSE_DEPTH = 8

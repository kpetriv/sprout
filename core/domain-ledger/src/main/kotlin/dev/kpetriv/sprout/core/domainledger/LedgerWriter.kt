package dev.kpetriv.sprout.core.domainledger

import dev.kpetriv.sprout.core.model.AccountId
import dev.kpetriv.sprout.core.model.KidId

/**
 * Application-level entry point for authoring a new ledger event on this device.
 *
 * Implementations (data layer) own the current [EventAuthor], the device's lamport clock, the
 * author stream's head hash and the device signing key. They build the envelope through
 * [LedgerEventFactory], hash and sign the event, validate it with [LedgerRules] and persist it
 * through [LedgerRepository] together with its outbox entry, all in one transaction.
 *
 * Feature modules call this instead of touching the factory, signer or repository directly.
 */
interface LedgerWriter {
    /**
     * Creates, signs and appends an event for [kidId] on [accountId]. [body] receives the prepared
     * envelope (unsigned, hash not yet set) and returns the concrete event.
     *
     * For kid-authored and other events that need no parent signature. The event carries no
     * [Envelope.parentSignature], so when the policy has [LedgerPolicy.requireParentSignature] set, a parent-only
     * event ([isParentOnly]) appended this way is refused at validation: [AppendResult.Rejected] with
     * [Violation.ParentSignatureMissing]. Use [appendAsParent] for those.
     */
    suspend fun append(kidId: KidId, accountId: AccountId, body: (Envelope) -> LedgerEvent): AppendResult

    /**
     * Like [append], for parent-only events (ADR-0004): after building and hashing the event, signs its
     * [LedgerEvent.parentSignedPayload] with [authorization] (exactly one [ParentAuthorization.sign] call) and
     * attaches it as [Envelope.parentSignature] ([parentSignWith]), then device-signs and appends it. Both
     * signatures cover the same canonical bytes under different domains, so their order does not matter.
     *
     * The feature layer obtains [authorization] from a strong-biometric `BiometricPrompt` immediately before the
     * call, once per event. Any event may be parent-signed; the signature is only required on parent-only ones. If
     * the event is then rejected, the authorisation is spent and the caller must prompt again to retry.
     */
    suspend fun appendAsParent(
        kidId: KidId,
        accountId: AccountId,
        authorization: ParentAuthorization,
        body: (Envelope) -> LedgerEvent,
    ): AppendResult

    /**
     * Like [append], but authored by [kidId] itself (role KID, the kid's own user id): for what a kid does in kid
     * mode, such as buying a reward. Kid-authored events can never be parent-only, so this cannot raise privileges.
     */
    suspend fun appendAsKid(kidId: KidId, accountId: AccountId, body: (Envelope) -> LedgerEvent): AppendResult

    /**
     * Appends [entries] as consecutive kid-authored events of [kidId] (see [appendAsKid]), **all or nothing**: they
     * are built, chained and signed together under the writer's lock and stored in one transaction. If any entry is
     * refused (or anything fails while storing) nothing is stored, the device clock does not move, and the result
     * names the refused entry. Used for a screen-time purchase and the time credit it grants (R1).
     *
     * Each [BatchEntry.body] gets its envelope and the events built before it in this batch, so a later event can
     * reference an earlier one (a `TimeBankCredited.grantedBy` naming the `RewardPurchased`).
     */
    suspend fun appendAllAsKid(kidId: KidId, entries: List<BatchEntry>): BatchAppendResult

    /**
     * Checks, without signing, prompting or storing anything, whether the parent-only event [body] builds would pass
     * [LedgerRules.validate] against the current ledger if it were parent-signed and appended now. Callers run this
     * **before** asking for a parent authorisation, so the parent is never prompted to sign an event the ledger would
     * refuse (an unknown account, a period already paid, insufficient funds for a debit, ...). The envelope is
     * provisional: the event actually appended later gets a fresh id, lamport and timestamp.
     */
    suspend fun validateAsParent(kidId: KidId, accountId: AccountId, body: (Envelope) -> LedgerEvent): ValidationResult
}

/**
 * One event of [LedgerWriter.appendAllAsKid], posting to [accountId]. [body] receives the prepared envelope and the
 * events built before this one in the same batch, in order, and returns the concrete event (using the envelope
 * unchanged).
 */
class BatchEntry(val accountId: AccountId, val body: (Envelope, List<LedgerEvent>) -> LedgerEvent)

/** Outcome of [LedgerWriter.appendAllAsKid]. */
sealed interface BatchAppendResult {
    /** Every entry was stored, in order; [events] are the stored events. */
    data class Accepted(val events: List<LedgerEvent>) : BatchAppendResult

    /**
     * Nothing was stored: entry [index] was refused with [result] (never [AppendResult.Accepted]), and every entry
     * before it was rolled back.
     */
    data class Rejected(val index: Int, val result: AppendResult) : BatchAppendResult
}

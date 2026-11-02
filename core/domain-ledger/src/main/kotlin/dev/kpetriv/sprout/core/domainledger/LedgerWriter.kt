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
     */
    suspend fun append(kidId: KidId, accountId: AccountId, body: (Envelope) -> LedgerEvent): AppendResult
}

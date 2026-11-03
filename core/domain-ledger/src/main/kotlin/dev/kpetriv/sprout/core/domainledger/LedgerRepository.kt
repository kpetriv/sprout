package dev.kpetriv.sprout.core.domainledger

import dev.kpetriv.sprout.core.model.DeviceId
import dev.kpetriv.sprout.core.model.KidId
import kotlinx.coroutines.flow.Flow

/**
 * Domain-level access to the local ledger. Implemented by `core/database` (Room, with the outbox written in the
 * same transaction as the event). Reads are [Flow]s that re-emit on every change.
 */
interface LedgerRepository {
    /** Projected balances of every account of [kidId]. */
    fun observeBalances(kidId: KidId): Flow<List<Balance>>

    /**
     * The anomalies of [kidId]'s projection ([anomaliesFor]): overdrafts, excluded games, invalid
     * events and conflicting duplicates, for the parent audit view.
     */
    fun observeAnomalies(kidId: KidId): Flow<List<LedgerAnomaly>>

    /** The latest [limit] events for [kidId], newest first by [LedgerOrdering]. */
    fun observeEvents(kidId: KidId, limit: Int): Flow<List<LedgerEvent>>

    /**
     * Validates, verifies and stores [event]. Idempotent by content: the same event again (even re-signed) is
     * [AppendResult.Duplicate]. A different event under a stored eventId is [AppendResult.Conflict]: once it passes
     * chain and signature verification it is kept next to the stored copy, and projections report
     * [LedgerAnomaly.ConflictingDuplicate] and apply only [appliedCopyOf] (the copy from a registered PARENT device
     * when every other copy comes from a KID device, otherwise none) on every device, whatever order the copies
     * arrive in.
     */
    suspend fun append(event: LedgerEvent): AppendResult

    /** The highest lamport seen locally (own and pulled events); feeds [LamportClock.observe]. */
    suspend fun latestLamport(): Long

    /** The hash of the newest event authored by [authorDeviceId], or null if it has authored none. */
    suspend fun headHash(authorDeviceId: DeviceId): String?
}

/** Outcome of [LedgerRepository.append]. */
sealed interface AppendResult {
    /** Stored and queued for sync. */
    data object Accepted : AppendResult

    /** This same event (same eventId, hash and content) is already stored; nothing changed. */
    data object Duplicate : AppendResult

    /**
     * A different event with the same [eventId] is already stored ([storedHash]); the incoming one has
     * [incomingHash]. Both copies are kept; at most one of them reaches the balances ([appliedCopyOf], reported in
     * [LedgerAnomaly.ConflictingDuplicate]).
     */
    data class Conflict(val eventId: EventId, val storedHash: String, val incomingHash: String) : AppendResult

    /** Not stored. [violations] lists broken invariants when [LedgerRules] rejected it. */
    data class Rejected(val reason: String, val violations: List<Violation> = emptyList()) : AppendResult
}

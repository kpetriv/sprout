package dev.kpetriv.sprout.core.database

import androidx.room.withTransaction
import dev.kpetriv.sprout.core.crypto.DeviceSigner
import dev.kpetriv.sprout.core.domainledger.AppendResult
import dev.kpetriv.sprout.core.domainledger.BatchAppendResult
import dev.kpetriv.sprout.core.domainledger.BatchEntry
import dev.kpetriv.sprout.core.domainledger.Envelope
import dev.kpetriv.sprout.core.domainledger.EventAuthor
import dev.kpetriv.sprout.core.domainledger.EventIdGenerator
import dev.kpetriv.sprout.core.domainledger.LamportSource
import dev.kpetriv.sprout.core.domainledger.LedgerEvent
import dev.kpetriv.sprout.core.domainledger.LedgerEventFactory
import dev.kpetriv.sprout.core.domainledger.LedgerRules
import dev.kpetriv.sprout.core.domainledger.LedgerWriter
import dev.kpetriv.sprout.core.domainledger.ParentAuthorization
import dev.kpetriv.sprout.core.domainledger.UuidV4EventIdGenerator
import dev.kpetriv.sprout.core.domainledger.ValidationResult
import dev.kpetriv.sprout.core.domainledger.parentSignWith
import dev.kpetriv.sprout.core.domainledger.signedPayload
import dev.kpetriv.sprout.core.domainledger.withParentSignature
import dev.kpetriv.sprout.core.domainledger.withSignature
import dev.kpetriv.sprout.core.model.AccountId
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.time.Clock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Authors events on this device: builds the envelope with [LedgerEventFactory], parent-signs it when asked
 * ([appendAsParent]), signs it with the device key and appends it through [RoomLedgerRepository].
 *
 * The Lamport clock is the [DeviceClock] in `device_state`, shared with document writes. The next value is
 * `max(stored, highest stored event) + 1`, so it survives restarts and always sorts after every event this device
 * has seen. It is written in the same transaction as the event, only when the event is stored (accepted, or kept as a
 * conflicting copy), and never moves backwards.
 *
 * Signing (device and parent) and binding the repository's local-event check are blocking or suspend Keystore calls,
 * so they happen between two transactions (read the chain head and clock, then write) rather than inside one. The
 * process-wide [DeviceClock] lock is held across both, so neither another event nor a document write can take the
 * same lamport in between; even if something did, the second transaction re-checks the chain and rejects a stale head.
 *
 * [appendAllAsKid] stores several events in **one** transaction: a refusal of any of them (or any failure while
 * storing) rolls every one back.
 */
@Singleton
class RoomLedgerWriter internal constructor(
    private val database: SproutDatabase,
    private val repository: RoomLedgerRepository,
    private val signer: DeviceSigner,
    private val authors: SessionAuthorProvider,
    private val clock: Clock,
    private val ids: EventIdGenerator,
) : LedgerWriter {
    @Inject
    constructor(
        database: SproutDatabase,
        repository: RoomLedgerRepository,
        signer: DeviceSigner,
        authors: SessionAuthorProvider,
        clock: Clock,
    ) : this(database, repository, signer, authors, clock, UuidV4EventIdGenerator())

    private val deviceClock = DeviceClock.of(database)

    override suspend fun append(kidId: KidId, accountId: AccountId, body: (Envelope) -> LedgerEvent): AppendResult =
        write(authors.currentAuthor(), kidId, accountId, parent = null, body)

    override suspend fun appendAsParent(
        kidId: KidId,
        accountId: AccountId,
        authorization: ParentAuthorization,
        body: (Envelope) -> LedgerEvent,
    ): AppendResult = write(authors.currentAuthor(), kidId, accountId, authorization, body)

    /**
     * Authored by [kidId] itself ([SessionAuthorProvider.kidAuthor]). A kid-authored `RewardPurchased` must match the
     * reward catalogue (see [RoomLedgerRepository]).
     */
    override suspend fun appendAsKid(
        kidId: KidId,
        accountId: AccountId,
        body: (Envelope) -> LedgerEvent,
    ): AppendResult = write(authors.kidAuthor(kidId), kidId, accountId, parent = null, body)

    override suspend fun appendAllAsKid(kidId: KidId, entries: List<BatchEntry>): BatchAppendResult {
        require(entries.isNotEmpty()) { "a batch needs at least one entry" }
        val author = authors.kidAuthor(kidId)
        return deviceClock.withLock {
            val (first, head) = database.withTransaction {
                deviceClock.peekNext() to repository.headHash(author.deviceId)
            }
            val built = ArrayList<LedgerEvent>(entries.size)
            entries.forEachIndexed { index, entry ->
                val lamport = Math.addExact(first, index.toLong())
                val factory = LedgerEventFactory(clock, LamportSource { lamport }, ids)
                val previous = built.toList()
                val prevHash = previous.lastOrNull()?.envelope?.hash ?: head
                val unsigned = factory.create(author, kidId, entry.accountId, prevHash) { entry.body(it, previous) }
                built += unsigned.withSignature(signer.sign(unsigned.signedPayload()))
            }
            val check = repository.bindLocalCheck()
            try {
                database.withTransaction {
                    built.forEachIndexed { index, event ->
                        val result = repository.appendLocked(event, check)
                        if (result != AppendResult.Accepted) throw BatchRollback(index, result)
                    }
                    database.advanceDeviceLamport(author.deviceId, built.last().lamport)
                }
                BatchAppendResult.Accepted(built.toList())
            } catch (rollback: BatchRollback) {
                BatchAppendResult.Rejected(rollback.index, rollback.result)
            }
        }
    }

    override suspend fun validateAsParent(
        kidId: KidId,
        accountId: AccountId,
        body: (Envelope) -> LedgerEvent,
    ): ValidationResult {
        val author = authors.currentAuthor()
        val projection = repository.projection()
        val lamport = database.withTransaction { deviceClock.peekNext() }
        // A provisional envelope (fresh id, the next lamport) with a placeholder parent signature: the signature is
        // verified when the real event is appended, here only its presence matters.
        val factory = LedgerEventFactory(clock, LamportSource { lamport }, UuidV4EventIdGenerator())
        val provisional = factory.create(author, kidId, accountId, repository.headHash(author.deviceId), body)
            .withParentSignature(PROVISIONAL_PARENT_SIGNATURE)
        return LedgerRules.validate(provisional, projection)
    }

    private suspend fun write(
        author: EventAuthor,
        kidId: KidId,
        accountId: AccountId,
        parent: ParentAuthorization?,
        body: (Envelope) -> LedgerEvent,
    ): AppendResult = deviceClock.withLock {
        val (lamport, head) = database.withTransaction {
            deviceClock.peekNext() to repository.headHash(author.deviceId)
        }
        val factory = LedgerEventFactory(clock, LamportSource { lamport }, ids)
        val unsigned = factory.create(author, kidId, accountId, head, body)
        // Exactly one ParentAuthorization.sign call: a per-use authorisation signs once.
        val parentSigned = parent?.let(unsigned::parentSignWith) ?: unsigned
        val signed = parentSigned.withSignature(signer.sign(parentSigned.signedPayload()))
        val check = repository.bindLocalCheck()

        database.withTransaction {
            repository.appendLocked(signed, check).also { result ->
                // A conflicting copy is stored too, so its lamport is used up either way.
                if (result == AppendResult.Accepted || result is AppendResult.Conflict) {
                    database.advanceDeviceLamport(author.deviceId, lamport)
                }
            }
        }
    }

    /** Thrown inside the batch transaction to roll it back when entry [index] is refused with [result]. */
    private class BatchRollback(val index: Int, val result: AppendResult) :
        RuntimeException("batch entry $index refused")

    private companion object {
        /** Stands in for the parent signature while [validateAsParent] checks an event that is not signed yet. */
        val PROVISIONAL_PARENT_SIGNATURE = byteArrayOf(0)
    }
}

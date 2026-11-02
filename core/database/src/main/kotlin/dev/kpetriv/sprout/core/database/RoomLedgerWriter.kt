package dev.kpetriv.sprout.core.database

import androidx.room.withTransaction
import dev.kpetriv.sprout.core.crypto.DeviceSigner
import dev.kpetriv.sprout.core.database.entity.DeviceStateEntity
import dev.kpetriv.sprout.core.domainledger.AppendResult
import dev.kpetriv.sprout.core.domainledger.Envelope
import dev.kpetriv.sprout.core.domainledger.EventIdGenerator
import dev.kpetriv.sprout.core.domainledger.LamportClock
import dev.kpetriv.sprout.core.domainledger.LamportSource
import dev.kpetriv.sprout.core.domainledger.LedgerEvent
import dev.kpetriv.sprout.core.domainledger.LedgerEventFactory
import dev.kpetriv.sprout.core.domainledger.LedgerWriter
import dev.kpetriv.sprout.core.domainledger.UuidV4EventIdGenerator
import dev.kpetriv.sprout.core.domainledger.signedPayload
import dev.kpetriv.sprout.core.domainledger.withSignature
import dev.kpetriv.sprout.core.model.AccountId
import dev.kpetriv.sprout.core.model.DeviceId
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.time.Clock
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Authors events on this device: builds the envelope with [LedgerEventFactory], signs it with the device key and
 * appends it through [RoomLedgerRepository].
 *
 * The Lamport clock lives in `device_state`. The next value is `max(stored, highest stored event) + 1`, so it
 * survives restarts and always sorts after every event this device has seen. It is written in the same
 * transaction as the event, and only when the event is stored (accepted, or kept as a conflicting copy).
 *
 * Signing and binding the repository's local-event check are suspend Keystore calls, so they happen between two
 * transactions (read the chain head and clock, then write) rather than inside one. A process-wide [Mutex]
 * serialises local writes so nothing can slip in between; even if something did, the second transaction re-checks
 * the chain and rejects a stale head.
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

    private val mutex = Mutex()
    private val deviceState = database.deviceStateDao()
    private val events = database.ledgerEventDao()

    override suspend fun append(kidId: KidId, accountId: AccountId, body: (Envelope) -> LedgerEvent): AppendResult =
        mutex.withLock {
            val author = authors.currentAuthor()
            val (lamport, head) = database.withTransaction {
                nextLamport() to repository.headHash(author.deviceId)
            }
            val factory = LedgerEventFactory(clock, LamportSource { lamport }, ids)
            val unsigned = factory.create(author, kidId, accountId, head, body)
            val signed = unsigned.withSignature(signer.sign(unsigned.signedPayload()))
            val check = repository.bindLocalCheck()

            database.withTransaction {
                repository.appendLocked(signed, check).also { result ->
                    // A conflicting copy is stored too, so its lamport is used up either way.
                    if (result == AppendResult.Accepted || result is AppendResult.Conflict) {
                        persistLamport(author.deviceId, lamport)
                    }
                }
            }
        }

    /** The value the next local event gets; not persisted until that event is stored. */
    private suspend fun nextLamport(): Long {
        val clock = LamportClock(deviceState.get()?.lamport ?: 0)
        clock.observe(events.maxLamport())
        return clock.next()
    }

    private suspend fun persistLamport(deviceId: DeviceId, lamport: Long) =
        deviceState.upsert(DeviceStateEntity(deviceId = deviceId, lamport = lamport))
}

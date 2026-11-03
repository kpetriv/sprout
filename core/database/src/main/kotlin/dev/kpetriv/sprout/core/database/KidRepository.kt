package dev.kpetriv.sprout.core.database

import dev.kpetriv.sprout.core.crypto.DeviceSigner
import dev.kpetriv.sprout.core.database.entity.AuditAction
import dev.kpetriv.sprout.core.database.entity.KidEntity
import dev.kpetriv.sprout.core.domainledger.EventAuthor
import dev.kpetriv.sprout.core.model.Kid
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.PhotoRef
import dev.kpetriv.sprout.core.sync.DocTypes
import dev.kpetriv.sprout.core.time.Clock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.put
import javax.inject.Inject

/** Kid profiles on this device. */
interface KidRepository {
    /** Active (not archived) kids, youngest last. */
    fun observeKids(): Flow<List<Kid>>

    suspend fun getKid(id: KidId): Kid?

    /** Inserts or replaces [kid]. */
    suspend fun upsert(kid: Kid)

    /**
     * Hides [id] from day-to-day screens; its ledger history stays, its chore photos are deleted (audit L2). Returns
     * false if there is no such kid.
     */
    suspend fun archive(id: KidId): Boolean
}

/**
 * [KidRepository] on Room. Kid profiles are synced documents ([DocTypes.KID]): every write takes a lamport, queues
 * the signed document and is audited (ids only, never the name) in the same transaction.
 */
class RoomKidRepository @Inject constructor(
    private val database: SproutDatabase,
    private val authors: SessionAuthorProvider,
    private val clock: Clock,
    private val photoStore: PhotoStore,
    signer: DeviceSigner,
) : KidRepository {
    private val kids = database.kidDao()
    private val writes = DocumentWrites(database, clock, signer)

    override fun observeKids(): Flow<List<Kid>> = kids.observeActive().map { rows -> rows.map(KidEntity::toDomain) }

    override suspend fun getKid(id: KidId): Kid? = kids.get(id)?.takeUnless { it.stamp.deleted }?.toDomain()

    override suspend fun upsert(kid: Kid) {
        val author = authors.currentAuthor()
        writes.transaction { write(author, kid, AuditAction.KID_UPSERTED) }
    }

    override suspend fun archive(id: KidId): Boolean {
        val author = authors.currentAuthor()
        val found = writes.transaction {
            val kid = kids.get(id)?.takeUnless { it.stamp.deleted }?.toDomain()
            if (kid != null && !kid.archived) write(author, kid.copy(archived = true), AuditAction.KID_ARCHIVED)
            kid != null
        }
        if (found) {
            database.photoDao().forKid(id).forEach { photoStore.delete(PhotoRef(it.fileName, it.sha256)) }
        }
        return found
    }

    private suspend fun write(author: EventAuthor, kid: Kid, action: AuditAction) {
        val lamport = writes.nextLamport(author.deviceId)
        val row = KidEntity.from(kid)
        val stamp = writes.record(
            author,
            DocTypes.KID,
            kid.id.value,
            lamport,
            DocumentPayloads.encode(Kid.serializer(), row.toDomain()),
            action,
        ) {
            put("uiTier", kid.uiTier.name)
            put("archived", kid.archived)
        }
        kids.upsert(row.copy(lamport = lamport, stamp = stamp))
    }
}

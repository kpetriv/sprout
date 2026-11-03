package dev.kpetriv.sprout.core.database

import androidx.room.withTransaction
import dev.kpetriv.sprout.core.database.entity.AuditAction
import dev.kpetriv.sprout.core.database.entity.KidEntity
import dev.kpetriv.sprout.core.model.Kid
import dev.kpetriv.sprout.core.model.KidId
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

    /** Hides [id] from day-to-day screens; its ledger history stays. Returns false if there is no such kid. */
    suspend fun archive(id: KidId): Boolean
}

/**
 * [KidRepository] on Room. Every write is audited (ids only, never the name) in the same transaction.
 * Kid profiles are synced documents (PLAN "Domain model"); their outbox entries arrive with sync in M3.
 */
class RoomKidRepository @Inject constructor(
    private val database: SproutDatabase,
    private val authors: SessionAuthorProvider,
    private val clock: Clock,
) : KidRepository {
    private val kids = database.kidDao()
    private val audit = database.auditLogDao()

    override fun observeKids(): Flow<List<Kid>> = kids.observeActive().map { rows -> rows.map(KidEntity::toDomain) }

    override suspend fun getKid(id: KidId): Kid? = kids.get(id)?.toDomain()

    override suspend fun upsert(kid: Kid) {
        val author = authors.currentAuthor()
        database.withTransaction {
            kids.upsert(KidEntity.from(kid))
            audit.insert(
                auditEntry(author, clock.now().toEpochMilliseconds(), AuditAction.KID_UPSERTED, kid.id.value) {
                    put("uiTier", kid.uiTier.name)
                    put("archived", kid.archived)
                },
            )
        }
    }

    override suspend fun archive(id: KidId): Boolean {
        val author = authors.currentAuthor()
        return database.withTransaction {
            val changed = kids.archive(id) > 0
            if (changed) {
                audit.insert(auditEntry(author, clock.now().toEpochMilliseconds(), AuditAction.KID_ARCHIVED, id.value))
            }
            changed
        }
    }
}

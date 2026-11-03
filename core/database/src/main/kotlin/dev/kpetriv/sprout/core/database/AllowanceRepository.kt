package dev.kpetriv.sprout.core.database

import dev.kpetriv.sprout.core.crypto.DeviceSigner
import dev.kpetriv.sprout.core.database.entity.AllowanceScheduleEntity
import dev.kpetriv.sprout.core.database.entity.AuditAction
import dev.kpetriv.sprout.core.domainhousehold.AllowanceSchedule
import dev.kpetriv.sprout.core.model.CalendarDate
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.ScheduleId
import dev.kpetriv.sprout.core.sync.DocTypes
import dev.kpetriv.sprout.core.time.Clock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.put
import javax.inject.Inject

/** Kids' allowance schedules, and the planner's per-schedule bookkeeping. */
interface AllowanceRepository {
    /** Schedules of [kidId] (every kid when null), oldest start first. */
    fun observeSchedules(kidId: KidId? = null): Flow<List<AllowanceSchedule>>

    suspend fun get(id: ScheduleId): AllowanceSchedule?

    /** Inserts or replaces [schedule]. Keeps the schedule's [lastPaidOn] bookkeeping. */
    suspend fun upsert(schedule: AllowanceSchedule)

    /**
     * The due date of the latest period of [id] already queued for payment, or null if none (see
     * `AllowancePlanner.due`'s `lastPaidOn`).
     */
    suspend fun lastPaidOn(id: ScheduleId): CalendarDate?

    /** Records that every period of [id] due up to [dueOn] is queued. Device-local; never moves backwards. */
    suspend fun recordPaidThrough(id: ScheduleId, dueOn: CalendarDate)
}

/** [AllowanceRepository] on Room: each schedule write, its outbox `DOCUMENT` row and its audit row together. */
class RoomAllowanceRepository @Inject constructor(
    database: SproutDatabase,
    private val authors: SessionAuthorProvider,
    clock: Clock,
    signer: DeviceSigner,
) : AllowanceRepository {
    private val schedules = database.allowanceScheduleDao()
    private val writes = DocumentWrites(database, clock, signer)

    override fun observeSchedules(kidId: KidId?): Flow<List<AllowanceSchedule>> =
        schedules.observe(kidId).map { rows -> rows.map(AllowanceScheduleEntity::toDomain) }

    override suspend fun get(id: ScheduleId): AllowanceSchedule? =
        schedules.get(id)?.takeUnless { it.stamp.deleted }?.toDomain()

    override suspend fun upsert(schedule: AllowanceSchedule) {
        val author = authors.currentAuthor()
        writes.transaction {
            val lamport = writes.nextLamport(author.deviceId)
            val lastPaidOn = schedules.get(schedule.id)?.lastPaidOn
            val row = AllowanceScheduleEntity.from(schedule, lastPaidOn, lamport)
            val stamp = writes.record(
                author,
                DocTypes.ALLOWANCE_SCHEDULE,
                schedule.id.value,
                lamport,
                DocumentPayloads.encode(AllowanceSchedule.serializer(), row.toDomain()),
                AuditAction.ALLOWANCE_UPSERTED,
            ) {
                put("kidId", schedule.kidId.value)
                put("accountId", schedule.accountId.value)
                put("amountMinor", schedule.amount.minorUnits)
                put("currency", Converters.currencyToString(schedule.currency))
                put("active", schedule.active)
            }
            schedules.upsert(row.copy(stamp = stamp))
        }
    }

    override suspend fun lastPaidOn(id: ScheduleId): CalendarDate? = schedules.get(id)?.lastPaidOn

    override suspend fun recordPaidThrough(id: ScheduleId, dueOn: CalendarDate) {
        writes.transaction {
            val current = schedules.get(id)?.lastPaidOn
            if (current == null || dueOn > current) schedules.setLastPaidOn(id, dueOn)
        }
    }
}

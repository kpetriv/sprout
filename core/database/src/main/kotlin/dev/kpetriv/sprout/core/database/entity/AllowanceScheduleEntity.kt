package dev.kpetriv.sprout.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import dev.kpetriv.sprout.core.domainhousehold.AllowanceSchedule
import dev.kpetriv.sprout.core.domainledger.Currency
import dev.kpetriv.sprout.core.domainledger.Money
import dev.kpetriv.sprout.core.model.AccountId
import dev.kpetriv.sprout.core.model.AllowanceCadence
import dev.kpetriv.sprout.core.model.CalendarDate
import dev.kpetriv.sprout.core.model.DayOfWeek
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.ScheduleId

/** How [AllowanceScheduleEntity.cadenceDay] is read. */
enum class CadenceKind {
    /** `cadenceDay` is the ISO day of week, 1 (Monday) to 7. */
    WEEKLY,

    /** `cadenceDay` is the day of month, 1 to 28. */
    MONTHLY,
}

/**
 * A kid's allowance ([AllowanceSchedule]). A synced document, except [lastPaidOn].
 *
 * @property lastPaidOn device-local planning bookkeeping (never synced): the due date of the latest period already
 *   queued for payment, which `AllowancePlanner` plans after. Advanced when a period is queued in
 *   `pending_parent_actions`, not when it is signed; the ledger's period claim keeps the payment itself idempotent.
 */
@Entity(tableName = "allowance_schedules", indices = [Index(value = ["kidId"])])
data class AllowanceScheduleEntity(
    @PrimaryKey val scheduleId: ScheduleId,
    val kidId: KidId,
    val accountId: AccountId,
    val amount: Money,
    val currency: Currency,
    val cadence: CadenceKind,
    val cadenceDay: Int,
    val startsOn: CalendarDate,
    val active: Boolean,
    val lastPaidOn: CalendarDate?,
    val lamport: Long,
) {
    fun toDomain(): AllowanceSchedule = AllowanceSchedule(
        id = scheduleId,
        kidId = kidId,
        accountId = accountId,
        amount = amount,
        currency = currency,
        cadence = when (cadence) {
            CadenceKind.WEEKLY -> AllowanceCadence.Weekly(DayOfWeek.ofIso(cadenceDay))
            CadenceKind.MONTHLY -> AllowanceCadence.Monthly(cadenceDay)
        },
        startsOn = startsOn,
        active = active,
    )

    companion object {
        fun from(schedule: AllowanceSchedule, lastPaidOn: CalendarDate?, lamport: Long) = AllowanceScheduleEntity(
            scheduleId = schedule.id,
            kidId = schedule.kidId,
            accountId = schedule.accountId,
            amount = schedule.amount,
            currency = schedule.currency,
            cadence = when (schedule.cadence) {
                is AllowanceCadence.Weekly -> CadenceKind.WEEKLY
                is AllowanceCadence.Monthly -> CadenceKind.MONTHLY
            },
            cadenceDay = when (val cadence = schedule.cadence) {
                is AllowanceCadence.Weekly -> cadence.dayOfWeek.isoNumber
                is AllowanceCadence.Monthly -> cadence.dayOfMonth
            },
            startsOn = schedule.startsOn,
            active = schedule.active,
            lastPaidOn = lastPaidOn,
            lamport = lamport,
        )
    }
}

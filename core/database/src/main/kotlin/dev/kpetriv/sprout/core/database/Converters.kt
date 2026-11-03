package dev.kpetriv.sprout.core.database

import androidx.room.TypeConverter
import dev.kpetriv.sprout.core.domainledger.Currency
import dev.kpetriv.sprout.core.domainledger.CurrencySerializer
import dev.kpetriv.sprout.core.domainledger.EventId
import dev.kpetriv.sprout.core.domainledger.Money
import dev.kpetriv.sprout.core.model.AccountId
import dev.kpetriv.sprout.core.model.CalendarDate
import dev.kpetriv.sprout.core.model.ChoreId
import dev.kpetriv.sprout.core.model.DeviceId
import dev.kpetriv.sprout.core.model.GoalId
import dev.kpetriv.sprout.core.model.HouseholdId
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.RewardId
import dev.kpetriv.sprout.core.model.ScheduleId
import dev.kpetriv.sprout.core.model.SubmissionId
import dev.kpetriv.sprout.core.model.UserId
import dev.kpetriv.sprout.core.model.YearMonth

/**
 * Room converters for the domain's value classes. Ids are stored as TEXT, [Money] as INTEGER minor units,
 * [Currency] in its canonical string form (`"GBP"` or `"minutes"`), [YearMonth] as `YYYY-MM` and [CalendarDate] as
 * `YYYY-MM-DD` (so text order is date order). No column is ever floating point.
 */
@Suppress("TooManyFunctions") // One to/from pair per stored domain type.
internal object Converters {
    @TypeConverter fun eventIdToString(value: EventId): String = value.value

    @TypeConverter fun stringToEventId(value: String): EventId = EventId(value)

    @TypeConverter fun householdIdToString(value: HouseholdId): String = value.value

    @TypeConverter fun stringToHouseholdId(value: String): HouseholdId = HouseholdId(value)

    @TypeConverter fun kidIdToString(value: KidId): String = value.value

    @TypeConverter fun stringToKidId(value: String): KidId = KidId(value)

    @TypeConverter fun accountIdToString(value: AccountId): String = value.value

    @TypeConverter fun stringToAccountId(value: String): AccountId = AccountId(value)

    @TypeConverter fun deviceIdToString(value: DeviceId): String = value.value

    @TypeConverter fun stringToDeviceId(value: String): DeviceId = DeviceId(value)

    @TypeConverter fun userIdToString(value: UserId): String = value.value

    @TypeConverter fun stringToUserId(value: String): UserId = UserId(value)

    @TypeConverter fun moneyToLong(value: Money): Long = value.minorUnits

    @TypeConverter fun longToMoney(value: Long): Money = Money(value)

    @TypeConverter fun currencyToString(value: Currency): String = when (value) {
        is Currency.Fiat -> value.isoCode
        Currency.Minutes -> CurrencySerializer.MINUTES
    }

    @TypeConverter fun stringToCurrency(value: String): Currency = when (value) {
        CurrencySerializer.MINUTES -> Currency.Minutes
        else -> Currency.Fiat(value)
    }

    @TypeConverter fun choreIdToString(value: ChoreId): String = value.value

    @TypeConverter fun stringToChoreId(value: String): ChoreId = ChoreId(value)

    @TypeConverter fun rewardIdToString(value: RewardId): String = value.value

    @TypeConverter fun stringToRewardId(value: String): RewardId = RewardId(value)

    @TypeConverter fun submissionIdToString(value: SubmissionId): String = value.value

    @TypeConverter fun stringToSubmissionId(value: String): SubmissionId = SubmissionId(value)

    @TypeConverter fun goalIdToString(value: GoalId): String = value.value

    @TypeConverter fun stringToGoalId(value: String): GoalId = GoalId(value)

    @TypeConverter fun scheduleIdToString(value: ScheduleId): String = value.value

    @TypeConverter fun stringToScheduleId(value: String): ScheduleId = ScheduleId(value)

    @TypeConverter fun calendarDateToString(value: CalendarDate): String = value.toString()

    @TypeConverter fun stringToCalendarDate(value: String): CalendarDate = CalendarDate.parse(value)

    @TypeConverter fun yearMonthToString(value: YearMonth): String = value.toString()

    @TypeConverter fun stringToYearMonth(value: String): YearMonth = YearMonth.parse(value)
}

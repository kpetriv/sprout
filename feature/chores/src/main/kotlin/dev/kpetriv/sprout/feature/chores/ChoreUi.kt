package dev.kpetriv.sprout.feature.chores

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import dev.kpetriv.sprout.core.domainhousehold.ChoreReward
import dev.kpetriv.sprout.core.domainledger.Currency
import dev.kpetriv.sprout.core.domainledger.Money
import dev.kpetriv.sprout.core.model.CalendarDate
import dev.kpetriv.sprout.core.model.ChoreSchedule
import dev.kpetriv.sprout.core.model.DayOfWeek
import dev.kpetriv.sprout.core.model.KidUiTier
import dev.kpetriv.sprout.core.ui.MoneyFormatter
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/** A chore's reward for display: [money] in the household's fiat [currency] and/or [minutes] of screen time. */
data class RewardUi(val money: Money?, val minutes: Money?, val currency: Currency.Fiat) {
    companion object {
        fun of(reward: ChoreReward, currency: Currency.Fiat) = RewardUi(reward.money, reward.minutes, currency)
    }
}

/** "£2.50 + 15 min" (Seedling: "2 coins + 15 min"). */
internal fun RewardUi.format(formatter: MoneyFormatter, tier: KidUiTier = KidUiTier.SAPLING): String = listOfNotNull(
    money?.let { formatter.formatForTier(it, currency, tier) },
    minutes?.let { formatter.formatForTier(it, Currency.Minutes, tier) },
).joinToString(" + ")

/** "Wed 4 Nov" in the current locale. */
@Composable
@ReadOnlyComposable
internal fun formatDate(date: CalendarDate): String {
    val locale = LocalConfiguration.current.locales[0]
    return formatDate(date, locale)
}

internal fun formatDate(date: CalendarDate, locale: Locale): String {
    val local = LocalDate.of(date.year, date.month, date.day)
    val pattern = android.text.format.DateFormat.getBestDateTimePattern(locale, "EEEdMMM")
    return java.time.format.DateTimeFormatter.ofPattern(pattern, locale).format(local)
}

/** "Monday" or, [short], "Mon". */
internal fun DayOfWeek.displayName(locale: Locale, short: Boolean = false): String =
    java.time.DayOfWeek.of(isoNumber).getDisplayName(if (short) TextStyle.SHORT else TextStyle.FULL, locale)

/** "Every day", "Mon, Wed, Fri", "Every Saturday", "Monthly on day 15", "Once, any time", "Once on Wed 4 Nov". */
@Composable
internal fun scheduleLabel(schedule: ChoreSchedule): String {
    val locale = LocalConfiguration.current.locales[0]
    return when (schedule) {
        is ChoreSchedule.Once -> schedule.dueOn?.let {
            stringResource(R.string.feature_chores_schedule_once_on, formatDate(it, locale))
        } ?: stringResource(R.string.feature_chores_schedule_once_any_time)

        is ChoreSchedule.Daily -> if (schedule.daysOfWeek.size == DayOfWeek.DAYS_PER_WEEK) {
            stringResource(R.string.feature_chores_schedule_every_day)
        } else {
            schedule.daysOfWeek.sorted().joinToString(", ") { it.displayName(locale, short = true) }
        }

        is ChoreSchedule.Weekly ->
            stringResource(R.string.feature_chores_schedule_weekly, schedule.dayOfWeek.displayName(locale))

        is ChoreSchedule.Monthly -> stringResource(R.string.feature_chores_schedule_monthly, schedule.dayOfMonth)
    }
}

/** "Today", "Tomorrow", "In 3 days" relative to [today]; the date itself further out. */
@Composable
internal fun relativeDueLabel(dueOn: CalendarDate, today: CalendarDate): String {
    val days = today.daysUntil(dueOn)
    return when {
        days == 0L -> stringResource(R.string.feature_chores_due_today)
        days == 1L -> stringResource(R.string.feature_chores_due_tomorrow)
        days in 2L..WEEK_DAYS -> pluralStringResource(R.plurals.feature_chores_due_in_days, days.toInt(), days.toInt())
        else -> formatDate(dueOn)
    }
}

private const val WEEK_DAYS = 7L

/**
 * A picture for a chore on Seedling cards, from keywords in its title (English; anything else gets the sparkle).
 * Only emoji up to Unicode 11, so they render with the fonts of every supported Android version.
 * Decorative: the title is always shown and read out next to it.
 */
internal object ChoreEmoji {
    private val rules: List<Pair<List<String>, String>> = listOf(
        listOf("bed") to "🛏️",
        listOf("teeth", "tooth", "brush") to "🦷",
        listOf("dish", "plate", "cup", "kitchen") to "🍽️",
        listOf("table") to "🍴",
        listOf("toy", "lego") to "🧸",
        listOf("room", "tidy", "clean") to "🧹",
        listOf("laundry", "clothes", "sock") to "🧺",
        listOf("plant", "water", "garden", "flower") to "🌱",
        listOf("dog", "cat", "pet", "fish", "feed") to "🐾",
        listOf("trash", "rubbish", "bin", "recycl") to "🗑️",
        listOf("read", "book") to "📚",
        listOf("homework", "school", "study") to "✏️",
        listOf("shoe") to "👟",
        listOf("bath", "shower", "wash") to "🛁",
        listOf("car") to "🚗",
    )
    const val DEFAULT: String = "✨"

    fun forTitle(title: String): String {
        val lower = title.lowercase(Locale.ROOT)
        return rules.firstOrNull { (keywords, _) -> keywords.any { it in lower } }?.second ?: DEFAULT
    }
}

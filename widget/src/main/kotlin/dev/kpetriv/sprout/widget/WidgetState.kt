package dev.kpetriv.sprout.widget

import kotlinx.coroutines.flow.Flow

/**
 * What the home screen widget shows (M7 §4, D-M7-1, ADR-0012). The home screen is not `FLAG_SECURE`, so kid data
 * appears only on that kid's own linked phone ([Kid]); everywhere else the widget is a shortcut ([OpenApp]) or, while
 * the phone's household data is being erased or its sign-in has ended, empty ([Blank]).
 */
sealed interface WidgetState {
    /** Nothing but the widget's background: a removal or "Sign in again" is pending, or the data cannot be read. */
    data object Blank : WidgetState

    /** "Open Sprout": a parent's phone, no session yet, the trial household, or a kid that is gone. */
    data object OpenApp : WidgetState

    /**
     * The linked kid phone's day at a glance, already formatted for the kid's tier: [name], the money [balance] and
     * screen-time [minutes] (null when the kid has no such account), the chores [dueToday] and still [toDo], and the
     * titles of the first two to do ([firstToDo]). Names and titles are personal data: [toString] prints counts only.
     */
    class Kid(
        val name: String,
        val balance: String?,
        val minutes: String?,
        val dueToday: Int,
        val toDo: Int,
        val firstToDo: List<String>,
    ) : WidgetState {
        override fun equals(other: Any?): Boolean = other is Kid && name == other.name && balance == other.balance &&
            minutes == other.minutes && dueToday == other.dueToday && toDo == other.toDo &&
            firstToDo == other.firstToDo

        override fun hashCode(): Int = listOf(name, balance, minutes, dueToday, toDo, firstToDo).hashCode()

        override fun toString(): String = "Kid(dueToday=$dueToday, toDo=$toDo)"
    }
}

/**
 * Where the widget's state comes from; the app implements it over the session and `core/database`'s
 * `KidTodaySummaryUseCase` and hands it to [SproutWidget]. [observe] must not throw: it emits [WidgetState.Blank]
 * instead (the widget also catches, as a last resort).
 */
fun interface WidgetStateSource {
    fun observe(): Flow<WidgetState>
}

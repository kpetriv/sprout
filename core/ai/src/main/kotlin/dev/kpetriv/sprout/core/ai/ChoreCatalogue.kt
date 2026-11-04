package dev.kpetriv.sprout.core.ai

import dev.kpetriv.sprout.core.ai.SuggestedSchedule.AnyTime
import dev.kpetriv.sprout.core.ai.SuggestedSchedule.Daily
import dev.kpetriv.sprout.core.ai.SuggestedSchedule.Weekly
import dev.kpetriv.sprout.core.domainledger.Currency
import dev.kpetriv.sprout.core.domainledger.Money
import dev.kpetriv.sprout.core.model.AgeBand
import dev.kpetriv.sprout.core.model.DayOfWeek

/**
 * The curated chore ideas per [AgeBand] (D-M6-4): the rules answer to "Suggest chores" and the yardstick model
 * suggestions are checked against. Rewards are nominal: [Entry.cents] hundredths of one major unit of whatever
 * currency the household uses ([moneyFor]), so 50 is 0.50 GBP, 0.50 EUR or 0.50 USD. A currency with other than two
 * fraction digits is scaled by its digits only (50 is 1 JPY, rounded up to one minor unit, or 0.500 KWD), which is
 * crude; the parent reviews every amount before saving (D-M6-11).
 */
internal object ChoreCatalogue {
    /**
     * One catalogue chore. [cents] (hundredths of a major unit) and [minutes] are the default rewards; at least one is
     * set.
     */
    data class Entry(
        val title: String,
        val description: String,
        val cents: Long?,
        val minutes: Long?,
        val schedule: SuggestedSchedule,
    )

    private const val CENTS_PER_UNIT = 100L
    private const val DECIMAL = 10L

    /** Most screen-time minutes a suggested chore may reward (a model suggestion above it is invalid). */
    const val MAX_MINUTES: Long = 120

    /**
     * How many times the band's largest catalogue reward a model suggestion may ask for before it is treated as
     * invalid; the ledger's own bound ([dev.kpetriv.sprout.core.domainledger.LedgerPolicy.maxAmount]) applies too.
     */
    const val MAX_REWARD_FACTOR: Long = 3

    private val weekend = Daily(setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY))

    private val entries: Map<AgeBand, List<Entry>> = mapOf(
        AgeBand.UNDER_5 to listOf(
            Entry("Put toys back in the box", "Tidy toys into their box before bedtime.", 20, null, Daily.EVERY_DAY),
            Entry("Feed the pet", "Help a grown-up give the pet its food.", 20, null, Daily.EVERY_DAY),
            Entry("Put dirty clothes in the basket", "Clothes go in the laundry basket.", 10, null, Daily.EVERY_DAY),
            Entry("Water a plant", "Give one plant a small cup of water.", 20, null, Weekly(DayOfWeek.SATURDAY)),
            Entry("Help set the table", "Bring the spoons and napkins to the table.", 20, null, Daily.EVERY_DAY),
            Entry("Match the socks", "Find the pairs in the clean sock pile.", 30, 10, Weekly(DayOfWeek.SUNDAY)),
            Entry("Put books on the shelf", "Stand the books back on the shelf.", 20, null, AnyTime),
            Entry("Wipe the table", "Wipe the table with a damp cloth after a meal.", 20, null, Daily.SCHOOL_DAYS),
            Entry("Put shoes away", "Shoes go on the rack by the door.", 10, null, Daily.EVERY_DAY),
            Entry("Help carry the shopping", "Carry a light bag in from the car.", 30, null, AnyTime),
            Entry("Make teddy's bed", "Tuck the cuddly toys in on the bed.", 10, 5, Daily.EVERY_DAY),
            Entry("Sort the recycling", "Help put paper and plastic in the right box.", 30, null, weekend),
        ),
        AgeBand.AGES_5_TO_7 to listOf(
            Entry("Make your bed", "Straighten the covers and the pillow each morning.", 30, null, Daily.EVERY_DAY),
            Entry(
                "Tidy your room",
                "Toys away, clothes in the basket, floor clear.",
                50,
                15,
                Weekly(DayOfWeek.SATURDAY),
            ),
            Entry("Set the table", "Plates, cutlery and cups for everyone.", 30, null, Daily.EVERY_DAY),
            Entry("Clear your plate", "Take your plate and cup to the kitchen.", 20, null, Daily.EVERY_DAY),
            Entry("Feed the pet", "Give the pet its food and fresh water.", 30, null, Daily.EVERY_DAY),
            Entry("Water the plants", "Water the indoor plants.", 40, null, Weekly(DayOfWeek.WEDNESDAY)),
            Entry("Sort the laundry", "Put the clean clothes in piles for each person.", 50, null, weekend),
            Entry("Match the socks", "Pair up the clean socks.", 30, 10, Weekly(DayOfWeek.SUNDAY)),
            Entry(
                "Pack your school bag",
                "Books, lunch box and water bottle the night before.",
                30,
                null,
                Daily.SCHOOL_DAYS,
            ),
            Entry("Help unload the shopping", "Carry bags in and put the tins away.", 40, null, AnyTime),
            Entry("Dust the shelves", "Dust the low shelves with a cloth.", 50, null, Weekly(DayOfWeek.SATURDAY)),
            Entry("Put the recycling out", "Take the recycling box to the bin.", 40, null, Weekly(DayOfWeek.MONDAY)),
            Entry("Read for 20 minutes", "Read a book on your own or with someone.", null, 15, Daily.SCHOOL_DAYS),
        ),
        AgeBand.AGES_8_TO_11 to listOf(
            Entry("Make your bed", "Make the bed properly each morning.", 30, null, Daily.EVERY_DAY),
            Entry("Load the dishwasher", "Rinse plates and load them after dinner.", 50, null, Daily.EVERY_DAY),
            Entry("Empty the dishwasher", "Put the clean dishes and cutlery away.", 50, null, Daily.SCHOOL_DAYS),
            Entry("Vacuum your room", "Vacuum the floor, under the bed too.", 100, 20, Weekly(DayOfWeek.SATURDAY)),
            Entry(
                "Take the bins out",
                "Take the bins to the kerb on collection day.",
                50,
                null,
                Weekly(DayOfWeek.TUESDAY),
            ),
            Entry("Fold your laundry", "Fold your clean clothes and put them away.", 75, null, weekend),
            Entry("Walk the dog", "A walk round the block with a grown-up's OK.", 75, null, Daily.EVERY_DAY),
            Entry("Help cook dinner", "Wash and chop vegetables, stir the pot.", 100, 15, Weekly(DayOfWeek.FRIDAY)),
            Entry("Clean the bathroom sink", "Wipe the sink, taps and mirror.", 75, null, Weekly(DayOfWeek.SUNDAY)),
            Entry(
                "Water the garden",
                "Water the pots and the vegetable patch.",
                50,
                null,
                Daily(setOf(DayOfWeek.MONDAY, DayOfWeek.THURSDAY)),
            ),
            Entry(
                "Sweep the kitchen floor",
                "Sweep under the table and chairs.",
                50,
                null,
                Weekly(DayOfWeek.WEDNESDAY),
            ),
            Entry("Practise an instrument", "Fifteen minutes of practice.", null, 20, Daily.SCHOOL_DAYS),
            Entry("Pack your own lunch", "Make and pack tomorrow's lunch.", 50, null, Daily.SCHOOL_DAYS),
        ),
        AgeBand.AGES_12_PLUS to listOf(
            Entry("Cook a family meal", "Plan and cook one dinner for everyone.", 300, 30, Weekly(DayOfWeek.SUNDAY)),
            Entry(
                "Do your own laundry",
                "Wash, dry, fold and put away your clothes.",
                200,
                null,
                Weekly(DayOfWeek.SATURDAY),
            ),
            Entry(
                "Mow the lawn",
                "Mow and empty the grass box (with the safety rules).",
                300,
                null,
                Weekly(DayOfWeek.SATURDAY),
            ),
            Entry("Clean the bathroom", "Sink, bath, toilet and floor.", 250, null, Weekly(DayOfWeek.SUNDAY)),
            Entry(
                "Vacuum the living room",
                "Move the cushions and vacuum underneath.",
                150,
                null,
                Weekly(DayOfWeek.SATURDAY),
            ),
            Entry("Wash the car", "Wash, rinse and dry the outside.", 300, 30, AnyTime),
            Entry("Load and run the dishwasher", "Load, add a tablet and start it.", 75, null, Daily.EVERY_DAY),
            Entry(
                "Take the bins and recycling out",
                "All bins to the kerb on collection day.",
                75,
                null,
                Weekly(DayOfWeek.TUESDAY),
            ),
            Entry(
                "Change your bed sheets",
                "Strip the bed and put clean sheets on.",
                150,
                null,
                Weekly(DayOfWeek.SUNDAY),
            ),
            Entry("Walk the dog", "A proper walk, at least half an hour.", 100, null, Daily.EVERY_DAY),
            Entry("Help with the weekly shop", "Write the list and help carry it home.", 150, null, weekend),
            Entry("Clean the windows", "Inside of the downstairs windows.", 200, null, AnyTime),
            Entry("Revise for an hour", "One hour of focused revision.", null, 30, Daily.SCHOOL_DAYS),
        ),
    )

    /** The catalogue for [band], in a fixed order. */
    fun entriesFor(band: AgeBand): List<Entry> = entries.getValue(band)

    /** [cents] hundredths of one major unit of [currency], in its minor units; at least one minor unit. */
    fun moneyFor(cents: Long, currency: Currency.Fiat): Money {
        var scale = 1L
        repeat(currency.fractionDigits) { scale *= DECIMAL }
        val minor = (cents * scale + CENTS_PER_UNIT - 1) / CENTS_PER_UNIT
        return Money(maxOf(minor, 1))
    }

    /** The largest money reward a suggestion for [band] in [currency] may carry ([MAX_REWARD_FACTOR]). */
    fun maxRewardFor(band: AgeBand, currency: Currency.Fiat): Money {
        val largest = entriesFor(band).mapNotNull { it.cents }.max()
        return moneyFor(largest * MAX_REWARD_FACTOR, currency)
    }

    /** [entry] as a suggestion in [currency]. */
    fun suggestion(entry: Entry, currency: Currency.Fiat): ChoreSuggestion = ChoreSuggestion(
        title = entry.title,
        description = entry.description,
        money = entry.cents?.let { moneyFor(it, currency) },
        minutes = entry.minutes,
        schedule = entry.schedule,
    )
}

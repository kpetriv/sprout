package dev.kpetriv.sprout.core.games

import kotlin.random.Random

private const val GENTLE_SECONDS = 20
private const val STEADY_SECONDS = 10
private const val ROCK_STAR_SECONDS = 5

/**
 * How fast the Times tables clock runs (M9-games §6.1). The level is a choice of difficulty, not of pay: credit is
 * the same capped [GameCredit] rule at every level (D-M9-G8).
 *
 * Only [ROCK_STAR] is timed ([timed]): its sessions get the timed bonus when every question is answered correctly
 * within [timeLimitMs]. At [GENTLE] and [STEADY] the limit only drives the informational countdown ring.
 *
 * @property secondsPerQuestion the per-question limit in whole seconds.
 */
enum class TablesLevel(val secondsPerQuestion: Int) {
    GENTLE(GENTLE_SECONDS),
    STEADY(STEADY_SECONDS),
    ROCK_STAR(ROCK_STAR_SECONDS),
    ;

    /** [secondsPerQuestion] in milliseconds. */
    val timeLimitMs: Long get() = secondsPerQuestion * MS_PER_SECOND

    /** Whether a session at this level is timed (and so can earn the timed bonus): [ROCK_STAR] only. */
    val timed: Boolean get() = this == ROCK_STAR

    private companion object {
        const val MS_PER_SECOND = 1_000L
    }
}

/**
 * What a Times tables play practises: which [tables] (a non-empty subset of [TABLES], 2 to 12) and at which [level].
 * Kept locally per kid, never on the wire: the ledger sees only `tables.sprout` / `tables.sapling` (D-M9-G7).
 */
data class TablesSetup(val tables: Set<Int>, val level: TablesLevel) {
    init {
        require(tables.isNotEmpty()) { "Pick at least one table" }
        require(tables.all { it in TABLES }) { "Tables must be in $TABLES" }
    }

    /** Whether every table is chosen ("Mix"). */
    val isMix: Boolean get() = tables.size == TABLES.count()

    companion object {
        /** The tables a kid can pick from. */
        val TABLES: IntRange = 2..12

        /** The multipliers each table is asked with (`7 × 1` to `7 × 12`). */
        val MULTIPLIERS: IntRange = 1..12

        /** Every table, at the default (Gentle) level; use [copy] to change the level. */
        val MIX: TablesSetup = TablesSetup(TABLES.toSet(), TablesLevel.GENTLE)

        private val SPROUT_DEFAULT = TablesSetup(setOf(2, 5, 10), TablesLevel.GENTLE)
        private val SAPLING_DEFAULT = TablesSetup(setOf(6, 7, 8, 9), TablesLevel.STEADY)

        /**
         * The setup offered before the kid has chosen one: Sprout `{2, 5, 10}` Gentle, Sapling `{6, 7, 8, 9}` Steady.
         * Throws [IllegalArgumentException] for Seedling, which has no Times tables.
         */
        fun defaultFor(difficulty: Difficulty): TablesSetup = when (difficulty) {
            Difficulty.SEEDLING -> throw IllegalArgumentException("Times tables are not offered at $difficulty")
            Difficulty.SPROUT -> SPROUT_DEFAULT
            Difficulty.SAPLING -> SAPLING_DEFAULT
        }
    }
}

/**
 * Builds a Times tables session: [count] questions spread evenly across the chosen tables (each table gets
 * `count / tables` questions, and the remainder goes one each to tables drawn by the seed), `b` in
 * [TablesSetup.MULTIPLIERS], the order shuffled by the seed.
 *
 * No question repeats within a session while there are enough distinct ones. A single table has only 12, so a
 * 20-question session of one table asks all 12 once (in a seeded order) before any repeats, then 8 more drawn from a
 * second shuffled pass; the first repeat never directly follows the same question. With two or more tables every
 * table's quota is at most 10, so nothing repeats.
 */
internal object TablesGenerator {
    fun session(setup: TablesSetup, count: Int, random: Random): List<TablesRound> {
        val tables = setup.tables.sorted()
        val base = count / tables.size
        val extra = tables.shuffled(random).take(count % tables.size).toSet()
        val perTable = tables.map { table -> table to multipliers(base + if (table in extra) 1 else 0, random) }
        val rounds = perTable.flatMap { (table, bs) -> bs.map { TablesRound.of(table, it) } }
        // Repeats only exist when one table needs more than 12 questions: keep its pass order so every question is
        // asked once before any is asked again. Otherwise shuffle the tables together.
        val repeats = perTable.any { (_, bs) -> bs.size > TablesSetup.MULTIPLIERS.count() }
        return if (repeats) rounds else rounds.shuffled(random)
    }

    /** [quota] multipliers: whole shuffled passes of 1..12, the last one cut short. */
    private fun multipliers(quota: Int, random: Random): List<Int> {
        val result = mutableListOf<Int>()
        while (result.size < quota) {
            val pass = TablesSetup.MULTIPLIERS.shuffled(random).toMutableList()
            // A new pass never starts with the question the previous one ended on.
            if (result.isNotEmpty() && pass.first() == result.last()) {
                pass[0] = pass.last().also { pass[pass.lastIndex] = pass[0] }
            }
            result += pass.take(quota - result.size)
        }
        return result
    }
}

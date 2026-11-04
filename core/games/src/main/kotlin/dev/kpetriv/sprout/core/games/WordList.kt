package dev.kpetriv.sprout.core.games

/** A word for the scramble game. [hint] is a one-word category such as `"animal"`, present for Sprout words only. */
data class WordEntry(val word: String, val hint: String?)

/**
 * The built-in word list (written for the app, no third-party source): common everyday English words, lower-case
 * ASCII, no duplicates. Sprout words are 4–6 letters with a category hint; Sapling words are 6–9 letters and have no
 * hint. The two bands share no words, and no two listed words are anagrams of each other (`melon` and `canoe` were
 * dropped for `lemon` and `ocean`), so a scramble has one listed answer (M5 review M2). Seedling has no word game,
 * so its band is empty.
 */
object WordList {
    private val sproutByHint: Map<String, String> = mapOf(
        "animal" to """
            bear bird frog goat lion wolf duck deer fish crab mouse horse sheep tiger zebra camel whale snake
            otter panda rabbit monkey donkey turtle parrot kitten puppy eagle shark goose
        """,
        "food" to """
            bread apple pear plum rice soup cake milk honey lemon mango pizza pasta salad cheese butter carrot
            banana cherry grape peach bean corn toast cookie juice berry onion olive
        """,
        "home" to """
            door roof room sofa lamp desk chair table sink bath clock spoon fork plate bowl towel pillow window
            carpet shelf stairs garden oven kettle mirror
        """,
        "nature" to """
            tree leaf rock hill lake river cloud grass sand wave stone flower forest ocean moon star beach field
            seed root
        """,
        "body" to """
            hand foot knee nose mouth head hair teeth elbow thumb finger ankle chin cheek heart belly
        """,
        "clothes" to """
            coat shoe sock scarf glove shirt dress jacket boots skirt jumper mitten belt pocket button
        """,
        "weather" to """
            rain snow wind storm sunny frost foggy cloudy hail windy
        """,
        "toy" to """
            ball kite doll drum game puzzle blocks robot swing slide teddy rocket
        """,
        "school" to """
            book paper ruler glue chalk class pencil lesson crayon letter number
        """,
        "vehicle" to """
            train plane boat bike truck ship tram taxi wagon
        """,
        "colour" to """
            blue green pink black white brown purple orange yellow grey silver golden
        """,
    )

    private const val SAPLING_WORDS = """
        adventure airplane alphabet backpack balloon bathroom birthday blanket bicycle breakfast bridge
        brother butterfly calendar camera candle captain castle chicken chimney chocolate circle compass
        computer cousin cupboard curtain dinosaur dolphin dragon elephant evening example factory family
        farmer feather festival friend giraffe glasses hamster harbour helmet holiday hospital island
        jellyfish journey kangaroo keyboard kitchen ladder library lizard lunchbox magnet market meadow
        morning mountain museum notebook octopus orchard painting pancake parcel peacock penguin picnic
        pirate planet pumpkin puppet question rainbow reading rescue sandwich scissors seaside shadow
        shelter shoulder skeleton sledge snowman spider squirrel station stomach sunshine supper surprise
        sweater teacher telescope thunder tomato tortoise treasure triangle trumpet umbrella uniform
        vegetable village violin volcano weekend whistle winter wizard yesterday animal answer autumn basket
        beetle bottle branch bucket cabbage cactus cereal cottage crystal diamond doctor engine explore
        firework garage goldfish hedgehog homework jigsaw lantern lemonade mermaid monster needle ostrich
        paddle passport pebble postcard potato raincoat recipe saddle sailing science seashell stadium
        sticker summer swimming tadpole tunnel unicorn wallet walrus wardrobe windmill woodland
    """

    private val sprout: List<WordEntry> = sproutByHint.flatMap { (hint, words) ->
        split(words).map { WordEntry(it, hint) }
    }

    private val sapling: List<WordEntry> = split(SAPLING_WORDS).map { WordEntry(it, hint = null) }

    /** Every word in the list, Sprout band first. */
    val all: List<WordEntry> get() = sprout + sapling

    fun forDifficulty(difficulty: Difficulty): List<WordEntry> = when (difficulty) {
        Difficulty.SEEDLING -> emptyList()
        Difficulty.SPROUT -> sprout
        Difficulty.SAPLING -> sapling
    }

    private fun split(words: String): List<String> = words.trim().split(Regex("\\s+"))
}

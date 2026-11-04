package dev.kpetriv.sprout.core.ai

/**
 * The English word lists behind [KindnessRules], written for Sprout (no third-party list). Every entry is a
 * normalised key: lower case, letters only (see [KindnessText]). Kept small and specific on purpose: a nudge never
 * blocks a message, but a nudge on an ordinary sentence ("I hate broccoli", "the loser of the game goes first")
 * teaches kids to ignore them. Words that are only unkind when aimed at someone are in [negative], not [insults].
 */
internal object KindnessLexicon {
    /** What a match is, from most to least serious; [KindnessRules] picks the suggestion by the most serious one. */
    enum class Category {
        /** Threats, "I hate you", "shut up", "kill yourself", exclusion ("nobody likes you"). */
        HOSTILE,

        /** Swear words, slurs and mild oaths ("what the hell"). */
        PROFANITY,

        /** Name-calling that is unkind whatever the sentence ("idiot"). */
        INSULT,

        /** A [negative] word aimed at the reader: "you are (so) lazy", "you're a loser", "your drawing is ugly". */
        PUT_DOWN,

        /**
         * All capitals (at least [SHOUT_MIN_LETTERS] letters), or a run of at least [SHOUT_MIN_MARKS] `!` and `?` with
         * at least [SHOUT_MIN_BANGS] `!` ("!!!", "why?!?!"; "???" is only a question).
         */
        SHOUTING,
    }

    /** Letters a message needs, all in capitals, to count as shouting. */
    const val SHOUT_MIN_LETTERS: Int = 8

    /** Exclamation and question marks in a row that may count as shouting. */
    const val SHOUT_MIN_MARKS: Int = 3

    /** Exclamation marks such a run needs to count as shouting. */
    const val SHOUT_MIN_BANGS: Int = 2

    /** Most filler words ("so", "such", "a", "really") between "you are" and a negative word. */
    const val MAX_FILLERS: Int = 3

    /** Most words between "your" and its verb in "your drawing is ugly". */
    const val MAX_POSSESSED_WORDS: Int = 2

    /** Single letters in a row that may spell a word out ("s t u p i d"). */
    const val MIN_SPELLED_LETTERS: Int = 3

    /** Name-calling, matched as whole words (plural and adjective forms listed). */
    val insults: Set<String> = setOf(
        "idiot", "idiots", "idiotic", "moron", "morons", "moronic", "jerk", "jerks", "weirdo", "weirdos", "crybaby",
        "crybabies", "stupidhead", "butthead", "buttface", "poopyhead", "bozo", "twit", "dimwit", "nitwit", "numpty",
        "doofus", "imbecile", "scum", "psycho", "dweeb", "airhead", "bonehead", "fatso", "meanie",
    )

    /** Swear words and slurs matched as whole words. */
    val profanity: Set<String> = setOf(
        "ass", "asses", "arse", "arses", "dick", "dicks", "cock", "cocks", "prick", "pricks", "crap", "crappy",
        "damn", "damned", "dammit", "goddamn", "bollocks", "bugger", "wanker", "wankers", "twat", "twats", "slut",
        "sluts", "whore", "whores", "wtf", "stfu", "omfg", "spaz", "tard", "tards", "kys", "piss", "pissed", "fuk",
        "fuking", "fukin", "fukn", "fkn", "fking",
    )

    /**
     * Swear-word stems matched at the start of a word ("fucking", "shitty", "bitches", "assholes"). [allowed] lists
     * ordinary words that start the same way.
     */
    val profanityStems: List<String> = listOf(
        "fuck", "fck", "shit", "bitch", "biatch", "bastard", "asshole", "arsehole", "dickhead", "cunt", "motherf",
        "retard", "wank", "pissoff",
    )

    /** Ordinary words a stem, a whole word or a collapsed spelling would otherwise catch. */
    val allowed: Set<String> = setOf(
        "shitake", "shiitake", "retardant", "retardants", "cocktail", "cocktails", "dickens", "assess", "assist",
        "class", "pass", "glass", "grass", "mass", "brass", "bass", "cockpit", "peacock", "scunthorpe", "dumbbell",
        "dumbbells", "shital", "shitals", "shitzu", "shitzus", "shihtzu", "shihtzus", "shittim",
    )

    /** Negative words that are a put-down when aimed at the reader. */
    val negative: Set<String> = setOf(
        "stupid", "stupidest", "stoopid", "dumb", "dumber", "dumbest", "ugly", "uglier", "ugliest", "fat", "fatter",
        "lame", "pathetic", "worthless", "useless", "annoying", "gross", "disgusting", "smelly", "stinky", "weird",
        "trash", "garbage", "horrible", "terrible", "awful", "rude", "lazy", "boring", "worst", "evil", "hopeless",
        "nasty", "selfish", "liar", "cheater", "babyish", "brainless", "clueless", "rubbish", "loser", "freak", "dummy",
        "dumbo", "creep", "brat", "pig", "cow", "dork", "nerd", "baby", "fake", "failure", "joke", "mean", "meanest",
    )

    /**
     * [negative] words that are also verbs straight after "you" ("you mean the red one?", "can you trash the box?"):
     * a put-down only after a verb ("you are mean", "are you mean?").
     */
    val verbs: Set<String> = setOf("mean", "trash", "fake")

    /** The [negative] words that also work as a name straight after "you" ("you pig", "you liar"). */
    val negativeNames: Set<String> = setOf(
        "loser", "freak", "dummy", "dumbo", "creep", "brat", "pig", "cow", "dork", "nerd", "baby", "liar", "cheater",
        "failure", "joke",
    )

    /**
     * "You": then a [copulas] verb, [fillers] and a [negative] word ("you are so lazy"); directly a [negative] word
     * ("you stupid", "you pig"); [fillers] and a [negative] word at the start of a clause ("you so dumb"); or, asked,
     * after a [copulas] verb ("are you (so) stupid?").
     */
    val you: Set<String> = setOf("you", "u", "ya", "yall")

    /**
     * "You are" in one word: followed by [fillers] and a [negative] word. "ur" is also "your", so "ur" directly before
     * one of [negativeNames] reads as "your" ("ur baby brother", "ur joke was funny").
     */
    val youAre: Set<String> = setOf("youre", "ur", "yer", "youare")

    /** The [youAre] spelling that also means "your". */
    const val UR: String = "ur"

    /** "Your": followed by up to [MAX_POSSESSED_WORDS] words, a [copulas] verb, [fillers] and a [negative] word. */
    val your: Set<String> = setOf("your", "yours", "ur")

    /** Verbs that link "you" or "your thing" to a negative word. */
    val copulas: Set<String> = setOf(
        "are", "r", "is", "was", "were", "look", "looks", "smell", "smells", "sound", "sounds", "act", "acting",
        "seem", "seems", "be",
    )

    /** Words allowed between the verb and the negative word. */
    val fillers: Set<String> = setOf(
        "so", "such", "a", "an", "the", "really", "very", "totally", "super", "just", "like", "being", "actually",
        "literally", "always", "kinda", "sooo", "soo", "too", "big", "little", "complete", "total",
    )

    /** Words that turn a put-down or a hostile phrase around ("you are not stupid", "I don't hate you"). */
    val negators: Set<String> = setOf(
        "not", "never", "dont", "doesnt", "didnt", "isnt", "arent", "aint", "wasnt", "werent", "wont", "no", "nt",
    )

    /** [negators] that also turn a hostile phrase around; "no" does not ("no, I hate you"). */
    val phraseNegators: Set<String> = negators - "no"

    /**
     * Hostile phrases, as sequences of normalised words. "Hit you" and "hurt you" only with an intent word before them,
     * so "Sorry I hurt you" and "Did the ball hit you?" pass; see [upSlang] for "hit you up".
     */
    val hostilePhrases: List<List<String>> = (
        listOf(
            "hate you", "hate u", "hate ya", "hate your", "hate yall", "shut up", "shutup", "get lost", "kill you",
            "kill u", "punch you", "kick you", "slap you", "beat you up", "nobody likes you", "no one likes you",
            "noone likes you", "everyone hates you", "nobody wants you", "not your friend", "not my friend",
            "go to hell", "drop dead", "wish you were dead", "you should die", "go die", "kill yourself", "kill urself",
            "kill your self", "kill ur self", "kill yourselves", "you suck", "u suck", "ya suck",
        ) + listOf("ill", "i will", "will", "gonna", "going to", "wanna", "want to").flatMap { intent ->
            listOf("hit you", "hit u", "hurt you", "hurt u").map { "$intent $it" }
        }
        ).map { it.split(' ') }

    /** Verbs whose hostile phrase is slang for "get in touch" when "up" follows ("I'll hit you up later"). */
    val upSlang: Set<String> = setOf("hit")

    /**
     * Words that report what someone else said or did: a match after one is the kid telling a parent about it, not
     * being unkind ("Jake called me an idiot", "a girl told me kill yourself"), so it is never nudged (D-M6-52).
     */
    val reportingPhrases: List<List<String>> = listOf(
        "said", "says", "saying", "told me", "wrote", "writes", "called me", "texted", "messaged", "posted",
        "showed me",
    ).map { it.split(' ') }

    /** Places that make the whole message a report, wherever they appear ("kill yourself on my locker"). */
    val reportPlaces: List<List<String>> = listOf("on my locker").map { it.split(' ') }

    /**
     * Words of a kid asking for help or saying they are hurt or unsafe: a message with one is never nudged, whatever
     * else it says (D-M6-52).
     */
    val helpPhrases: List<List<String>> = listOf(
        "help", "scared", "afraid", "hurt me", "hurts", "secret", "if i tell", "if you tell", "dont tell", "bully",
        "bullying", "bullied", "bullies", "touched me", "unsafe",
    ).map { it.split(' ') }

    /** Oaths that are swearing rather than hostility ("what the hell", "bloody hell"). */
    val profanePhrases: List<List<String>> = listOf("the hell", "bloody hell").map { it.split(' ') }
}

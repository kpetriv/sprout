package dev.kpetriv.sprout.core.ai

import dev.kpetriv.sprout.core.ai.KindnessLexicon.Category
import dev.kpetriv.sprout.core.model.KidUiTier
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class KindnessRulesTest {
    private val gateway = RulesAiGateway({ null })

    /** Messages that must be nudged, with the most serious category the rules should find. */
    private val unkind = listOf(
        "you are stupid" to Category.PUT_DOWN,
        "You're so dumb" to Category.PUT_DOWN,
        "ur ugly" to Category.PUT_DOWN,
        "u r a loser" to Category.PUT_DOWN,
        "you are such a loser" to Category.PUT_DOWN,
        "you're the worst" to Category.PUT_DOWN,
        "your drawing is ugly" to Category.PUT_DOWN,
        "your hair looks so stupid" to Category.PUT_DOWN,
        "you pig" to Category.PUT_DOWN,
        "you liar" to Category.PUT_DOWN,
        "idiot" to Category.INSULT,
        "IDIOT" to Category.INSULT,
        "what an idiot lol" to Category.INSULT,
        "you're a moron" to Category.INSULT,
        "crybaby" to Category.INSULT,
        "shut up" to Category.HOSTILE,
        "Shut up!!" to Category.HOSTILE,
        "I hate you" to Category.HOSTILE,
        "i hate u" to Category.HOSTILE,
        "nobody likes you" to Category.HOSTILE,
        "no one likes you" to Category.HOSTILE,
        "get lost" to Category.HOSTILE,
        "I'll punch you" to Category.HOSTILE,
        "you're not my friend anymore" to Category.HOSTILE,
        "what the hell" to Category.HOSTILE,
        "this is shit" to Category.PROFANITY,
        "fuck" to Category.PROFANITY,
        "fucking annoying" to Category.PROFANITY,
        "damn it" to Category.PROFANITY,
        "wtf" to Category.PROFANITY,
        "stfu" to Category.PROFANITY,
        "STOP IT RIGHT NOW" to Category.SHOUTING,
        "give it back!!!" to Category.SHOUTING,
        "why?!?!" to Category.SHOUTING,
    )

    /** Spellings that try to get past a word list. */
    private val adversarial = listOf(
        "you're st00pid" to Category.PUT_DOWN,
        "u r stup1d" to Category.PUT_DOWN,
        "you are \$tupid" to Category.PUT_DOWN,
        "you are stuuuupid" to Category.PUT_DOWN,
        "you are s t u p i d" to Category.PUT_DOWN,
        "s.t.u.p.i.d" to Category.PUT_DOWN,
        "ur a l0ser" to Category.PUT_DOWN,
        "ur a 1oser" to Category.PUT_DOWN,
        "1d10t" to Category.INSULT,
        "IDIOOOOT" to Category.INSULT,
        "i-d-i-o-t" to Category.INSULT,
        "sh!t" to Category.PROFANITY,
        "f*ck off" to Category.PROFANITY,
        "sh*t" to Category.PROFANITY,
        "fuuuuck" to Category.PROFANITY,
        "a\$\$" to Category.PROFANITY,
        "@ss" to Category.PROFANITY,
        "F U C K" to Category.PROFANITY,
        "shuttt upppp" to Category.HOSTILE,
        "i h8 u" to null,
        "You’re dumb" to Category.PUT_DOWN,
        "YOU ARE AN IDIOT" to Category.INSULT,
    )

    /** Ordinary messages, several close to the lists, that must pass. */
    private val clean = listOf(
        "I hate broccoli",
        "you are the best",
        "You're the best sister ever",
        "you are amazing",
        "I love you",
        "Can you pick me up at 5?",
        "Did you see that weird dog?",
        "you mean the red one?",
        "can you slow down please",
        "you are not stupid",
        "you're not a loser",
        "I don't hate you",
        "I never said I hate you",
        "This homework is stupid",
        "my game is so lame today",
        "the loser of the game goes first",
        "the pig at the farm was huge",
        "we passed the class assessment",
        "I need a glass of water",
        "the shiitake mushrooms are nice",
        "Scunthorpe is a town",
        "I lifted a dumbbell",
        "hello hello",
        "she sells sea shells",
        "Ok",
        "lol",
        "I got 100% on my test!",
        "WOW",
        "Thanks!!",
        "Leave me alone please, I'm tired",
        "I feel sad today",
        "Mum, my brother keeps calling me names",
        "Are you a nerd?",
        "can you get the thick blanket",
        "The cockpit of the plane",
        "",
        "   ",
    )

    @Test
    fun unkindMessagesAreNudgedWithTheirCategory() {
        (unkind + adversarial).forEach { (text, category) ->
            val finding = KindnessRules.check(text)
            if (category == null) {
                return@forEach
            }
            assertEquals(category, finding.worst, "\"$text\" found ${finding.categories}")
        }
    }

    @Test
    fun cleanMessagesPass() {
        clean.forEach { text ->
            assertTrue(KindnessRules.check(text).isKind, "\"$text\" was flagged: ${KindnessRules.check(text)}")
        }
    }

    @Test
    fun theGatewayNudgesWithASuggestionThatNeverRepeatsTheFlaggedWords() = runTest {
        for ((text, category) in unkind + adversarial) {
            if (category == null) continue
            for (tier in KidUiTier.entries) {
                val verdict = assertIs<KindnessVerdict.Nudge>(gateway.kindness(text, tier), text)
                val suggestion = requireNotNull(verdict.suggestion) { text }
                assertTrue(AiOutputRules.codePoints(suggestion) <= AiGateway.MAX_SUGGESTION_LENGTH)
                assertTrue(KindnessRules.check(suggestion).isKind, "suggestion for \"$text\" is flagged: $suggestion")
                val flagged = KindnessRules.check(text).words
                val words = KindnessText.tokens(suggestion).flatMap { it.variants }
                assertTrue(words.none { it in flagged }, "suggestion for \"$text\" repeats a flagged word")
            }
        }
    }

    @Test
    fun cleanMessagesGetOk() = runTest {
        clean.forEach { assertEquals(KindnessVerdict.Ok, gateway.kindness(it, KidUiTier.SPROUT), it) }
    }

    @Test
    fun suggestionsDependOnTheTier() {
        val finding = KindnessRules.check("you are so dumb")
        val suggestions = KidUiTier.entries.map { KindnessRules.suggestion(finding, it, "you are so dumb") }
        assertEquals(KidUiTier.entries.size, suggestions.toSet().size)
    }

    @Test
    fun shoutingAloneGetsTheMessageCalmedDown() {
        val text = "COME TO DINNER NOW!!!"
        val suggestion = KindnessRules.suggestion(KindnessRules.check(text), KidUiTier.SAPLING, text)
        assertEquals("Come to dinner now!", suggestion)
    }

    @Test
    fun everyFixedSuggestionIsKindAndShort() {
        KindnessRules.fixedSuggestions.forEach { suggestion ->
            assertTrue(KindnessRules.check(suggestion).isKind, suggestion)
            assertTrue(AiOutputRules.isCleanText(suggestion, AiGateway.MAX_SUGGESTION_LENGTH), suggestion)
        }
    }

    @Test
    fun aFindingNeverPrintsTheWords() {
        assertTrue("stupid" !in KindnessRules.check("you are stupid").toString())
        assertEquals("Nudge(hasSuggestion=true)", KindnessVerdict.Nudge("x").toString())
    }
}

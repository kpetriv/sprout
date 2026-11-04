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
        "what the hell" to Category.PROFANITY,
        "bloody hell" to Category.PROFANITY,
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
        // Review M1: questions, "you + adjective", phrases and "mean".
        "Are you stupid?" to Category.PUT_DOWN,
        "are u dumb" to Category.PUT_DOWN,
        "you stupid" to Category.PUT_DOWN,
        "Are you so stupid?" to Category.PUT_DOWN,
        "you so dumb" to Category.PUT_DOWN,
        "Are you a nerd?" to Category.PUT_DOWN,
        "kill yourself" to Category.HOSTILE,
        "kill urself" to Category.HOSTILE,
        "go kill your self" to Category.HOSTILE,
        "you suck" to Category.HOSTILE,
        "you are mean" to Category.PUT_DOWN,
        "Ur so mean" to Category.PUT_DOWN,
        "are you mean?" to Category.PUT_DOWN,
        // Review M2: a negator in an earlier clause does not turn the phrase around.
        "I don't care, I hate you" to Category.HOSTILE,
        "I didn't do it, shut up!" to Category.HOSTILE,
        "I don't know, shut up" to Category.HOSTILE,
        "No. I don't care! I hate you" to Category.HOSTILE,
        // Review M3: hitting and hurting with intent.
        "I'll hit you" to Category.HOSTILE,
        "im gonna hurt you" to Category.HOSTILE,
        "I will hit u tomorrow" to Category.HOSTILE,
        // Review L4: three marks with two "!".
        "!!!" to Category.SHOUTING,
        "what?!?!" to Category.SHOUTING,
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
        "can you get the thick blanket",
        // Review M3: apologies, accidents and slang.
        "Sorry I hurt you",
        "Did the ball hit you?",
        "I'll hit you up later",
        "ill hit u up tmrw",
        "I didn't mean to hurt you",
        // Review L1: "ur" as "your".
        "ur baby brother is so cute",
        "ur joke was funny",
        // Review L2: names and dogs.
        "Shital is coming to my party",
        "my shitzu is so fluffy",
        "We saw a shih-tzu and a shihtzu",
        // Review L4: questions are not shouting.
        "Where are you???",
        "really?!?",
        // Verbs after "you".
        "you mean the red one?",
        "Can you trash the old box?",
        // Negation in the same clause still counts.
        "I don't hate you, you're my best friend",
        "I didn't say shut up",
        "Thank you, the weird dog is back",
        "The cockpit of the plane",
        "",
        "   ",
    )

    /**
     * Audit 2 N2 (D-M6-52): a kid reporting what someone else said or did, or asking for help, is never nudged, even
     * when the report quotes the words.
     */
    private val reportsAndPleas = listOf(
        // Reports that the first fix pass started to nudge.
        "Someone wrote kill yourself on my locker",
        "a girl in my class told me kill yourself",
        "Someone said you suck at football",
        // The audit's nine still-nudged reports and pleas.
        "my teacher said he will hit you if you tell",
        "he said he is going to hurt you",
        "they said they want to hurt you",
        "a boy showed me his dick",
        "they said kys to me",
        "Jake called me an idiot",
        "they keep saying you are ugly to me",
        "HELP ME PLEASE",
        "help!!!",
        // More of the same shape.
        "he texted me shut up loser",
        "She posted that I'm an idiot",
        "they called me a moron",
        "I'm scared, he said he'll kill you",
        "please don't tell, he said he'll punch you",
        "some kids are bullying me and say I'm a weirdo",
        "it hurts and I'm afraid",
        "he touched me and said shut up",
        "I feel unsafe at school",
    )

    @Test
    fun reportsAndPleasForHelpAreNeverNudged() = runTest {
        reportsAndPleas.forEach { text ->
            assertTrue(KindnessRules.check(text).isKind, "\"$text\" was flagged: ${KindnessRules.check(text)}")
            assertTrue(KindnessRules.isReportOrPlea(text), text)
            KidUiTier.entries.forEach { assertEquals(KindnessVerdict.Ok, gateway.kindness(text, it), text) }
        }
    }

    @Test
    fun aReportOnlyCoversWhatComesAfterIt() {
        assertEquals(Category.INSULT, KindnessRules.check("you idiot, Jake said hi").worst)
        assertEquals(Category.HOSTILE, KindnessRules.check("shut up, she said it first").worst)
    }

    @Test
    fun modelOutputGetsNoCarveOut() {
        reportsAndPleas.filter { it != "I feel unsafe at school" && it != "it hurts and I'm afraid" }.forEach { text ->
            assertTrue(!KindnessRules.check(text, allowReports = false).isKind, text)
        }
        assertTrue(!AiOutputRules.isValidSuggestion("Jake called me an idiot", "you idiot"))
    }

    @Test
    fun unkindMessagesAreNudgedWithTheirCategory() {
        (unkind + adversarial).forEach { (text, category) ->
            val finding = KindnessRules.check(text)
            if (category == null) {
                return@forEach
            }
            assertEquals(category, finding.worst, "\"$text\" found ${finding.categories}")
            assertTrue(!KindnessRules.isReportOrPlea(text), text)
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
    fun marksAloneGetAFixedSuggestionNotABareMark() {
        val suggestion = KindnessRules.suggestion(KindnessRules.check("!!!"), KidUiTier.SEEDLING, "!!!")
        assertEquals("Can we talk?", suggestion)
    }

    @Test
    fun theReviewsExamplesBothWaysThroughTheGateway() = runTest {
        assertEquals(KindnessVerdict.Ok, gateway.kindness("Sorry I hurt you", KidUiTier.SPROUT))
        assertIs<KindnessVerdict.Nudge>(gateway.kindness("Are you stupid?", KidUiTier.SPROUT))
        assertEquals(KindnessVerdict.Ok, gateway.kindness("I'll hit you up later", KidUiTier.SAPLING))
        assertIs<KindnessVerdict.Nudge>(gateway.kindness("kill yourself", KidUiTier.SAPLING))
        assertEquals(KindnessVerdict.Ok, gateway.kindness("Where are you???", KidUiTier.SEEDLING))
        assertIs<KindnessVerdict.Nudge>(gateway.kindness("I don't care, I hate you", KidUiTier.SPROUT))
    }

    @Test
    fun aClauseEndsAtPunctuationButNotInsideAWord() {
        val tokens = KindnessText.tokens("I don't care, I hate you! sh!t")
        assertEquals(listOf(0, 0, 0, 1, 1, 1, 2), tokens.map { it.clause })
        assertEquals("shit", tokens.last().key)
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

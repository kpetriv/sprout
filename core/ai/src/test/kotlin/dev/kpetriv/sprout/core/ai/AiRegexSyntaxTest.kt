package dev.kpetriv.sprout.core.ai

import java.io.File
import java.util.regex.Pattern
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Every `Regex(...)` in `core/ai`'s sources, read from the source, compiles with `java.util.regex.Pattern` and has no
 * syntax that only the JVM accepts. Android compiles patterns with ICU, which rejects a bare `}` or `]` and a `{` that
 * does not start a quantifier, all of which the JVM reads as literals: slice C2's markup pattern failed only on the
 * device. This is a lint-style guard on the JVM; an instrumented run on a device remains the real one (M7).
 */
class AiRegexSyntaxTest {
    private val sources: List<File> = File("src/main/kotlin").walkTopDown().filter { it.extension == "kt" }.toList()

    /** `Regex(...)` arguments that are built at run time, so cannot be read here. Review any addition. */
    private val dynamic = setOf("KindnessRules.kt: word.replace(\"*\", \"[a-z]\")")

    /** Run-time values in string templates, with a value of the same shape. */
    private val runtimeTemplates = mapOf("anomalies" to "1")

    @Test
    fun everyRegexInCoreAiCompilesAndIsIcuSafe() {
        assertTrue(sources.isNotEmpty(), "no sources found from ${File(".").absolutePath}")
        val constants = sources.flatMap { constants(it.readText()) }.toMap()
        val found = mutableListOf<String>()
        val built = mutableSetOf<String>()
        for (file in sources) {
            for (argument in regexArguments(file.readText())) {
                val pattern = literal(argument, constants)
                if (pattern == null) {
                    built += "${file.name}: $argument"
                    continue
                }
                runCatching { Pattern.compile(pattern) }
                    .onFailure { fail("${file.name}: $pattern does not compile: ${it.message}") }
                val problems = icuProblems(pattern)
                assertTrue(problems.isEmpty(), "${file.name}: $pattern: $problems")
                found += pattern
            }
        }
        assertEquals(dynamic, built, "a Regex built at run time: check it by hand and list it here")
        assertTrue(found.size >= MIN_PATTERNS, "only ${found.size} patterns read; is the parser broken?")
    }

    @Test
    fun theCheckCatchesWhatIcuRejects() {
        listOf("\\{\\{|}}", "a]", "x{", "{2}", "(?:{1,2})", "[a[b]]", "[]a]", "a{,3}").forEach { pattern ->
            assertTrue(icuProblems(pattern).isNotEmpty(), pattern)
        }
        listOf(
            "\\{\\{|\\}\\}",
            "[a-z]{2,}",
            "\\p{L}{1,4}",
            "[\\[(]",
            "[{}]",
            "\\uFF0E",
            "a{3}b{1,}",
            "(?<![\\d.,])x",
            "\\Q{]\\E",
        ).forEach { pattern -> assertEquals(emptyList(), icuProblems(pattern), pattern) }
    }

    /** `const val NAME = "..."` and `const val NAME: Int = 3` declarations, as their values. */
    private fun constants(source: String): List<Pair<String, String>> =
        Regex("const val (\\w+)(?::\\s*\\w+)?\\s*=\\s*(\"(?:[^\"\\\\]|\\\\.)*\"|[\\d_]+)").findAll(source).map {
            val value = it.groupValues[2]
            val decoded = if (value.startsWith('"')) {
                decode(value.drop(1).dropLast(1), emptyMap()) ?: error("template in ${it.groupValues[1]}")
            } else {
                value.replace("_", "")
            }
            it.groupValues[1] to decoded
        }.toList()

    /** The first argument of every `Regex(` call, as source text. */
    private fun regexArguments(source: String): List<String> {
        val arguments = mutableListOf<String>()
        var index = source.indexOf(CALL)
        while (index >= 0) {
            val before = source.getOrNull(index - 1)
            if (before == null || !before.isLetterOrDigit()) {
                arguments += firstArgument(source, index + CALL.length)
            }
            index = source.indexOf(CALL, index + 1)
        }
        return arguments
    }

    /** The text from [start] to the first top-level `,` or the closing `)`, skipping string literals. */
    @Suppress("LoopWithTooManyJumpStatements") // A small scanner: a closing parenthesis and a comma both end it.
    private fun firstArgument(source: String, start: Int): String {
        var depth = 0
        var index = start
        while (index < source.length) {
            when (source[index]) {
                '"' -> index = endOfString(source, index)
                '(' -> depth++
                ')' -> if (depth == 0) break else depth--
                ',' -> if (depth == 0) break
            }
            index++
        }
        return source.substring(start, index).trim()
    }

    /** The index of the closing quote of the string literal opening at [open]. */
    private fun endOfString(source: String, open: Int): Int {
        var index = open + 1
        while (source[index] != '"') index += if (source[index] == '\\') 2 else 1
        return index
    }

    /** [argument] as a pattern when it is string literals joined with `+`; null when it is built at run time. */
    @Suppress("ReturnCount") // Anything but a literal or `+`, or an unknown template: both mean "built at run time".
    private fun literal(argument: String, constants: Map<String, String>): String? {
        val parts = mutableListOf<String>()
        var index = 0
        while (index < argument.length) {
            val char = argument[index]
            when {
                char.isWhitespace() || char == '+' -> index++

                char == '"' -> {
                    val end = endOfString(argument, index)
                    parts += decode(argument.substring(index + 1, end), constants) ?: return null
                    index = end + 1
                }

                else -> return null
            }
        }
        return parts.joinToString("").takeIf { parts.isNotEmpty() }
    }

    /** A Kotlin string literal's body: escapes decoded and templates resolved; null when a template is unknown. */
    @Suppress("CyclomaticComplexMethod", "ReturnCount")
    private fun decode(body: String, constants: Map<String, String>): String? {
        val out = StringBuilder()
        var index = 0
        while (index < body.length) {
            val char = body[index]
            when {
                char == '\\' -> {
                    val next = body[index + 1]
                    when (next) {
                        'n' -> out.append('\n')
                        't' -> out.append('\t')
                        'r' -> out.append('\r')
                        'u' -> out.append(body.substring(index + 2, index + 6).toInt(HEX).toChar())
                        else -> out.append(next)
                    }
                    index += if (next == 'u') 6 else 2
                }

                char == '$' && body.getOrNull(index + 1) == '{' -> {
                    val close = body.indexOf('}', index)
                    val name = body.substring(index + 2, close).substringAfterLast('.')
                    out.append(constants[name] ?: runtimeTemplates[name] ?: return null)
                    index = close + 1
                }

                char == '$' && body.getOrNull(index + 1)?.let { it.isLetter() || it == '_' } == true -> {
                    var end = index + 1
                    while (end < body.length && (body[end].isLetterOrDigit() || body[end] == '_')) end++
                    val name = body.substring(index + 1, end)
                    out.append(constants[name] ?: runtimeTemplates[name] ?: return null)
                    index = end
                }

                else -> {
                    out.append(char)
                    index++
                }
            }
        }
        return out.toString()
    }

    /**
     * What ICU would reject or read differently in [pattern]: outside a character class, a `]`, a `}`, or a `{` that
     * is not a quantifier after an atom; inside one, an unescaped `[` (ICU nests sets) or a `]` straight after the
     * opening bracket.
     */
    @Suppress("CyclomaticComplexMethod", "LoopWithTooManyJumpStatements", "NestedBlockDepth", "LongMethod")
    private fun icuProblems(pattern: String): List<String> {
        val problems = mutableListOf<String>()
        var index = 0
        var inClass = false
        var atom = false
        while (index < pattern.length) {
            val char = pattern[index]
            if (char == '\\') {
                val next = pattern.getOrNull(index + 1)
                index = when {
                    next != null && next in "pPxN" && pattern.getOrNull(index + 2) == '{' ->
                        pattern.indexOf('}', index) + 1

                    next == 'u' -> index + UNICODE_ESCAPE

                    next == 'Q' -> pattern.indexOf("\\E", index).let { if (it < 0) pattern.length else it + 2 }

                    else -> index + 2
                }
                atom = true
                continue
            }
            if (inClass) {
                when (char) {
                    '[' -> problems += "unescaped [ in a class at $index"

                    ']' -> {
                        inClass = false
                        atom = true
                    }
                }
                index++
                continue
            }
            when (char) {
                '[' -> {
                    inClass = true
                    index++
                    if (pattern.getOrNull(index) == '^') index++
                    if (pattern.getOrNull(index) == ']') {
                        problems += "] first in a class at $index"
                        index++
                    }
                    continue
                }

                ']' -> problems += "unescaped ] at $index"

                '}' -> problems += "unescaped } at $index"

                '(' -> {
                    index = afterGroupOpening(pattern, index)
                    atom = false
                    continue
                }

                '{' -> {
                    val quantifier = quantifierPattern.find(pattern, index)?.takeIf { it.range.first == index }
                    if (quantifier == null || !atom) {
                        problems += "{ that is not a quantifier at $index"
                    } else {
                        index += quantifier.value.length
                        continue
                    }
                }
            }
            atom = char !in "(|"
            index++
        }
        if (inClass) problems += "unclosed class"
        return problems
    }

    /** The index after `(` and any group header (`?:`, `?=`, `?<!`, `?<name>`, `?i)`) at [open]. */
    private fun afterGroupOpening(pattern: String, open: Int): Int {
        if (pattern.getOrNull(open + 1) != '?') return open + 1
        val kind = pattern.getOrNull(open + 2)
        val lookbehind = kind == '<' && pattern.getOrNull(open + 3) in setOf('=', '!')
        return when {
            kind in setOf(':', '=', '!', '>') -> open + 3
            lookbehind -> open + 4
            kind == '<' -> pattern.indexOf('>', open) + 1
            else -> pattern.indexOfAny(charArrayOf(')', ':'), open) + 1
        }
    }

    private val quantifierPattern = Regex("\\{\\d+(?:,\\d*)?\\}")

    private companion object {
        const val CALL = "Regex("
        const val HEX = 16
        const val UNICODE_ESCAPE = 6
        const val MIN_PATTERNS = 30
    }
}

package dev.kpetriv.sprout.core.domainhousehold

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Money must never touch floating point (CLAUDE.md, ADR-0002). Scans the production sources of this module. */
class NoFloatingPointTest {
    private val forbidden = listOf(
        Regex("""\b(Double|Float)\b"""),
        Regex("""\.to(Double|Float)\("""),
        Regex("""\b\d+\.\d+([eE][+-]?\d+)?[fF]?\b"""),
        Regex("""\b\d+[fF]\b"""),
        Regex("""\bBigDecimal\b"""),
    )

    @Test
    fun productionSourcesUseNoFloatingPoint() {
        val sources = File("src/main/kotlin").walkTopDown().filter { it.extension == "kt" }.toList()
        assertTrue(sources.size >= 10, "expected to find the module's sources, found ${sources.size}")
        val offences = sources.flatMap { file -> offences(file.readText()).map { "${file.name}:$it" } }
        assertTrue(offences.isEmpty(), "Floating point in household sources:\n" + offences.joinToString("\n"))
    }

    @Test
    fun theDetectorCatchesFloatingPointButNotCommentsOrStrings() {
        assertEquals(1, offences("val rate: Double = 0").size)
        assertEquals(1, offences("val x = 1.5").size)
        assertEquals(1, offences("val x = 2f").size)
        assertEquals(1, offences("val x = n.toFloat()").size)
        assertEquals(0, offences("// a Double in a comment\n/* 1.005 */ val s = \"1.5 Float\"").size)
        assertEquals(0, offences("val mask = 0x0F").size)
    }

    private fun offences(text: String): List<String> = stripComments(text).lines().mapIndexedNotNull { index, line ->
        val code = line.replace(Regex(""""(\\.|[^"\\])*""""), "\"\"")
        if (forbidden.any { it.containsMatchIn(code) }) "${index + 1}: $line" else null
    }

    /** Blanks out comments but keeps line breaks so reported line numbers stay right. */
    private fun stripComments(text: String): String = text
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)) { match ->
            match.value.replace(Regex("[^\n]"), " ")
        }
        .replace(Regex("//[^\n]*"), "")
}

package dev.kpetriv.sprout.core.designsystem.theme

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import androidx.annotation.FontRes
import androidx.test.core.app.ApplicationProvider
import dev.kpetriv.sprout.core.designsystem.R
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.nio.ByteBuffer
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The bundled fonts cover what Sprout writes (M9 §2.2, D-M9-4): the pound and euro signs and Latin Extended in the
 * font files themselves, and Cyrillic at least through Android's fallback (neither Outfit nor Plus Jakarta Sans has
 * Cyrillic; core/designsystem/fonts/FONTS.md).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class FontGlyphTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private val fonts = mapOf(
        "outfit_bold" to R.font.outfit_bold,
        "outfit_extrabold" to R.font.outfit_extrabold,
        "plus_jakarta_sans_medium" to R.font.plus_jakarta_sans_medium,
        "plus_jakarta_sans_semibold" to R.font.plus_jakarta_sans_semibold,
        "plus_jakarta_sans_bold" to R.font.plus_jakarta_sans_bold,
    )

    @Test
    fun `every font loads as a typeface`() {
        fonts.forEach { (name, id) -> assertTrue(typeface(id) != Typeface.DEFAULT, "$name did not load") }
    }

    @Test
    fun `the font files themselves cover currency and Latin Extended`() {
        fonts.forEach { (name, id) ->
            val covered = cmap(id)
            val missing = (CURRENCY + LATIN_EXTENDED).filter { it.code !in covered }
            assertEquals(emptyList(), missing, "$name lacks these glyphs")
        }
    }

    @Test
    fun `currency and Latin Extended render through the typefaces`() {
        fonts.forEach { (name, id) ->
            val paint = Paint().apply { typeface = typeface(id) }
            (CURRENCY + LATIN_EXTENDED).forEach { char ->
                assertTrue(paint.hasGlyph(char.toString()), "$name cannot draw '$char'")
            }
        }
    }

    @Test
    fun `Cyrillic is not in the bundled files but still renders through the system fallback`() {
        fonts.forEach { (name, id) ->
            val covered = cmap(id)
            assertTrue(CYRILLIC.none { it.code in covered }, "$name now has Cyrillic: update FONTS.md and this test")
            val paint = Paint().apply { typeface = typeface(id) }
            CYRILLIC.forEach { char ->
                assertTrue(paint.hasGlyph(char.toString()), "'$char' has no glyph even through fallback ($name)")
            }
        }
    }

    @Test
    fun `hasGlyph is a real check in this environment`() {
        val paint = Paint().apply { typeface = typeface(R.font.outfit_bold) }
        assertFalse(paint.hasGlyph(NONCHARACTER), "hasGlyph is true for anything, so the checks above prove nothing")
    }

    private fun typeface(@FontRes id: Int): Typeface = context.resources.getFont(id)

    /** The code points of the font file's Unicode `cmap` subtables (formats 4 and 12). */
    private fun cmap(@FontRes id: Int): Set<Int> {
        val bytes = context.resources.openRawResource(id).use { it.readBytes() }
        val font = ByteBuffer.wrap(bytes)
        val tableCount = font.getShort(4).toInt() and USHORT
        val cmapOffset = (0 until tableCount).map { TABLE_DIRECTORY + it * TABLE_RECORD }
            .first { record -> String(bytes, record, 4, Charsets.US_ASCII) == "cmap" }
            .let { record -> font.getInt(record + 8) }
        val subtables = font.getShort(cmapOffset + 2).toInt() and USHORT
        val codePoints = mutableSetOf<Int>()
        repeat(subtables) { index ->
            val start = cmapOffset + font.getInt(cmapOffset + 4 + index * 8 + 4)
            when (font.getShort(start).toInt() and USHORT) {
                FORMAT_SEGMENTS -> readFormat4(font, start, codePoints)
                FORMAT_GROUPS -> readFormat12(font, start, codePoints)
            }
        }
        return codePoints
    }

    private fun readFormat4(font: ByteBuffer, start: Int, into: MutableSet<Int>) {
        val segments = (font.getShort(start + 6).toInt() and USHORT) / 2
        val ends = start + 14
        val starts = ends + segments * 2 + 2
        repeat(segments) { segment ->
            val first = font.getShort(starts + segment * 2).toInt() and USHORT
            val last = font.getShort(ends + segment * 2).toInt() and USHORT
            if (first != USHORT) into += first..last
        }
    }

    private fun readFormat12(font: ByteBuffer, start: Int, into: MutableSet<Int>) {
        val groups = font.getInt(start + 12)
        repeat(groups) { group ->
            val record = start + 16 + group * 12
            into += font.getInt(record)..font.getInt(record + 4)
        }
    }

    private companion object {
        val CURRENCY = "£€".toList()
        val LATIN_EXTENDED = "ĄŁŻčšž".toList()
        val CYRILLIC = "Привіт".toList()
        const val NONCHARACTER = "\uFFFF"
        const val USHORT = 0xFFFF
        const val TABLE_DIRECTORY = 12
        const val TABLE_RECORD = 16
        const val FORMAT_SEGMENTS = 4
        const val FORMAT_GROUPS = 12
    }
}

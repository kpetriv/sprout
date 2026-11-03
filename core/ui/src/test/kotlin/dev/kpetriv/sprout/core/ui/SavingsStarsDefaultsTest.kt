package dev.kpetriv.sprout.core.ui

import org.junit.Test
import kotlin.test.assertEquals

class SavingsStarsDefaultsTest {
    @Test
    fun `stars are whole fifths rounded down`() {
        assertEquals(0, SavingsStarsDefaults.filledStars(saved = 0, target = 4_000))
        assertEquals(0, SavingsStarsDefaults.filledStars(saved = -50, target = 4_000))
        assertEquals(0, SavingsStarsDefaults.filledStars(saved = 799, target = 4_000))
        assertEquals(1, SavingsStarsDefaults.filledStars(saved = 800, target = 4_000))
        assertEquals(1, SavingsStarsDefaults.filledStars(saved = 1_250, target = 4_000))
        assertEquals(4, SavingsStarsDefaults.filledStars(saved = 3_999, target = 4_000))
        assertEquals(5, SavingsStarsDefaults.filledStars(saved = 4_000, target = 4_000))
        assertEquals(5, SavingsStarsDefaults.filledStars(saved = 9_000, target = 4_000))
    }

    @Test
    fun `no target means no stars and huge amounts do not overflow`() {
        assertEquals(0, SavingsStarsDefaults.filledStars(saved = 100, target = 0))
        assertEquals(2, SavingsStarsDefaults.filledStars(saved = Long.MAX_VALUE / 2, target = Long.MAX_VALUE))
        assertEquals(4, SavingsStarsDefaults.filledStars(saved = Long.MAX_VALUE - 1, target = Long.MAX_VALUE))
    }
}

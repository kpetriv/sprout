package dev.kpetriv.sprout.core.ui

import org.junit.Test
import kotlin.test.assertEquals

class BalanceJarDefaultsTest {
    @Test
    fun `level is count over the next capacity`() {
        assertEquals(0f, BalanceJarDefaults.levelFor(0))
        assertEquals(0f, BalanceJarDefaults.levelFor(-3))
        assertEquals(0.7f, BalanceJarDefaults.levelFor(7))
        assertEquals(1f, BalanceJarDefaults.levelFor(10))
        assertEquals(0.55f, BalanceJarDefaults.levelFor(11))
        assertEquals(0.5f, BalanceJarDefaults.levelFor(250))
        assertEquals(1f, BalanceJarDefaults.levelFor(1_000_000))
    }
}

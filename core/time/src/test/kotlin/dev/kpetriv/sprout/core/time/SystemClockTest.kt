package dev.kpetriv.sprout.core.time

import kotlin.test.Test
import kotlin.test.assertTrue

class SystemClockTest {
    @Test
    fun neverGoesBackwardsBetweenReads() {
        val first = SystemClock.now()
        val second = SystemClock.now()
        assertTrue(second >= first)
    }
}

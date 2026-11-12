package dev.kpetriv.sprout.onboarding

import dev.kpetriv.sprout.core.sync.SyncError
import dev.kpetriv.sprout.core.sync.SyncReport
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OnboardingAdaptersTest {
    @Test
    fun `only a sync that ran and completed without an error counts as a pull`() {
        assertTrue(SyncReport().completed())
        assertFalse(SyncReport(error = SyncError.Transient()).completed())
        // Review Low: a sync that could not run at all (stopped, paused, a store failure) is no pull (D-REL-14).
        assertFalse((null as SyncReport?).completed())
    }
}

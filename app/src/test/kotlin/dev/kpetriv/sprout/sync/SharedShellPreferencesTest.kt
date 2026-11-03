package dev.kpetriv.sprout.sync

import android.app.Application
import dev.kpetriv.sprout.PARENT
import dev.kpetriv.sprout.REAL_HOUSEHOLD
import dev.kpetriv.sprout.core.model.HouseholdId
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.security.MessageDigest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The shell's digests are keyed per install, and a wipe forgets them and the first pull (audit L7). */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class) // Not the Hilt app: its startup opens the encrypted database.
class SharedShellPreferencesTest {
    private val context: Application = RuntimeEnvironment.getApplication()

    private fun plainSha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.encodeToByteArray()).joinToString("") { "%02x".format(it) }

    @Test
    fun `digests are HMACs under a per-install key, stable across instances`() {
        val first = SharedShellPreferences(context)
        val digest = first.digestOf(REAL_HOUSEHOLD, PARENT)
        assertEquals(digest, SharedShellPreferences(context).digestOf(REAL_HOUSEHOLD, PARENT), "the key is stored")
        assertNotEquals(plainSha256("${REAL_HOUSEHOLD.value}\u0000${PARENT.value}"), digest, "not a bare hash")
        assertNotEquals(digest, first.digestOf(REAL_HOUSEHOLD))
        assertNotEquals(first.digestOf(REAL_HOUSEHOLD), first.digestOf(HouseholdId("another")))
        assertEquals(64, digest.length)
    }

    @Test
    fun `a wipe forgets the first pull, the linked account and the setup, and replaces the key`() {
        val preferences = SharedShellPreferences(context)
        preferences.markLinked()
        preferences.recordFirstPull(REAL_HOUSEHOLD)
        preferences.setLinkedAccount(preferences.digestOf(REAL_HOUSEHOLD, PARENT))
        preferences.setParentSetupPending(preferences.digestOf(REAL_HOUSEHOLD))
        preferences.setRemoval(Removal(RemovalReason.SIGNED_OUT))
        val before = preferences.digestOf(REAL_HOUSEHOLD)
        assertTrue(preferences.firstPullDone(REAL_HOUSEHOLD))

        preferences.wipe()

        assertFalse(preferences.firstPullDone(REAL_HOUSEHOLD))
        assertNull(preferences.linkedAccount())
        assertNull(preferences.parentSetupPending())
        assertNotEquals(before, preferences.digestOf(REAL_HOUSEHOLD), "nothing links the households across a wipe")
        assertTrue(preferences.wasLinked(), "kept: the demo trial is never seeded again")
        assertEquals(Removal(RemovalReason.SIGNED_OUT), preferences.removal(), "kept: the removed screen needs it")
        assertEquals(preferences.digestOf(REAL_HOUSEHOLD), SharedShellPreferences(context).digestOf(REAL_HOUSEHOLD))
    }
}

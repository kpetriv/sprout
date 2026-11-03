package dev.kpetriv.sprout.sync

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.kpetriv.sprout.core.model.HouseholdId
import dev.kpetriv.sprout.core.model.UserId
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/** Why this device has to wipe its household data (M3 §6). */
enum class RemovalReason {
    /** The household was deleted, or this member was removed from it (`SyncReport.householdGone`). */
    HOUSEHOLD_GONE,

    /** A parent revoked this device: its registry entry, mirrored into the session, is REVOKED. */
    DEVICE_REVOKED,

    /** This phone signed out, or another account signed in (D-M3-47): its copy of the household is erased. */
    SIGNED_OUT,
}

/** A removal as stored: why, and whether the wipe has finished. */
data class Removal(val reason: RemovalReason, val wiped: Boolean = false)

/**
 * Plain (non-secret) app-shell state that must survive a wipe, which deletes the database and the `SecretStore`
 * blobs but not shared preferences. Holds no personal data: households and accounts are kept only as unsalted
 * SHA-256 digests ([digestOf]), used for equality checks, never shown or sent.
 */
interface ShellPreferences {
    /**
     * True once this device has belonged to a real household. Debug builds seed the demo trial household only on a
     * device that never did, so a wipe or a sign-out lands on onboarding instead of a fresh trial (M3 §6).
     */
    fun wasLinked(): Boolean

    fun markLinked()

    /** Digest of the real account and household the local rows belong to ([digestOf]); null when none. */
    fun linkedAccount(): String?

    fun setLinkedAccount(digest: String?)

    /** True once a sync of [householdId] finished without an error on this device (the planner waits for it). */
    fun firstPullDone(householdId: HouseholdId): Boolean

    fun recordFirstPull(householdId: HouseholdId)

    /** Digest of the household whose parent setup (PIN and signing step) is unfinished; null when none. */
    fun parentSetupPending(): String?

    fun setParentSetupPending(digest: String?)

    /** The removal under way or finished but not yet acknowledged; null when there is none. */
    fun removal(): Removal?

    /** Stores [removal] durably (committed before returning); null clears it. */
    fun setRemoval(removal: Removal?)

    companion object {
        /** The digest stored for [householdId] (and [uid] when given): hex SHA-256 of the ids, NUL-separated. */
        fun digestOf(householdId: HouseholdId, uid: UserId? = null): String {
            val text = listOfNotNull(householdId.value, uid?.value).joinToString("\u0000")
            return MessageDigest.getInstance("SHA-256").digest(text.encodeToByteArray())
                .joinToString("") { "%02x".format(it) }
        }
    }
}

@Singleton
class SharedShellPreferences @Inject constructor(@param:ApplicationContext private val context: Context) :
    ShellPreferences {
    private val preferences by lazy { context.getSharedPreferences(FILE, Context.MODE_PRIVATE) }

    override fun wasLinked(): Boolean = preferences.getBoolean(LINKED, false)

    override fun markLinked() {
        if (!wasLinked()) commit { putBoolean(LINKED, true) }
    }

    override fun linkedAccount(): String? = preferences.getString(LINKED_ACCOUNT, null)

    override fun setLinkedAccount(digest: String?) = commit { putString(LINKED_ACCOUNT, digest) }

    override fun firstPullDone(householdId: HouseholdId): Boolean =
        preferences.getString(FIRST_PULL, null) == ShellPreferences.digestOf(householdId)

    override fun recordFirstPull(householdId: HouseholdId) =
        commit { putString(FIRST_PULL, ShellPreferences.digestOf(householdId)) }

    override fun parentSetupPending(): String? = preferences.getString(PARENT_SETUP, null)

    override fun setParentSetupPending(digest: String?) = commit { putString(PARENT_SETUP, digest) }

    override fun removal(): Removal? {
        val reason = preferences.getString(REMOVAL, null)?.let { name ->
            RemovalReason.entries.firstOrNull { it.name == name }
        } ?: return null
        return Removal(reason, wiped = preferences.getBoolean(WIPED, false))
    }

    override fun setRemoval(removal: Removal?) = commit {
        putString(REMOVAL, removal?.reason?.name)
        putBoolean(WIPED, removal?.wiped ?: false)
    }

    private fun commit(change: android.content.SharedPreferences.Editor.() -> Unit) {
        // commit, not apply: a removal must be on disk before the wipe starts (a process death in between resumes it).
        check(preferences.edit().apply(change).commit()) { "shell preferences could not be saved" }
    }

    private companion object {
        const val FILE = "sprout_shell"
        const val LINKED = "linked_once"
        const val LINKED_ACCOUNT = "linked_account"
        const val FIRST_PULL = "first_pull"
        const val PARENT_SETUP = "parent_setup_pending"
        const val REMOVAL = "removal"
        const val WIPED = "removal_wiped"
    }
}

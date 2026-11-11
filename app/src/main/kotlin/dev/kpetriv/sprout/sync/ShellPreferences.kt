package dev.kpetriv.sprout.sync

import android.content.Context
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.kpetriv.sprout.core.model.HouseholdId
import dev.kpetriv.sprout.core.model.UserId
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
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
 * blobs but not shared preferences. Holds no personal data: households and accounts are kept only as HMAC-SHA256
 * digests under a random per-install key kept in the same preferences ([digestOf]), used for equality checks, never
 * shown or sent; a wipe forgets them and replaces the key ([wipe]), so nothing links the household before it to the
 * one after.
 */
@Suppress("TooManyFunctions") // One getter and setter per stored value.
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

    /**
     * The digest stored for [householdId] (and [uid] when given): hex HMAC-SHA256 of the ids, NUL-separated, under
     * this install's digest key (generated on first use).
     */
    fun digestOf(householdId: HouseholdId, uid: UserId? = null): String

    /**
     * After a wipe: forgets the linked account, the first pull and the pending parent setup, and replaces the digest
     * key. Keeps [wasLinked] and the [removal] (the removed screen still needs it).
     */
    fun wipe()

    companion object {
        /** The digest [digestOf] computes, under [key]. */
        fun hmacDigest(key: ByteArray, householdId: HouseholdId, uid: UserId? = null): String {
            val text = listOfNotNull(householdId.value, uid?.value).joinToString("\u0000")
            val mac = Mac.getInstance(HMAC_ALGORITHM)
            mac.init(SecretKeySpec(key, HMAC_ALGORITHM))
            return mac.doFinal(text.encodeToByteArray()).joinToString("") { "%02x".format(it) }
        }

        /** Bytes of a digest key. */
        const val DIGEST_KEY_BYTES: Int = 32

        private const val HMAC_ALGORITHM = "HmacSHA256"
    }
}

@Singleton
@Suppress("TooManyFunctions") // One getter and setter per stored value.
class SharedShellPreferences @Inject constructor(@param:ApplicationContext private val context: Context) :
    ShellPreferences {
    private val preferences by lazy { context.getSharedPreferences(FILE, Context.MODE_PRIVATE) }
    private val random by lazy { SecureRandom() }

    override fun wasLinked(): Boolean = preferences.getBoolean(LINKED, false)

    override fun markLinked() {
        if (!wasLinked()) commit { putBoolean(LINKED, true) }
    }

    override fun linkedAccount(): String? = preferences.getString(LINKED_ACCOUNT, null)

    override fun setLinkedAccount(digest: String?) = commit { putString(LINKED_ACCOUNT, digest) }

    override fun firstPullDone(householdId: HouseholdId): Boolean =
        preferences.getString(FIRST_PULL, null) == digestOf(householdId)

    override fun recordFirstPull(householdId: HouseholdId) = commit { putString(FIRST_PULL, digestOf(householdId)) }

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

    override fun digestOf(householdId: HouseholdId, uid: UserId?): String =
        ShellPreferences.hmacDigest(digestKey(), householdId, uid)

    override fun wipe() = synchronized(this) {
        commit {
            remove(LINKED_ACCOUNT)
            remove(FIRST_PULL)
            remove(PARENT_SETUP)
            putString(DIGEST_KEY, newKey())
        }
    }

    /** This install's digest key, generated and stored once (a wipe replaces it). */
    private fun digestKey(): ByteArray = synchronized(this) {
        preferences.getString(DIGEST_KEY, null)?.let(::decodeKey) ?: run {
            val key = newKey()
            commit { putString(DIGEST_KEY, key) }
            checkNotNull(decodeKey(key))
        }
    }

    private fun newKey(): String = Base64.encodeToString(
        ByteArray(ShellPreferences.DIGEST_KEY_BYTES).also(random::nextBytes),
        Base64.NO_WRAP,
    )

    @Suppress("SwallowedException") // A malformed key is replaced; nothing to log.
    private fun decodeKey(text: String): ByteArray? = try {
        Base64.decode(text, Base64.NO_WRAP).takeIf { it.size == ShellPreferences.DIGEST_KEY_BYTES }
    } catch (malformed: IllegalArgumentException) {
        null
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
        const val DIGEST_KEY = "digest_key"
    }
}

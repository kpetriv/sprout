package dev.kpetriv.sprout.core.syncfirebase

import dev.kpetriv.sprout.core.model.HouseholdId
import dev.kpetriv.sprout.core.model.ID_PATTERN
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.Role
import dev.kpetriv.sprout.core.model.UserId

/** The Firebase Auth session as the app sees it. Never carries an e-mail address or a token. */
sealed interface AuthState {
    data object SignedOut : AuthState

    /**
     * A signed-in Firebase user: a parent (Google, or e-mail/password on the emulator) or an anonymous kid device.
     * [claims] are the custom claims set by Cloud Functions; empty until the user joins a household.
     */
    data class SignedIn(val uid: UserId, val isAnonymous: Boolean, val claims: Claims) : AuthState

    /**
     * The Firebase account is gone: deleted or disabled (`FirebaseAuthInvalidUserException` with `USER_NOT_FOUND` or
     * `USER_DISABLED` from an ID-token read). The household was deleted or, for a kid device, the device was revoked:
     * positive evidence for [dev.kpetriv.sprout.core.sync.SyncError.HouseholdGone] (M3 §6). A merely expired or
     * revoked session is not this: it signs in again.
     */
    data object UserGone : AuthState
}

/**
 * Custom claims set by Cloud Functions only (M3 §1): `{ hid, role, kidId? }`. A value that is missing or not a valid
 * id reads as null, so a malformed claim never grants anything.
 */
data class Claims(val hid: HouseholdId?, val role: Role?, val kidId: KidId?) {
    companion object {
        val NONE: Claims = Claims(hid = null, role = null, kidId = null)

        /** Parses the ID token's claims map (`GetTokenResult.claims`). */
        fun from(claims: Map<String, Any?>): Claims = Claims(
            hid = claims.id(HID)?.let(::HouseholdId),
            role = (claims[ROLE] as? String)?.let { name -> Role.entries.firstOrNull { it.name == name } },
            kidId = claims.id(KID_ID)?.let(::KidId),
        )

        private fun Map<String, Any?>.id(key: String): String? = (this[key] as? String)?.takeIf(ID_PATTERN::matches)

        private const val HID = "hid"
        private const val ROLE = "role"
        private const val KID_ID = "kidId"
    }
}

/** Why a sign-in or claims refresh failed. Codes only: never a message from Firebase, which may name the account. */
sealed class AuthError(val code: String) {
    /** No [FirebaseConfig.googleServerClientId]: Google sign-in needs a real Firebase project (D-M3-1). */
    data object GoogleNotConfigured : AuthError("google-not-configured")

    /** E-mail/password sign-in exists only against the Auth emulator in debug builds (D-M3-2). */
    data object NotAvailableInThisBuild : AuthError("not-available")

    /** The user dismissed the account picker. */
    data object Cancelled : AuthError("cancelled")

    /** No Google account on the device can sign in. */
    data object NoCredential : AuthError("no-credential")

    /** The credential was refused (wrong password, revoked or malformed Google ID token). */
    data object InvalidCredential : AuthError("invalid-credential")

    /** The account was disabled or deleted (a revoked kid device, a deleted household). */
    data object UserDisabled : AuthError("user-disabled")

    data object Network : AuthError("network")

    data object TooManyRequests : AuthError("too-many-requests")

    /** The operation needs a signed-in user. */
    data object SignedOut : AuthError("signed-out")

    data object Unknown : AuthError("unknown")
}

/** The failure of an [AuthError]-reporting call. The message is the code; no cause is attached (it may hold PII). */
class AuthException(val error: AuthError) : Exception(error.code)

package dev.kpetriv.sprout.feature.onboarding

import android.content.Context
import dev.kpetriv.sprout.core.crypto.DeviceRegistrationMaterial
import dev.kpetriv.sprout.core.database.Session
import dev.kpetriv.sprout.core.domainledger.Currency
import dev.kpetriv.sprout.core.model.DeviceId
import dev.kpetriv.sprout.core.model.HouseholdId
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.Role
import dev.kpetriv.sprout.core.model.UserId

/*
 * What onboarding needs from the rest of the app (M3 §1, §6). The app adapts these to core/sync-firebase
 * (FirebaseAuthSession, HouseholdFunctions), core/database (purge, household policy, sync) and feature/lock; tests use
 * fakes. Every failure is an [OnboardingException] with a fixed [OnboardingFailure]: nothing here carries a message
 * from Firebase, an e-mail address, a password or a link code.
 */

/** Why an onboarding step failed, coarse enough to show to a user. */
enum class OnboardingFailure {
    /** Offline, a timeout or server trouble: try again. */
    NETWORK,

    /** The e-mail/password pair was refused. */
    INVALID_CREDENTIAL,

    /** The Google account picker was dismissed. */
    CANCELLED,

    /** No Google account on this phone can sign in. */
    NO_GOOGLE_ACCOUNT,

    /** Google sign-in needs a real Firebase project (D-M3-1). */
    GOOGLE_NOT_CONFIGURED,

    /** The sign-in method does not exist in this build (D-M3-2). */
    NOT_AVAILABLE,

    /** Too many attempts: wait a while. */
    TOO_MANY_ATTEMPTS,

    /** The account was disabled or deleted. */
    ACCOUNT_DISABLED,

    /** The link code is wrong, expired or already used (the server does not say which, T6). */
    CODE_NOT_ACCEPTED,

    /** This account cannot do that here (for example a kid's account signing in as a parent). */
    NOT_ALLOWED,

    /** A parent revoked this phone while it waited for approval. */
    DEVICE_REJECTED,

    UNKNOWN,
}

/** The failure of a port call. The message is the [failure]'s name; no cause is attached. */
class OnboardingException(val failure: OnboardingFailure) : Exception(failure.name)

/** The signed-in Firebase user and the custom claims Cloud Functions set (empty before joining a household). */
data class AccountClaims(val uid: UserId, val householdId: HouseholdId?, val role: Role?, val kidId: KidId?)

/** Sign-in (core/sync-firebase `FirebaseAuthSession`). */
interface OnboardingAuth {
    /**
     * True in debug builds against the Auth emulator: parents sign in with e-mail and password instead of Google
     * (D-M3-2).
     */
    val usesEmailPassword: Boolean

    /** Google through Credential Manager; [activityContext] hosts the account picker. */
    suspend fun signInWithGoogle(activityContext: Context): Result<Unit>

    suspend fun signInWithEmailPassword(email: String, password: String): Result<Unit>

    /** A kid device signs in anonymously before redeeming its link code. */
    suspend fun signInAnonymously(): Result<Unit>

    /** Forces an ID token refresh and returns the user and its claims. */
    suspend fun refreshClaims(): Result<AccountClaims>

    suspend fun signOut()
}

/** A household created by this device: the device is its first ACTIVE parent device. */
data class RegisteredHousehold(val householdId: HouseholdId, val deviceId: DeviceId)

/** A second parent device, PENDING until an existing parent device approves it; [fingerprint] is what both show. */
data class PendingParentDevice(val deviceId: DeviceId, val fingerprint: String)

/** A kid device linked by a code. */
data class LinkedKidDevice(val householdId: HouseholdId, val kidId: KidId, val deviceId: DeviceId)

/** Which of this device's keys a registry lookup is about (`DeviceKeyStore`'s two-phase registration). */
enum class RegistrationKey {
    /** The active signing key. */
    CURRENT,

    /** The pending registration key, if any (a registration the server may have accepted before a process death). */
    PENDING,
}

/** A device's status in the household's server registry, as the token-less `deviceStatus` callable answers it. */
enum class RegistryStatus {
    ACTIVE,
    PENDING,
    REVOKED,

    /** Not registered in that household (or there is no such key). */
    UNKNOWN,
}

/** The household callables (core/sync-firebase `HouseholdFunctions`). */
interface RegistrationBackend {
    /** A fresh attestation challenge for the key about to be registered. */
    suspend fun beginDeviceRegistration(): Result<ByteArray>

    /**
     * The registry status of [key]'s device in [householdId], proven with that key's signature (`deviceStatus`). Used
     * before registering, so a key the registry already has ACTIVE is never replaced (audit M1).
     */
    suspend fun deviceStatus(householdId: HouseholdId, key: RegistrationKey): Result<RegistryStatus>

    suspend fun createHousehold(
        fiatCurrency: Currency.Fiat,
        key: DeviceRegistrationMaterial,
    ): Result<RegisteredHousehold>

    suspend fun registerParentDevice(key: DeviceRegistrationMaterial): Result<PendingParentDevice>

    suspend fun redeemLinkCode(code: LinkCodeInput, key: DeviceRegistrationMaterial): Result<LinkedKidDevice>
}

/** The local household: trial data, the household policy, sync, the session and the parent setup marker. */
@Suppress("TooManyFunctions") // One function per question onboarding asks of the local household.
interface OnboardingHousehold {
    /** The household currency the picker starts on (the locale's). */
    fun defaultCurrency(): Currency.Fiat

    /** True when the pre-linking trial household (`householdId == "local"`) has data on this device. */
    suspend fun hasTrialData(): Boolean

    /** Deletes the trial household's data (`PurgeLocalHouseholdUseCase`, ADR-0004 §2). */
    suspend fun purgeTrialData()

    /** Creates the household policy document with [fiatCurrency] (a new household, after its session is saved). */
    suspend fun ensureHouseholdPolicy(fiatCurrency: Currency.Fiat)

    /** One sync now; false when it failed. */
    suspend fun syncNow(): Boolean

    /**
     * This device's current key when the local registry mirror, which must belong to [uid] in [householdId], already
     * has it ACTIVE for [uid]: no new registration and no approval needed.
     */
    suspend fun activeDeviceOf(uid: UserId, householdId: HouseholdId): DeviceId?

    /**
     * Saves [session]. A real session whose account or household differs from the one the local rows belong to
     * clears those rows first (D-M3-47), so two households never mix on one phone.
     */
    suspend fun saveSession(session: Session)

    /** The parent's PIN and signing step for [householdId] has started; it survives a process death (review M4). */
    suspend fun startParentSetup(householdId: HouseholdId)

    /** The parent's PIN and signing step finished. */
    suspend fun finishParentSetup()

    /** True while [householdId]'s parent setup has started and not finished. */
    suspend fun parentSetupPending(householdId: HouseholdId): Boolean

    /**
     * Erases this phone's copy of the household (a phone that leaves onboarding after its sync pulled household data)
     * and signs out; the app then shows the erase screen and restarts.
     */
    suspend fun eraseLocalHousehold()
}

/**
 * Registers this device's parent authorisation key with the household's server-side registry (backend
 * `registerParentKey`, signed by the device key). The app calls it after `feature/lock`'s enrolment registered the key
 * locally; other devices learn the key only from that registry (D-M3-34). [attestationChain] is the key's Keystore
 * attestation (DER, leaf first), empty when it has none.
 */
fun interface ParentKeyRegistrar {
    suspend fun register(publicKey: ByteArray, attestationChain: List<ByteArray>): Result<Unit>
}

/** Fingerprint signing for a parent whose PIN already exists (the trial PIN), over `feature/lock`'s enrolment. */
interface ParentSigningSetup {
    /** True when a strong biometric is usable and no parent key is registered for the household yet. */
    suspend fun needsEnrolment(): Boolean

    /** Verifies [pin], creates the parent key and proves it with one fingerprint prompt. Zeroes [pin]. */
    suspend fun enrol(pin: CharArray): SigningSetupResult
}

/** How [ParentSigningSetup.enrol] ended. */
enum class SigningSetupResult {
    REGISTERED,

    /** No usable strong biometric (D-M2-1): money changes from this phone carry no parent signature. */
    UNAVAILABLE,
    WRONG_PIN,
    LOCKED_OUT,
    CANCELLED,
    FAILED,
}

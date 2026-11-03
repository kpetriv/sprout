package dev.kpetriv.sprout.core.crypto

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG

/**
 * Whether this device can hold and use the parent authentication key (ADR-0004): it needs a strong (Class 3)
 * biometric that is enrolled right now. Device credential never counts.
 *
 * Anything other than [STRONG_BIOMETRIC_AVAILABLE] means parent-only events cannot carry a `parentSignature`
 * on this device; what the household does then is decision D-M2-1 (docs/DECISIONS-PENDING.md).
 */
enum class ParentAuthAvailability {
    /** A strong biometric is enrolled and usable: the parent key can be created and used. */
    STRONG_BIOMETRIC_AVAILABLE,

    /** The hardware exists but no strong biometric is enrolled. The parent can fix this in system settings. */
    NOT_ENROLLED,

    /** No strong biometric hardware at all. */
    NO_HARDWARE,

    /**
     * Anything else: the sensor is temporarily unavailable, a security update is required, the status is unknown,
     * or the query itself failed. Treat it as "not available now" and check again later.
     */
    UNAVAILABLE_OTHER,
}

/** Reports [ParentAuthAvailability]. Cheap enough to call before every parent-key operation. */
fun interface ParentAuthAvailabilityChecker {
    fun availability(): ParentAuthAvailability
}

/** [ParentAuthAvailabilityChecker] over `androidx.biometric.BiometricManager.canAuthenticate(BIOMETRIC_STRONG)`. */
class BiometricParentAuthAvailabilityChecker(context: Context) : ParentAuthAvailabilityChecker {
    private val biometricManager = BiometricManager.from(context.applicationContext)

    // A system service failure here must never crash the caller: it only means "not available now".
    @Suppress("TooGenericExceptionCaught")
    override fun availability(): ParentAuthAvailability = try {
        availabilityFor(biometricManager.canAuthenticate(BIOMETRIC_STRONG))
    } catch (expected: RuntimeException) {
        ParentAuthAvailability.UNAVAILABLE_OTHER
    }

    internal companion object {
        /** Maps a `BiometricManager.canAuthenticate` status code. */
        fun availabilityFor(status: Int): ParentAuthAvailability = when (status) {
            BiometricManager.BIOMETRIC_SUCCESS -> ParentAuthAvailability.STRONG_BIOMETRIC_AVAILABLE
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> ParentAuthAvailability.NOT_ENROLLED
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> ParentAuthAvailability.NO_HARDWARE
            else -> ParentAuthAvailability.UNAVAILABLE_OTHER
        }
    }
}

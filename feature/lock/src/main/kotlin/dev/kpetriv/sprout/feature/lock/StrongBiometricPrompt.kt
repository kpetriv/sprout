package dev.kpetriv.sprout.feature.lock

import android.content.Context
import android.content.ContextWrapper
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricPrompt
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.withResumed
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.security.Signature
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The prompt was dismissed: the user cancelled or tapped the negative button, the system cancelled it, or it never
 * finished because its activity went away ([HOST_GONE]), did not reach the resumed state in time ([NOT_RESUMED]) or
 * the parent did not answer within the overall timeout ([TIMED_OUT]). Nothing was authorised; do nothing.
 */
class BiometricCancelledException(val errorCode: Int) : Exception("Biometric prompt cancelled ($errorCode)") {
    companion object {
        /** Not a `BiometricPrompt` code: the hosting activity was destroyed (for example by a rotation). */
        const val HOST_GONE = -2

        /** Not a `BiometricPrompt` code: the hosting activity was not resumed within [PromptTimeouts.resume]. */
        const val NOT_RESUMED = -3

        /** Not a `BiometricPrompt` code: no answer within [PromptTimeouts.overall]. */
        const val TIMED_OUT = -4
    }
}

/**
 * The prompt ended with an error other than a cancel, for example `ERROR_LOCKOUT` after too many unrecognised
 * fingers or `ERROR_HW_UNAVAILABLE`. [errorCode] is a `BiometricPrompt.ERROR_*` value.
 */
class BiometricErrorException(val errorCode: Int) : Exception("Biometric prompt failed ($errorCode)")

/** The texts of one prompt. */
internal data class PromptTexts(
    val title: String,
    val subtitle: String?,
    val negativeButton: String,
    /** True for money events, so face unlock cannot pass while the parent merely looks at the screen. */
    val confirmationRequired: Boolean,
)

/** How long a prompt may wait for its activity to resume, and how long it may stay up in total. */
internal data class PromptTimeouts(val resume: Duration, val overall: Duration) {
    companion object {
        val Default = PromptTimeouts(resume = 5.seconds, overall = 2.minutes)
    }
}

/**
 * Shows a `BiometricPrompt` that accepts **only** a strong (Class 3) biometric, never the device credential
 * (ADR-0004: a kid may know the screen lock), bound to [signature] through a `CryptoObject`. Returns the
 * authenticated signature from the result. Runs on the main thread; cancelling the coroutine dismisses the prompt.
 *
 * Never hangs: it waits at most [PromptTimeouts.resume] for the activity to be resumed (`BiometricPrompt` needs a
 * resumed host to show its fragment), dismisses the prompt after [PromptTimeouts.overall], and ends with
 * [BiometricCancelledException] if the activity is destroyed (a rotation, for example) before an answer.
 *
 * Throws [BiometricCancelledException] or [BiometricErrorException]. Needs a [FragmentActivity] (a
 * `BiometricPrompt` requirement).
 */
internal suspend fun FragmentActivity.authenticateStrong(
    texts: PromptTexts,
    signature: Signature,
    timeouts: PromptTimeouts = PromptTimeouts.Default,
): Signature = withContext(Dispatchers.Main.immediate) {
    val activity = this@authenticateStrong
    awaitStrongBiometric(activity.lifecycle, timeouts) { callbacks ->
        val prompt = BiometricPrompt(activity, activity.mainExecutor, callbacks.toAuthenticationCallback())
        prompt.authenticate(texts.toPromptInfo(), BiometricPrompt.CryptoObject(signature))
        PromptHandle { prompt.cancelAuthentication() }
    }
}

/** The answer of one shown prompt, decoupled from `BiometricPrompt` so the lifecycle handling is unit-testable. */
internal interface StrongPromptCallbacks {
    /** Authenticated; [signature] is the `CryptoObject`'s signature, or null if the result had none. */
    fun onSucceeded(signature: Signature?)

    /** A terminal `BiometricPrompt.ERROR_*` code. */
    fun onError(errorCode: Int)
}

/** Dismisses a shown prompt. */
internal fun interface PromptHandle {
    fun cancel()
}

/**
 * The lifecycle and timeout part of [authenticateStrong]. Call it on the main thread. [show] displays the prompt
 * and reports to the callbacks; it runs only once [lifecycle] is resumed, and its handle is cancelled whenever the
 * wait ends without an answer (timeout, destroyed host, coroutine cancelled).
 */
internal suspend fun awaitStrongBiometric(
    lifecycle: Lifecycle,
    timeouts: PromptTimeouts,
    show: (StrongPromptCallbacks) -> PromptHandle,
): Signature {
    awaitResumed(lifecycle, timeouts.resume)
    return withTimeoutOrNull(timeouts.overall) { showAndAwait(lifecycle, show) }
        ?: throw BiometricCancelledException(BiometricCancelledException.TIMED_OUT)
}

private suspend fun awaitResumed(lifecycle: Lifecycle, timeout: Duration) {
    val resumed = lifecycle.currentState != Lifecycle.State.DESTROYED && try {
        withTimeoutOrNull(timeout) { lifecycle.withResumed { true } } == true
    } catch (e: CancellationException) {
        // withResumed throws LifecycleDestroyedException (a CancellationException) when the host is destroyed.
        if (lifecycle.currentState != Lifecycle.State.DESTROYED) throw e
        false
    }
    if (!resumed) {
        val gone = lifecycle.currentState == Lifecycle.State.DESTROYED
        throw BiometricCancelledException(
            if (gone) BiometricCancelledException.HOST_GONE else BiometricCancelledException.NOT_RESUMED,
        )
    }
}

private suspend fun showAndAwait(lifecycle: Lifecycle, show: (StrongPromptCallbacks) -> PromptHandle): Signature {
    var handle: PromptHandle? = null
    var observer: LifecycleEventObserver? = null
    var answered = false
    try {
        return suspendCancellableCoroutine { continuation ->
            val callbacks = object : StrongPromptCallbacks {
                override fun onSucceeded(signature: Signature?) {
                    if (!continuation.isActive) return
                    answered = true
                    if (signature == null) {
                        continuation.resumeWithException(BiometricErrorException(NO_CRYPTO_OBJECT))
                    } else {
                        continuation.resume(signature)
                    }
                }

                override fun onError(errorCode: Int) {
                    if (!continuation.isActive) return
                    answered = true
                    val error = if (errorCode in CANCEL_CODES) {
                        BiometricCancelledException(errorCode)
                    } else {
                        BiometricErrorException(errorCode)
                    }
                    continuation.resumeWithException(error)
                }
            }
            observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_DESTROY && continuation.isActive) {
                    continuation.resumeWithException(BiometricCancelledException(BiometricCancelledException.HOST_GONE))
                }
            }.also(lifecycle::addObserver)
            handle = show(callbacks)
        }
    } finally {
        // Runs on the caller's (main) dispatcher, also after a timeout or cancellation.
        observer?.let(lifecycle::removeObserver)
        if (!answered) handle?.cancel()
    }
}

private fun StrongPromptCallbacks.toAuthenticationCallback() = object : BiometricPrompt.AuthenticationCallback() {
    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) =
        onSucceeded(result.cryptoObject?.signature)

    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = onError(errorCode)
    // onAuthenticationFailed (finger not recognised) is not terminal: the prompt stays up.
}

private fun PromptTexts.toPromptInfo(): BiometricPrompt.PromptInfo = BiometricPrompt.PromptInfo.Builder()
    .setTitle(title)
    .apply { subtitle?.let(::setSubtitle) }
    .setNegativeButtonText(negativeButton)
    .setAllowedAuthenticators(BIOMETRIC_STRONG)
    .setConfirmationRequired(confirmationRequired)
    .build()

private val CANCEL_CODES = setOf(
    BiometricPrompt.ERROR_USER_CANCELED,
    BiometricPrompt.ERROR_NEGATIVE_BUTTON,
    BiometricPrompt.ERROR_CANCELED,
)

/** Not a `BiometricPrompt` code: a success without the `CryptoObject` we passed. */
private const val NO_CRYPTO_OBJECT = -1

/** The [FragmentActivity] this context belongs to, or null (for example under a plain `ComponentActivity`). */
internal fun Context.findFragmentActivity(): FragmentActivity? {
    var current: Context? = this
    while (current != null) {
        if (current is FragmentActivity) return current
        current = (current as? ContextWrapper)?.baseContext
    }
    return null
}

package dev.kpetriv.sprout.feature.lock

import android.security.keystore.KeyPermanentlyInvalidatedException
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.fragment.app.FragmentActivity
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import dev.kpetriv.sprout.core.crypto.ParentAuthAvailability
import dev.kpetriv.sprout.core.crypto.ParentAuthUnavailableException
import dev.kpetriv.sprout.core.crypto.ParentAuthorizer
import dev.kpetriv.sprout.core.crypto.ParentKeyInvalidatedException
import dev.kpetriv.sprout.core.crypto.ParentKeyMismatchException
import dev.kpetriv.sprout.core.crypto.ParentKeyNotRegisteredException
import dev.kpetriv.sprout.core.crypto.ParentSigner
import dev.kpetriv.sprout.core.crypto.PublicKeyBytes
import dev.kpetriv.sprout.core.crypto.verifyParentSignature
import dev.kpetriv.sprout.core.domainledger.ParentAuthorization
import dev.kpetriv.sprout.core.domainledger.ParentAuthorizationRequester
import java.lang.ref.WeakReference
import java.security.GeneralSecurityException
import java.security.Signature
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/*
 * Per-event parent authorisation (ADR-0004 section 1): one strong-biometric prompt per parent-only ledger event,
 * behind `core/domain-ledger`'s [ParentAuthorizationRequester]. Data-layer use cases take a provider
 * (`{ requester.request(reason) }`) and pass its result to `LedgerWriter.appendAsParent`.
 *
 * [ParentAuthorizationRequester.request] here never creates a parent key and returns:
 * - a single-use [ParentAuthorization] after a successful prompt, only when a parent key is registered for the
 *   household ([ParentKeyRegistry]) and this device's key byte-equals it. Its `sign` also verifies the signature
 *   against the registered key before returning it;
 * - an [UnavailableParentAuthorization] when this device has no usable strong biometric, or when no parent key is
 *   registered. Its `sign` throws [ParentAuthUnavailableException] (respectively its subclass
 *   [ParentKeyNotRegisteredException]); the use cases fall back per the household policy (D-M2-1: append unsigned
 *   with `LedgerWriter.append` when signatures are off, refuse otherwise).
 *
 * and throws [BiometricCancelledException] (parent dismissed the prompt, it timed out, or its activity went away: do
 * nothing), [BiometricErrorException], [ParentKeyInvalidatedException] (biometric enrolment changed) or its subclass
 * [ParentKeyMismatchException] (this device's key is missing or differs from the registered one). Both key
 * exceptions can also come from `sign`. Use cases map both to "Fingerprint changed: renew the signing key in
 * Settings" ([ParentKeyRenewal]).
 *
 * Events signed by a key that is not the registered one must be treated as unsigned by the data layer.
 *
 * Bound in Hilt to [HostedParentAuthorizationRequester] (a singleton), which prompts over the activity that hosts
 * [ParentAuthorizationHost]. Composables can also get one bound to their own activity with
 * [rememberParentAuthorizationLauncher].
 */

/**
 * This device cannot produce a parent signature the household would accept: [sign] always throws
 * [ParentAuthUnavailableException], or [ParentKeyNotRegisteredException] when [keyRegistered] is false (a usable
 * biometric, but no parent key registered).
 */
class UnavailableParentAuthorization(val availability: ParentAuthAvailability, val keyRegistered: Boolean = true) :
    ParentAuthorization {
    override fun sign(payload: ByteArray): ByteArray = throw if (keyRegistered) {
        ParentAuthUnavailableException(availability)
    } else {
        ParentKeyNotRegisteredException(availability)
    }
}

/**
 * Signs exactly once with the prompt-authenticated [signature] through [ParentSigner], and only returns a signature
 * that verifies against the [registeredKey]. A key invalidated mid-flight surfaces as [ParentKeyInvalidatedException].
 */
internal class SingleUseParentAuthorization(
    private val signature: Signature,
    private val signer: ParentSigner,
    private val registeredKey: PublicKeyBytes,
) : ParentAuthorization {
    private val used = AtomicBoolean(false)

    override fun sign(payload: ByteArray): ByteArray {
        check(used.compareAndSet(false, true)) { "A parent authorisation signs one event; prompt again" }
        val signed = try {
            signer.sign(signature, payload)
        } catch (e: GeneralSecurityException) {
            if (e.isKeyInvalidation()) throw ParentKeyInvalidatedException(e) else throw e
        }
        if (!verifyParentSignature(registeredKey, payload, signed)) throw ParentKeyMismatchException()
        return signed
    }

    private fun GeneralSecurityException.isKeyInvalidation(): Boolean =
        generateSequence<Throwable>(this) { it.cause }.take(MAX_CAUSES).any { it is KeyPermanentlyInvalidatedException }

    private companion object {
        const val MAX_CAUSES = 8
    }
}

/**
 * Shows the per-event parent prompt over a given activity. Use it through the activity-bound
 * [ParentAuthorizationRequester] from [rememberParentAuthorizationLauncher], or inject [ParentAuthorizationRequester]
 * directly.
 */
@Singleton
class ParentAuthorizationPrompt @Inject constructor(
    private val authorizer: ParentAuthorizer,
    private val registry: ParentKeyRegistry,
    private val signer: ParentSigner,
) {
    /** See [ParentAuthorizationRequester.request]. [activity] must be resumed or about to be. */
    suspend fun authorize(activity: FragmentActivity, description: String? = null): ParentAuthorization {
        val texts = PromptTexts(
            title = activity.getString(R.string.feature_lock_authorize_title),
            subtitle = description,
            negativeButton = activity.getString(R.string.feature_lock_cancel),
            confirmationRequired = true,
        )
        return authorizeWith { signature -> activity.authenticateStrong(texts, signature) }
    }

    /**
     * The prompt-independent part of [authorize]; [prompt] returns the authenticated signature. Only reads the
     * parent key; throws [ParentKeyMismatchException] before any prompt when this device's key is missing or is
     * not the registered one, and [ParentKeyInvalidatedException] when it was invalidated.
     */
    internal suspend fun authorizeWith(prompt: suspend (Signature) -> Signature): ParentAuthorization {
        val availability = authorizer.availability()
        val available = availability == ParentAuthAvailability.STRONG_BIOMETRIC_AVAILABLE
        val registered = if (available) registry.registeredParentKey() else null
        return when {
            !available -> UnavailableParentAuthorization(availability)
            registered == null -> UnavailableParentAuthorization(availability, keyRegistered = false)
            authorizer.existingParentPublicKey() != registered -> throw ParentKeyMismatchException()
            else -> SingleUseParentAuthorization(prompt(initSignature()), signer, registered)
        }
    }

    private fun initSignature(): Signature = try {
        authorizer.initSignature()
    } catch (expected: IllegalStateException) {
        // The key disappeared after the comparison.
        throw ParentKeyMismatchException()
    }
}

/**
 * The [FragmentActivity] that per-event, unlock-independent prompts (parent authorisation, key proof) appear over:
 * the one hosting [ParentAuthorizationHost]. Held weakly and only while it is in composition.
 */
@Singleton
class ParentPromptHost @Inject constructor() {
    private val host = AtomicReference<WeakReference<FragmentActivity>?>(null)

    /** The current host activity, or null when none is attached. */
    fun current(): FragmentActivity? = host.get()?.get()

    internal fun attach(activity: FragmentActivity) = host.set(WeakReference(activity))

    internal fun detach(activity: FragmentActivity) {
        val current = host.get()
        if (current?.get() === activity) host.compareAndSet(current, null)
    }
}

/**
 * The Hilt-bound [ParentKeyProofPrompter]: the enrolment and renewal proof prompt over the [ParentPromptHost]
 * activity. Throws [IllegalStateException] when no host is attached.
 */
class HostedParentKeyProofPrompter @Inject constructor(private val host: ParentPromptHost) : ParentKeyProofPrompter {
    override suspend fun prompt(signature: Signature): Signature {
        val activity = checkNotNull(host.current()) { "No ParentAuthorizationHost in composition" }
        val texts = PromptTexts(
            title = activity.getString(R.string.feature_lock_key_proof_title),
            subtitle = activity.getString(R.string.feature_lock_key_proof_subtitle),
            negativeButton = activity.getString(R.string.feature_lock_cancel),
            confirmationRequired = true,
        )
        return activity.authenticateStrong(texts, signature)
    }
}

/**
 * The Hilt-bound [ParentAuthorizationRequester]: prompts over the [FragmentActivity] currently hosting
 * [ParentAuthorizationHost]. Holds the activity weakly and only while it is in composition. A process-wide singleton,
 * so every ViewModel that injects [ParentAuthorizationRequester] prompts over whichever activity is current.
 * [request] throws [IllegalStateException] when no host is attached.
 */
@Singleton
class HostedParentAuthorizationRequester @Inject constructor(
    private val prompt: ParentAuthorizationPrompt,
    private val host: ParentPromptHost,
) : ParentAuthorizationRequester {
    override suspend fun request(reason: String): ParentAuthorization {
        val activity = checkNotNull(host.current()) { "No ParentAuthorizationHost in composition" }
        return prompt.authorize(activity, reason)
    }
}

/**
 * Registers the current activity as the [ParentPromptHost], so the injected [HostedParentAuthorizationRequester]
 * and [HostedParentKeyProofPrompter] can prompt.
 * Call once near the root of `MainActivity`'s content. `MainActivity` must extend [FragmentActivity] (a
 * `BiometricPrompt` requirement); under any other activity this does nothing and injected requesters throw.
 */
@Composable
fun ParentAuthorizationHost() {
    val context = LocalContext.current
    val activity = remember(context) { context.findFragmentActivity() } ?: return
    val host = remember(context) { lockEntryPoint(context).parentPromptHost() }
    DisposableEffect(activity, host) {
        host.attach(activity)
        onDispose { host.detach(activity) }
    }
}

/**
 * A [ParentAuthorizationRequester] bound to this composable's [FragmentActivity], to pass to a ViewModel in an
 * event (`onEvent(Approve(id, launcher))`), or null when the activity is not a [FragmentActivity].
 */
@Composable
fun rememberParentAuthorizationLauncher(): ParentAuthorizationRequester? {
    val context = LocalContext.current
    return remember(context) {
        val activity = context.findFragmentActivity() ?: return@remember null
        val prompt = lockEntryPoint(context).parentAuthorizationPrompt()
        ParentAuthorizationRequester { reason -> prompt.authorize(activity, reason) }
    }
}

/** Lets this module's composables reach its singletons without a ViewModel. Not for use outside feature/lock. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface LockEntryPoint {
    fun parentAuthorizationPrompt(): ParentAuthorizationPrompt

    fun parentPromptHost(): ParentPromptHost

    fun parentSession(): ParentSession
}

internal fun lockEntryPoint(context: android.content.Context): LockEntryPoint =
    EntryPointAccessors.fromApplication(context.applicationContext, LockEntryPoint::class.java)

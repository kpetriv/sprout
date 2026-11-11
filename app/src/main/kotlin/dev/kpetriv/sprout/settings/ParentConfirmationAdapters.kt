package dev.kpetriv.sprout.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kpetriv.sprout.R
import dev.kpetriv.sprout.core.crypto.LockoutDecision
import dev.kpetriv.sprout.core.crypto.ParentKeyInvalidatedException
import dev.kpetriv.sprout.core.domainledger.ParentAuthorization
import dev.kpetriv.sprout.di.ApplicationScope
import dev.kpetriv.sprout.feature.lock.BiometricCancelledException
import dev.kpetriv.sprout.feature.lock.ParentAuthorizationPrompt
import dev.kpetriv.sprout.feature.lock.ParentPromptHost
import dev.kpetriv.sprout.feature.lock.ParentSession
import dev.kpetriv.sprout.feature.lock.PinEntryError
import dev.kpetriv.sprout.feature.lock.PinEntryEvent
import dev.kpetriv.sprout.feature.lock.PinEntryScreen
import dev.kpetriv.sprout.feature.lock.PinEntryUiState
import dev.kpetriv.sprout.feature.lock.PinRules
import dev.kpetriv.sprout.feature.lock.PinUnlockResult
import dev.kpetriv.sprout.feature.lock.UnavailableParentAuthorization
import dev.kpetriv.sprout.feature.settings.ConfirmationOutcome
import dev.kpetriv.sprout.feature.settings.ConfirmationPurpose
import dev.kpetriv.sprout.feature.settings.ParentConfirmation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Settings' [ParentConfirmation] (M7 §2, §3, D-M7-17, D-M7-21): the parent proves presence again before the readable
 * export, "Delete chat history now" and the household policy writes.
 *
 * Where this device's parent key can sign for the household, that is one strong-biometric prompt over the
 * [ParentPromptHost] activity ([ParentAuthorizationPrompt], never the device credential, ADR-0004): the authorisation
 * signs a fresh, domain-separated challenge and verifies it against the registered key; the signature is then
 * dropped. Where it cannot (no strong biometric, no registered key, or the fingerprints changed), the parent PIN
 * through [PinConfirmationHost] under the PIN lockout. A dismissed prompt is [ConfirmationOutcome.CANCELLED]; no host
 * activity, a lockout or any failure is [ConfirmationOutcome.FAILED]. Nothing is logged.
 */
class PromptParentConfirmation internal constructor(
    /** The strong-biometric authorisation for a purpose, or null when no host activity can show the prompt. */
    private val authorize: suspend (ConfirmationPurpose) -> ParentAuthorization?,
    /** The parent PIN fallback ([PinConfirmationHost.confirm]). */
    private val pinFallback: suspend (ConfirmationPurpose) -> ConfirmationOutcome,
) : ParentConfirmation {
    @Inject
    constructor(host: ParentPromptHost, prompt: ParentAuthorizationPrompt, pins: PinConfirmationHost) : this(
        authorize = { purpose ->
            host.current()?.let { activity -> prompt.authorize(activity, activity.getString(purpose.reason())) }
        },
        pinFallback = pins::confirm,
    )

    @Suppress("TooGenericExceptionCaught", "SwallowedException") // Any other failure: nothing is confirmed.
    override suspend fun confirm(purpose: ConfirmationPurpose): ConfirmationOutcome = try {
        when (val authorization = authorize(purpose)) {
            null -> ConfirmationOutcome.FAILED

            is UnavailableParentAuthorization -> pinFallback(purpose)

            else -> {
                authorization.sign(challenge(purpose))
                ConfirmationOutcome.CONFIRMED
            }
        }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (cancelled: BiometricCancelledException) {
        ConfirmationOutcome.CANCELLED
    } catch (changed: ParentKeyInvalidatedException) {
        // Fingerprints changed (or the key is not the registered one): the PIN still proves the parent.
        pinFallback(purpose)
    } catch (failure: Exception) {
        ConfirmationOutcome.FAILED
    }

    private fun challenge(purpose: ConfirmationPurpose): ByteArray =
        "$CHALLENGE_PREFIX${purpose.name}:".encodeToByteArray() + ByteArray(NONCE_SIZE).also(random::nextBytes)

    private companion object {
        /** Domain separation: a confirmation signature is never a valid ledger or unlock signature. */
        const val CHALLENGE_PREFIX = "sprout-parent-confirmation-v1:"
        const val NONCE_SIZE = 32
        val random = SecureRandom()
    }
}

/** The prompt's subtitle for [this] purpose. */
internal fun ConfirmationPurpose.reason(): Int = when (this) {
    ConfirmationPurpose.EXPORT_HOUSEHOLD -> R.string.confirm_reason_export
    ConfirmationPurpose.DELETE_CHAT_HISTORY -> R.string.confirm_reason_delete_chat
    ConfirmationPurpose.CHANGE_CHAT_RETENTION -> R.string.confirm_reason_retention
    ConfirmationPurpose.CHANGE_GAME_CAP -> R.string.confirm_reason_game_cap
    ConfirmationPurpose.RESTORE_HOUSEHOLD_SETTINGS -> R.string.confirm_reason_restore_settings
}

/**
 * The parent PIN as [PromptParentConfirmation]'s fallback: [confirm] shows [PinConfirmationDialog] (hosted at the root
 * of the parent area) and waits for the answer. The PIN is checked by `feature/lock`'s [ParentSession.unlockWithPin]
 * under the same lockout as an unlock (a wrong PIN counts as a failed attempt; the area is already unlocked, so the
 * session state does not change). A wrong PIN may be retried while the lockout allows; a lockout, or an unreadable
 * PIN store, ends with [ConfirmationOutcome.FAILED]. The typed digits live in one array, zeroed after every attempt
 * and when the dialog closes; [state] only counts them. Without a hosted dialog [confirm] fails at once.
 */
@Singleton
class PinConfirmationHost internal constructor(
    private val scope: CoroutineScope,
    private val unlock: suspend (CharArray) -> PinUnlockResult,
) {
    @Inject
    constructor(@ApplicationScope scope: CoroutineScope, session: ParentSession) : this(scope, session::unlockWithPin)

    private val mutex = Mutex()
    private val hosts = AtomicInteger(0)
    private val digits = CharArray(PinRules.LENGTH)
    private var entered = 0
    private var pending: CompletableDeferred<ConfirmationOutcome>? = null
    private val mutableRequest = MutableStateFlow<ConfirmationPurpose?>(null)
    private val mutableState = MutableStateFlow(PinEntryUiState())

    /** The confirmation waiting for the PIN, or null. */
    val request: StateFlow<ConfirmationPurpose?> = mutableRequest.asStateFlow()

    /** What the dialog shows. */
    val state: StateFlow<PinEntryUiState> = mutableState.asStateFlow()

    suspend fun confirm(purpose: ConfirmationPurpose): ConfirmationOutcome {
        if (hosts.get() == 0) return ConfirmationOutcome.FAILED
        return mutex.withLock {
            val answer = CompletableDeferred<ConfirmationOutcome>()
            synchronized(this) {
                clear()
                mutableState.value = PinEntryUiState()
                pending = answer
            }
            mutableRequest.value = purpose
            try {
                answer.await()
            } finally {
                synchronized(this) {
                    pending = null
                    clear()
                }
                mutableRequest.value = null
            }
        }
    }

    /** The dialog's keypad. */
    fun onEvent(event: PinEntryEvent) {
        when (event) {
            is PinEntryEvent.Digit -> digit(event.digit)

            PinEntryEvent.Backspace -> synchronized(this) {
                if (entered > 0 && !mutableState.value.isVerifying) {
                    digits[--entered] = ZERO
                    mutableState.update { it.copy(enteredDigits = entered, error = null) }
                }
            }

            // No fingerprint or key renewal from here: the biometric prompt was the first choice.
            is PinEntryEvent.UseBiometric, PinEntryEvent.RenewSigningKey, PinEntryEvent.Shown -> Unit
        }
    }

    /** The dialog was dismissed (back or outside). */
    fun dismiss() = finish(ConfirmationOutcome.CANCELLED)

    internal fun attach() {
        hosts.incrementAndGet()
    }

    internal fun detach() {
        // A confirmation shown in a host that went away is cancelled, so the caller never waits forever.
        if (hosts.decrementAndGet() == 0) finish(ConfirmationOutcome.CANCELLED)
    }

    private fun digit(digit: Char) {
        val pin = synchronized(this) {
            if (pending == null || mutableState.value.isVerifying || entered >= digits.size) return
            digits[entered++] = digit
            mutableState.update { it.copy(enteredDigits = entered, error = null) }
            if (entered < digits.size) return
            mutableState.update { it.copy(isVerifying = true) }
            digits.copyOf().also { clear() }
        }
        scope.launch { verify(pin) }
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException") // Any failure: not confirmed.
    private suspend fun verify(pin: CharArray) {
        val result = try {
            unlock(pin)
        } catch (cancellation: CancellationException) {
            pin.fill(ZERO)
            throw cancellation
        } catch (failure: Exception) {
            PinUnlockResult.Unavailable
        } finally {
            pin.fill(ZERO)
        }
        when (result) {
            PinUnlockResult.Unlocked -> finish(ConfirmationOutcome.CONFIRMED)

            is PinUnlockResult.WrongPin -> if (result.next == LockoutDecision.Allowed) {
                mutableState.update {
                    PinEntryUiState(error = PinEntryError.WRONG_PIN, wrongAttempts = it.wrongAttempts + 1)
                }
            } else {
                finish(ConfirmationOutcome.FAILED)
            }

            is PinUnlockResult.LockedOut, PinUnlockResult.SetupRequired, PinUnlockResult.Unavailable ->
                finish(ConfirmationOutcome.FAILED)
        }
    }

    private fun finish(outcome: ConfirmationOutcome) {
        synchronized(this) {
            clear()
            mutableState.value = PinEntryUiState()
            pending
        }?.complete(outcome)
    }

    private fun clear() {
        digits.fill(ZERO)
        entered = 0
    }

    private companion object {
        const val ZERO = '\u0000'
    }
}

/** Hands the [PinConfirmationHost] singleton to [PinConfirmationDialog]. */
@HiltViewModel
class PinConfirmationViewModel @Inject constructor(val host: PinConfirmationHost) : ViewModel()

/**
 * Shows [PinConfirmationHost]'s PIN entry while a confirmation waits for it; call once at the root of the parent area.
 * The PIN screen is `feature/lock`'s own (secure window included).
 */
@Composable
fun PinConfirmationDialog(host: PinConfirmationHost = hiltViewModel<PinConfirmationViewModel>().host) {
    DisposableEffect(host) {
        host.attach()
        onDispose { host.detach() }
    }
    val request by host.request.collectAsStateWithLifecycle()
    val state by host.state.collectAsStateWithLifecycle()
    if (request != null) {
        Dialog(
            onDismissRequest = host::dismiss,
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            PinEntryScreen(state = state, onEvent = host::onEvent)
        }
    }
}

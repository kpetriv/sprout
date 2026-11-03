package dev.kpetriv.sprout.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * "Link a device" for one kid: a single-use code, shown in groups of four with its 10-minute countdown. [code] is
 * held only here and on screen; [toString] never shows it (T6).
 *
 * @property remainingSeconds whole seconds until the code expires; 0 once [expired].
 */
data class LinkDeviceUiState(
    val kidName: String = "",
    val loading: Boolean = false,
    val code: String? = null,
    val remainingSeconds: Long = 0,
    val failure: AdminFailure? = null,
) {
    val expired: Boolean get() = code != null && remainingSeconds <= 0

    /** "K7QM 2X9P". */
    val groupedCode: String? get() = code?.chunked(GROUP)?.joinToString(" ")

    override fun toString(): String =
        "LinkDeviceUiState(loading=$loading, hasCode=${code != null}, remainingSeconds=$remainingSeconds, " +
            "failure=$failure)"

    private companion object {
        const val GROUP = 4
    }
}

sealed interface LinkDeviceEvent {
    /** Show a code for [kidId] (asks the server for one). */
    data class Start(val kidId: KidId, val kidName: String) : LinkDeviceEvent {
        override fun toString(): String = "Start(kidId=$kidId)"
    }

    /** A new code after expiry or a failure. */
    data object NewCode : LinkDeviceEvent
}

@HiltViewModel
class LinkDeviceViewModel @Inject constructor(private val linking: KidLinking, private val clock: Clock) :
    ViewModel() {
    private val mutableState = MutableStateFlow(LinkDeviceUiState())
    val state: StateFlow<LinkDeviceUiState> = mutableState.asStateFlow()

    private var kidId: KidId? = null
    private var job: Job? = null

    fun onEvent(event: LinkDeviceEvent) {
        when (event) {
            is LinkDeviceEvent.Start -> if (kidId != event.kidId) {
                kidId = event.kidId
                mutableState.value = LinkDeviceUiState(kidName = event.kidName)
                fetch()
            }

            LinkDeviceEvent.NewCode -> fetch()
        }
    }

    private fun fetch() {
        val kid = kidId ?: return
        if (mutableState.value.loading) return
        job?.cancel()
        mutableState.update { it.copy(loading = true, code = null, failure = null, remainingSeconds = 0) }
        job = viewModelScope.launch {
            val result = try {
                linking.createLinkCode(kid)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (@Suppress("TooGenericExceptionCaught") failure: Exception) {
                Result.failure(failure)
            }
            val issued = result.getOrElse { failure ->
                val code = (failure as? AdminException)?.failure ?: AdminFailure.FAILED
                mutableState.update { it.copy(loading = false, failure = code) }
                return@launch
            }
            mutableState.update { it.copy(loading = false, code = issued.code, remainingSeconds = remaining(issued)) }
            while (remaining(issued) > 0) {
                delay(TICK_MS)
                mutableState.update { it.copy(remainingSeconds = remaining(issued)) }
            }
        }
    }

    private fun remaining(code: IssuedLinkCode): Long =
        ((code.expiresAt - clock.now()).inWholeMilliseconds + MS_PER_SECOND - 1).div(MS_PER_SECOND).coerceAtLeast(0)

    private companion object {
        const val TICK_MS = 1_000L
        const val MS_PER_SECOND = 1_000L
    }
}

package dev.kpetriv.sprout.core.ai

import kotlinx.coroutines.delay
import kotlin.reflect.KClass
import kotlin.time.Duration

/** A [PromptModel] that answers from [answer] after [latency], and records the prompts it was given. */
internal class FakePromptModel(
    var status: AiStatus = AiStatus.AVAILABLE,
    var latency: Duration = Duration.ZERO,
    var downloadAccepted: Boolean = true,
    var answer: (PromptRequest) -> PromptResult<Any> = { PromptResult.Failed(AiFailure.ERROR) },
) : PromptModel {
    val requests = mutableListOf<PromptRequest>()
    var downloads = 0

    override suspend fun status(): AiStatus = status

    override suspend fun download(): Boolean {
        downloads++
        return downloadAccepted
    }

    override suspend fun <T : Any> generate(request: PromptRequest, output: KClass<T>): PromptResult<T> {
        requests += request
        delay(latency)
        return when (val result = answer(request)) {
            is PromptResult.Success -> {
                check(output.isInstance(result.value)) { "asked for ${output.simpleName}" }
                @Suppress("UNCHECKED_CAST")
                PromptResult.Success(result.value as T)
            }

            PromptResult.Busy -> PromptResult.Busy

            PromptResult.Unavailable -> PromptResult.Unavailable

            is PromptResult.Failed -> result
        }
    }
}

/** [AiPreferences] in memory. */
internal class InMemoryAiPreferences(initial: AiSettings = AiSettings()) : AiPreferences {
    private val state = kotlinx.coroutines.flow.MutableStateFlow(initial)
    override val settings: kotlinx.coroutines.flow.Flow<AiSettings> = state

    override suspend fun setEnabled(enabled: Boolean) {
        state.value = state.value.copy(enabled = enabled)
    }

    override suspend fun setDownloadConsent(consent: Boolean) {
        state.value = state.value.copy(downloadConsent = consent)
    }
}

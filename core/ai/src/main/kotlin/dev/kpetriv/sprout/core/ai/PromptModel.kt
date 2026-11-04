package dev.kpetriv.sprout.core.ai

import kotlin.reflect.KClass

/**
 * The on-device model behind [GeminiNanoAiGateway]: a thin seam over the ML Kit GenAI Prompt API
 * ([MlKitPromptModel]) so the gateway's logic runs in JVM tests with a fake. Internal: only [AiGateway] (the
 * router) is bound outside `core/ai`, so nothing reaches the model without the router's switch and consent gates
 * (audit L6).
 */
internal interface PromptModel {
    /** AICore's feature status for the Prompt API. Never throws. */
    suspend fun status(): AiStatus

    /** Asks AICore to download the model (the system downloads it, usually on Wi-Fi). True when accepted or there. */
    suspend fun download(): Boolean

    /**
     * Runs [request] with structured output: the answer is parsed into [output] (a `@Generable` class). Never throws
     * except for cancellation.
     */
    suspend fun <T : Any> generate(request: PromptRequest, output: KClass<T>): PromptResult<T>
}

/**
 * One prompt. [instructions] go in the system instruction; [input] is the user turn, in which every piece of user
 * text is a JSON string literal ([PromptText.json]). [toString] never shows either.
 */
internal data class PromptRequest(
    val instructions: String,
    val input: String,
    val temperature: Float,
    val topK: Int,
    val maxOutputTokens: Int,
    val seed: Int = 0,
) {
    override fun toString(): String =
        "PromptRequest(temperature=$temperature, topK=$topK, maxOutputTokens=$maxOutputTokens, seed=$seed)"
}

/** What [PromptModel.generate] returned. */
internal sealed interface PromptResult<out T> {
    data class Success<T>(val value: T) : PromptResult<T> {
        override fun toString(): String = "Success(value=$REDACTED)"
    }

    /** AICore is busy: quota used up, or the app is in the background. */
    data object Busy : PromptResult<Nothing>

    /** The model is not available (not downloaded, unsupported, AICore missing or too old). */
    data object Unavailable : PromptResult<Nothing>

    /** No usable answer: unparseable or constraint-breaking output, a policy refusal or another AICore error. */
    data class Failed(val failure: AiFailure) : PromptResult<Nothing>
}

package dev.kpetriv.sprout.core.ai

import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.prompt.GenerateContentRequest
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.SystemInstruction
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateTypedContentRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException
import kotlin.reflect.KClass

/**
 * [PromptModel] on the ML Kit GenAI Prompt API (`com.google.mlkit:genai-prompt`, Gemini Nano on AICore). Only runs on
 * a device; JVM tests use a fake [PromptModel]. AICore serves foreground apps only and may answer `BUSY`; every
 * failure is mapped to a [PromptResult] and never thrown.
 *
 * One [GenerativeModel] client serves every call. The Hilt binding is an app-lifetime singleton, so in the app the
 * client lives as long as the process and is released with it; [close] releases it (and stops a download still being
 * collected) for an owner with a shorter life (review L16).
 *
 * @param scope where a requested download keeps being collected after [download] returns (the app's lifetime).
 */
internal class MlKitPromptModel(private val scope: CoroutineScope) :
    PromptModel,
    AutoCloseable {
    private val client = lazy { Generation.getClient() }
    private val model: GenerativeModel by client

    @Volatile
    private var systemInstructions: Boolean? = null

    override suspend fun status(): AiStatus = aicore(onError = { AICORE_ERROR }) {
        when (model.checkStatus()) {
            FeatureStatus.AVAILABLE -> if (model.isStructuredOutputFeatureAvailable()) {
                AiStatus.AVAILABLE
            } else {
                AiStatus(AiAvailability.UNAVAILABLE, AiStatus.REASON_NO_STRUCTURED_OUTPUT)
            }

            FeatureStatus.DOWNLOADABLE -> AiStatus(AiAvailability.DOWNLOADABLE)

            FeatureStatus.DOWNLOADING -> AiStatus(AiAvailability.DOWNLOADING)

            else -> AiStatus(AiAvailability.UNAVAILABLE, AiStatus.REASON_NOT_SUPPORTED)
        }
    }

    /**
     * Starts the download in [scope] and returns once AICore has accepted it (its first progress event) or refused it.
     * The caller bounds the wait; the download itself goes on in the background.
     */
    override suspend fun download(): Boolean = when (status().availability) {
        AiAvailability.AVAILABLE, AiAvailability.DOWNLOADING -> true

        AiAvailability.UNAVAILABLE -> false

        AiAvailability.DOWNLOADABLE -> {
            val accepted = CompletableDeferred<Boolean>()
            scope.launch { collectDownload(accepted) }
            accepted.await()
        }
    }

    /** Collects the download's progress until it ends; [accepted] completes with the first event. */
    @Suppress("TooGenericExceptionCaught", "SwallowedException") // A failed download only means "not accepted".
    private suspend fun collectDownload(accepted: CompletableDeferred<Boolean>) {
        try {
            model.download()
                .catch { accepted.complete(false) }
                .collect { status -> accepted.complete(status !is DownloadStatus.DownloadFailed) }
        } catch (cancelled: CancellationException) {
            accepted.complete(false)
            throw cancelled
        } catch (error: Exception) {
            // Nothing escapes into the scope: an uncaught exception there would crash the app.
            accepted.complete(false)
        }
        accepted.complete(true)
    }

    override suspend fun <T : Any> generate(request: PromptRequest, output: KClass<T>): PromptResult<T> =
        aicore(onError = ::resultFor) {
            val builder = if (supportsSystemInstructions()) {
                GenerateContentRequest.Builder(SystemInstruction(request.instructions), TextPart(request.input))
            } else {
                GenerateContentRequest.Builder(TextPart(request.instructions + "\n\n" + request.input))
            }
            val base = builder.apply {
                temperature = request.temperature
                topK = request.topK
                seed = request.seed
                candidateCount = 1
                maxOutputTokens = request.maxOutputTokens
            }.build()
            val answer = model.generateContent(generateTypedContentRequest(base, output)).candidates.firstOrNull()
            answer?.response?.let { PromptResult.Success(it) } ?: PromptResult.Failed(AiFailure.INVALID_OUTPUT)
        }

    private suspend fun supportsSystemInstructions(): Boolean =
        systemInstructions ?: model.isSystemPromptAvailable().also { systemInstructions = it }

    override fun close() {
        scope.cancel()
        if (client.isInitialized()) model.close()
    }

    /**
     * Runs an AICore call; a [GenAiException] or any other failure becomes [onError] of its error code
     * ([UNKNOWN_ERROR] when there is none). A [CancellationException] is rethrown only when the calling coroutine is
     * cancelled: AICore cancelling its own future is a failure like any other (review M6).
     */
    @Suppress("TooGenericExceptionCaught", "SwallowedException") // Any AICore failure means "no model answer".
    private suspend fun <R> aicore(onError: (Int) -> R, block: suspend () -> R): R = try {
        block()
    } catch (cancelled: CancellationException) {
        currentCoroutineContext().ensureActive()
        onError(UNKNOWN_ERROR)
    } catch (error: GenAiException) {
        onError(error.errorCode)
    } catch (error: Exception) {
        onError(UNKNOWN_ERROR)
    }

    private fun resultFor(code: Int): PromptResult<Nothing> = when (code) {
        GenAiException.ErrorCode.BUSY,
        GenAiException.ErrorCode.PER_APP_BATTERY_USE_QUOTA_EXCEEDED,
        GenAiException.ErrorCode.BACKGROUND_USE_BLOCKED,
        -> PromptResult.Busy

        GenAiException.ErrorCode.NOT_AVAILABLE,
        GenAiException.ErrorCode.AICORE_INCOMPATIBLE,
        GenAiException.ErrorCode.NEEDS_SYSTEM_UPDATE,
        GenAiException.ErrorCode.NOT_SUPPORTED,
        GenAiException.ErrorCode.NOT_ENOUGH_DISK_SPACE,
        -> PromptResult.Unavailable

        GenAiException.ErrorCode.STRUCTURED_OUTPUT_REQUEST_ERROR,
        GenAiException.ErrorCode.STRUCTURED_OUTPUT_RESPONSE_ERROR,
        GenAiException.ErrorCode.STRUCTURED_OUTPUT_MAX_TOKENS_ERROR,
        GenAiException.ErrorCode.RESPONSE_PROCESSING_ERROR,
        GenAiException.ErrorCode.RESPONSE_GENERATION_ERROR,
        GenAiException.ErrorCode.REQUEST_PROCESSING_ERROR,
        -> PromptResult.Failed(AiFailure.INVALID_OUTPUT)

        else -> PromptResult.Failed(AiFailure.ERROR)
    }

    private companion object {
        /** Stands for "no GenAiException code" (every real code is another value). */
        const val UNKNOWN_ERROR = Int.MIN_VALUE

        val AICORE_ERROR = AiStatus(AiAvailability.UNAVAILABLE, AiStatus.REASON_AICORE_ERROR)
    }
}

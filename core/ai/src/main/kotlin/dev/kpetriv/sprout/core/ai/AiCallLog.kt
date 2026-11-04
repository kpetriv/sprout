package dev.kpetriv.sprout.core.ai

import timber.log.Timber
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** The four gateway tasks, for logging. */
enum class AiTask {
    KINDNESS,
    CHORE_SUGGESTIONS,
    WEEKLY_SUMMARY,
    RECEIPT,
}

/** Why a call ended on the rules path. */
enum class AiFailure {
    /** The AI switch is off ([AiSettings.enabled]). */
    DISABLED,

    /** The model is not available on this device (or not downloaded). */
    UNAVAILABLE,

    /** AICore answered BUSY (quota, or the app is not in the foreground). */
    BUSY,

    /** No answer within the budget ([AiTimeouts]). */
    TIMEOUT,

    /** The answer did not parse or failed [AiOutputRules]. */
    INVALID_OUTPUT,

    /** Any other error from AICore. */
    ERROR,
}

/** A coarse latency, so logs never carry timing fine enough to fingerprint anything. */
enum class LatencyBucket(val upTo: Duration) {
    UNDER_250_MS(250.milliseconds),
    UNDER_500_MS(500.milliseconds),
    UNDER_1_S(1_000.milliseconds),
    UNDER_2_S(2_000.milliseconds),
    UNDER_6_S(6_000.milliseconds),
    OVER_6_S(Duration.INFINITE),
    ;

    companion object {
        fun of(latency: Duration): LatencyBucket = entries.first { latency < it.upTo || it == OVER_6_S }
    }
}

/**
 * Where AI calls are recorded: only the task, the path, a [LatencyBucket] and the failure kind. Never any text, input
 * or output (CLAUDE.md: never log personal data).
 */
fun interface AiCallLog {
    fun record(task: AiTask, path: AiPath, latency: LatencyBucket, failure: AiFailure?)

    companion object {
        /** Records nothing. */
        val None: AiCallLog = AiCallLog { _, _, _, _ -> }
    }
}

/** [AiCallLog] at DEBUG through Timber; the release tree drops everything below WARN, so nothing ships. */
object TimberAiCallLog : AiCallLog {
    override fun record(task: AiTask, path: AiPath, latency: LatencyBucket, failure: AiFailure?) {
        Timber.tag("SproutAi").d("task=%s path=%s latency=%s failure=%s", task, path, latency, failure)
    }
}

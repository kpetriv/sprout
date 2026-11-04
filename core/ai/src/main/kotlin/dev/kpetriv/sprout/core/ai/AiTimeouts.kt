package dev.kpetriv.sprout.core.ai

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Budgets for one gateway call, model and fallback included (M6 §1, D-M6-10). The chat feature adds its own 2.5 s
 * fail-open on top of [kindness].
 *
 * @property status how long [AiGateway.status] may wait for AICore.
 * @property download how long [AiGateway.requestDownload] waits for AICore to accept the request.
 */
data class AiTimeouts(
    val kindness: Duration = 2.seconds,
    val suggestions: Duration = 6.seconds,
    val summary: Duration = 6.seconds,
    val status: Duration = 2.seconds,
    val download: Duration = 10.seconds,
)

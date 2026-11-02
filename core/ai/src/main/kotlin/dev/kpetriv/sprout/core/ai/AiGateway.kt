package dev.kpetriv.sprout.core.ai

/** On-device AI entry point. Gemini Nano and the rules-based fallback both implement it (M6). */
interface AiGateway {
    suspend fun availability(): AiAvailability
}

enum class AiAvailability {
    AVAILABLE,
    DOWNLOADABLE,
    UNAVAILABLE,
}

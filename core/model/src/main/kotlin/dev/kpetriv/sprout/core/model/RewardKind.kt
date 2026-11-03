package dev.kpetriv.sprout.core.model

import kotlinx.serialization.Serializable

/** What a catalogue reward is. Only [SCREEN_TIME] grants time-bank minutes. */
@Serializable
enum class RewardKind {
    SCREEN_TIME,
    TREAT,
    OUTING,
    OTHER,
}

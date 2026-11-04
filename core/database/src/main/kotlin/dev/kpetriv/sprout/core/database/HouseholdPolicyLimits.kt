package dev.kpetriv.sprout.core.database

/**
 * Bounds of the household policy v2 settings (M7 §1, D-M7-10). The Firestore rules (`householdPolicyPayload`) and the
 * server (`server/src/config.ts`) enforce the same ranges; a pulled policy document outside them is ignored.
 */
object HouseholdPolicyLimits {
    /** `chatRetentionDays`: at least a day, at most about ten years; null keeps messages. */
    val CHAT_RETENTION_DAYS: IntRange = 1..3650

    /** `dailyGameCapMinor`: 0 (games credit nothing) to 10,000 minor units a day; null is the ledger default (50). */
    val DAILY_GAME_CAP_MINOR: LongRange = 0L..10_000L

    /** The retention choices the settings screen offers (null: forever). */
    val RETENTION_CHOICES: List<Int?> = listOf(null, 30, 90, 365)
}

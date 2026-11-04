package dev.kpetriv.sprout.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * The monotonic clock reading taken when this device started a time bank session (D-M4-24, schema version 8):
 * device-local, never synced. `StopTimeBankUseCase` measures the session with it when the device has not restarted
 * since, so moving the wall clock back during a session does not shorten it.
 *
 * @property sessionId the session's id (`TimeBankStarted.sessionId`).
 * @property kidId the kid whose session it is; a new session of the kid replaces the kid's older rows.
 * @property elapsedRealtimeMs the monotonic clock at the start (milliseconds since boot, deep sleep included).
 * @property bootCount the boot the reading belongs to, or null when the device does not say.
 */
@Entity(tableName = "time_bank_clocks")
data class TimeBankClockEntity(
    @PrimaryKey val sessionId: String,
    val kidId: String,
    val elapsedRealtimeMs: Long,
    val bootCount: Long?,
)

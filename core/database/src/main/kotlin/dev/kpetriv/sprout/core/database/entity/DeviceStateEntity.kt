package dev.kpetriv.sprout.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import dev.kpetriv.sprout.core.model.DeviceId

/**
 * This device's persistent state; a single row with [id] [SINGLETON_ID]. Holds the Lamport clock so it survives
 * restarts. The per-author head hash is derivable from `ledger_events`, so it is not stored here.
 */
@Entity(tableName = "device_state")
data class DeviceStateEntity(@PrimaryKey val id: Int = SINGLETON_ID, val deviceId: DeviceId, val lamport: Long) {
    companion object {
        const val SINGLETON_ID: Int = 0
    }
}

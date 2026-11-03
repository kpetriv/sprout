package dev.kpetriv.sprout.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import dev.kpetriv.sprout.core.model.DeviceId
import dev.kpetriv.sprout.core.model.HouseholdId
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.Role
import dev.kpetriv.sprout.core.model.UserId

/**
 * Who is signed in on this device (M3 §1); a single row with [id] [SINGLETON_ID], or none. Lives in the encrypted
 * database, so it is covered by SQLCipher like everything else.
 */
@Entity(tableName = "session")
data class SessionEntity(
    @PrimaryKey val id: Int = SINGLETON_ID,
    val uid: UserId,
    val householdId: HouseholdId,
    val role: Role,
    val kidId: KidId?,
    val deviceId: DeviceId,
    val deviceStatus: RegistrationStatus,
    val signedInAtEpochMs: Long,
) {
    companion object {
        const val SINGLETON_ID: Int = 0
    }
}

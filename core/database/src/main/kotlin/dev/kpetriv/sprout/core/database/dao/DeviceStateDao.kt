package dev.kpetriv.sprout.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import dev.kpetriv.sprout.core.database.entity.DeviceStateEntity

@Dao
interface DeviceStateDao {
    @Query("SELECT * FROM device_state WHERE id = ${DeviceStateEntity.SINGLETON_ID}")
    suspend fun get(): DeviceStateEntity?

    @Upsert
    suspend fun upsert(state: DeviceStateEntity)
}

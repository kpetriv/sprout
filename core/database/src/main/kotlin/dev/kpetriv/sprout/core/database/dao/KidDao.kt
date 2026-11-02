package dev.kpetriv.sprout.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import dev.kpetriv.sprout.core.database.entity.KidEntity
import dev.kpetriv.sprout.core.model.KidId
import kotlinx.coroutines.flow.Flow

@Dao
interface KidDao {
    @Upsert
    suspend fun upsert(kid: KidEntity)

    @Query("SELECT * FROM kids WHERE archived = 0 ORDER BY birthYearMonth, kidId")
    fun observeActive(): Flow<List<KidEntity>>

    @Query("SELECT * FROM kids WHERE kidId = :kidId")
    suspend fun get(kidId: KidId): KidEntity?

    @Query("UPDATE kids SET archived = 1 WHERE kidId = :kidId")
    suspend fun archive(kidId: KidId): Int

    @Query("SELECT COUNT(*) FROM kids")
    suspend fun count(): Int
}

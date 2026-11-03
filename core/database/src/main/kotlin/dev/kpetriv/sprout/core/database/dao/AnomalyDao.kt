package dev.kpetriv.sprout.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import dev.kpetriv.sprout.core.database.entity.AnomalyEntity
import dev.kpetriv.sprout.core.model.KidId
import kotlinx.coroutines.flow.Flow

/** The anomalies cache, replaced as a whole (every kid) on every projection of the household. */
@Dao
interface AnomalyDao {
    @Query("DELETE FROM anomalies")
    suspend fun deleteAll()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(anomalies: List<AnomalyEntity>)

    /** Swaps every cached anomaly for [anomalies]. */
    @Transaction
    suspend fun replaceAll(anomalies: List<AnomalyEntity>) {
        deleteAll()
        insert(anomalies)
    }

    @Query("SELECT * FROM anomalies")
    suspend fun all(): List<AnomalyEntity>

    @Query("SELECT * FROM anomalies WHERE kidId = :kidId ORDER BY anomalyId")
    fun observeForKid(kidId: KidId): Flow<List<AnomalyEntity>>

    @Query("SELECT * FROM anomalies WHERE kidId = :kidId ORDER BY anomalyId")
    suspend fun forKid(kidId: KidId): List<AnomalyEntity>
}

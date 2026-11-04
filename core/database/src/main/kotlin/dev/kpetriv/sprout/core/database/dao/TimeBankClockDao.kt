package dev.kpetriv.sprout.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import dev.kpetriv.sprout.core.database.entity.TimeBankClockEntity

/** Monotonic start readings of time bank sessions started on this device (D-M4-24). */
@Dao
interface TimeBankClockDao {
    @Upsert
    suspend fun upsert(row: TimeBankClockEntity)

    @Query("SELECT * FROM time_bank_clocks WHERE sessionId = :sessionId")
    suspend fun get(sessionId: String): TimeBankClockEntity?

    @Query("DELETE FROM time_bank_clocks WHERE sessionId = :sessionId")
    suspend fun delete(sessionId: String): Int

    @Query("DELETE FROM time_bank_clocks WHERE kidId = :kidId")
    suspend fun deleteForKid(kidId: String): Int

    @Query("DELETE FROM time_bank_clocks WHERE kidId IN (:kids)")
    suspend fun deleteForKids(kids: List<String>): Int

    @Query("SELECT COUNT(*) FROM time_bank_clocks")
    suspend fun count(): Int
}

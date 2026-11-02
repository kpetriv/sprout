package dev.kpetriv.sprout.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import dev.kpetriv.sprout.core.database.entity.AuditLogEntity
import kotlinx.coroutines.flow.Flow

/** Append-only audit trail: insert and read, no update or delete. */
@Dao
interface AuditLogDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entry: AuditLogEntity): Long

    @Query("SELECT * FROM audit_log ORDER BY id DESC LIMIT :limit")
    fun observeNewest(limit: Int): Flow<List<AuditLogEntity>>

    @Query("SELECT * FROM audit_log ORDER BY id")
    suspend fun all(): List<AuditLogEntity>
}

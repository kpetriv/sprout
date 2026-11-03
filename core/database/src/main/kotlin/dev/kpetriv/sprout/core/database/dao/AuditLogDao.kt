package dev.kpetriv.sprout.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import dev.kpetriv.sprout.core.database.entity.AuditAction
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

    /** How many rows record exactly this [action] on [target] with these [detailsJson]. */
    @Query(
        "SELECT COUNT(*) FROM audit_log WHERE action = :action AND target = :target AND detailsJson = :detailsJson",
    )
    suspend fun countExact(action: AuditAction, target: String, detailsJson: String): Int

    /** Every row of [action], oldest first. */
    @Query("SELECT * FROM audit_log WHERE action = :action ORDER BY id")
    fun observeAction(action: AuditAction): Flow<List<AuditLogEntity>>
}

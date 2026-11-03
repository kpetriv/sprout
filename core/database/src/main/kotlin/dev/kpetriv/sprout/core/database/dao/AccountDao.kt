package dev.kpetriv.sprout.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import dev.kpetriv.sprout.core.database.entity.AccountEntity
import dev.kpetriv.sprout.core.model.AccountId
import dev.kpetriv.sprout.core.model.CalendarDate
import dev.kpetriv.sprout.core.model.KidId
import kotlinx.coroutines.flow.Flow

@Dao
interface AccountDao {
    /** Returns the row id, or -1 when an account for the same (kid, currency, kind) already exists. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(account: AccountEntity): Long

    @Query("SELECT * FROM accounts WHERE kidId = :kidId ORDER BY kind, currency")
    fun observeForKid(kidId: KidId): Flow<List<AccountEntity>>

    @Query("SELECT * FROM accounts WHERE kidId = :kidId ORDER BY kind, currency")
    suspend fun forKid(kidId: KidId): List<AccountEntity>

    /** Every account of every kid in the household. */
    @Query("SELECT * FROM accounts")
    suspend fun all(): List<AccountEntity>

    @Query("SELECT * FROM accounts WHERE accountId = :accountId")
    suspend fun get(accountId: AccountId): AccountEntity?

    /** Interest bookkeeping of a SAVINGS account; see [AccountEntity.lastAccruedOn]. */
    @Query("UPDATE accounts SET lastAccruedOn = :lastAccruedOn WHERE accountId = :accountId")
    suspend fun setLastAccruedOn(accountId: AccountId, lastAccruedOn: CalendarDate): Int
}

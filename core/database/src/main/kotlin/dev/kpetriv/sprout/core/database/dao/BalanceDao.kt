package dev.kpetriv.sprout.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import dev.kpetriv.sprout.core.database.entity.BalanceEntity
import dev.kpetriv.sprout.core.model.KidId
import kotlinx.coroutines.flow.Flow

/** The balances projection cache, replaced as a whole on every projection. */
@Dao
interface BalanceDao {
    @Upsert
    suspend fun upsert(balances: List<BalanceEntity>)

    @Query("DELETE FROM balances")
    suspend fun deleteAll()

    /** Swaps the whole cache for [balances], so an account no event posts to any more drops out. */
    @Transaction
    suspend fun replaceAll(balances: List<BalanceEntity>) {
        deleteAll()
        upsert(balances)
    }

    @Query("SELECT * FROM balances WHERE kidId = :kidId ORDER BY accountId")
    fun observeForKid(kidId: KidId): Flow<List<BalanceEntity>>

    @Query("SELECT * FROM balances WHERE kidId = :kidId ORDER BY accountId")
    suspend fun forKid(kidId: KidId): List<BalanceEntity>
}

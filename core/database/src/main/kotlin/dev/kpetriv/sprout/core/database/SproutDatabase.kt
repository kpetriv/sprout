package dev.kpetriv.sprout.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import dev.kpetriv.sprout.core.database.dao.AccountDao
import dev.kpetriv.sprout.core.database.dao.AllowanceScheduleDao
import dev.kpetriv.sprout.core.database.dao.AnomalyDao
import dev.kpetriv.sprout.core.database.dao.AuditLogDao
import dev.kpetriv.sprout.core.database.dao.BalanceDao
import dev.kpetriv.sprout.core.database.dao.ChoreDao
import dev.kpetriv.sprout.core.database.dao.ChoreSubmissionDao
import dev.kpetriv.sprout.core.database.dao.DeviceStateDao
import dev.kpetriv.sprout.core.database.dao.HouseholdPolicyDao
import dev.kpetriv.sprout.core.database.dao.InterestPolicyDao
import dev.kpetriv.sprout.core.database.dao.KidDao
import dev.kpetriv.sprout.core.database.dao.LedgerEventDao
import dev.kpetriv.sprout.core.database.dao.OutboxDao
import dev.kpetriv.sprout.core.database.dao.PendingParentActionDao
import dev.kpetriv.sprout.core.database.dao.PhotoDao
import dev.kpetriv.sprout.core.database.dao.RewardDao
import dev.kpetriv.sprout.core.database.dao.SavingsGoalDao
import dev.kpetriv.sprout.core.database.entity.AccountEntity
import dev.kpetriv.sprout.core.database.entity.AllowanceScheduleEntity
import dev.kpetriv.sprout.core.database.entity.AnomalyEntity
import dev.kpetriv.sprout.core.database.entity.AuditLogEntity
import dev.kpetriv.sprout.core.database.entity.BalanceEntity
import dev.kpetriv.sprout.core.database.entity.ChoreEntity
import dev.kpetriv.sprout.core.database.entity.ChoreSubmissionEntity
import dev.kpetriv.sprout.core.database.entity.DeviceStateEntity
import dev.kpetriv.sprout.core.database.entity.HouseholdPolicyEntity
import dev.kpetriv.sprout.core.database.entity.InterestPolicyEntity
import dev.kpetriv.sprout.core.database.entity.KidEntity
import dev.kpetriv.sprout.core.database.entity.LedgerEventEntity
import dev.kpetriv.sprout.core.database.entity.OutboxEntity
import dev.kpetriv.sprout.core.database.entity.PendingParentActionEntity
import dev.kpetriv.sprout.core.database.entity.PhotoEntity
import dev.kpetriv.sprout.core.database.entity.RewardEntity
import dev.kpetriv.sprout.core.database.entity.SavingsGoalEntity

/**
 * Sprout's local database. Encrypted with SQLCipher in production (see [DatabaseFactory]). The schema is exported
 * to `core/database/schemas` and every version bump needs a migration in [Migrations] and a `MigrationTest` case.
 */
@Database(
    entities = [
        LedgerEventEntity::class,
        AccountEntity::class,
        KidEntity::class,
        BalanceEntity::class,
        AnomalyEntity::class,
        OutboxEntity::class,
        AuditLogEntity::class,
        DeviceStateEntity::class,
        ChoreEntity::class,
        ChoreSubmissionEntity::class,
        RewardEntity::class,
        AllowanceScheduleEntity::class,
        SavingsGoalEntity::class,
        InterestPolicyEntity::class,
        PendingParentActionEntity::class,
        PhotoEntity::class,
        HouseholdPolicyEntity::class,
    ],
    version = SproutDatabase.VERSION,
    exportSchema = true,
)
@TypeConverters(Converters::class)
@Suppress("TooManyFunctions") // One DAO accessor per table.
abstract class SproutDatabase : RoomDatabase() {
    abstract fun ledgerEventDao(): LedgerEventDao

    abstract fun accountDao(): AccountDao

    abstract fun kidDao(): KidDao

    abstract fun balanceDao(): BalanceDao

    abstract fun anomalyDao(): AnomalyDao

    abstract fun outboxDao(): OutboxDao

    abstract fun auditLogDao(): AuditLogDao

    abstract fun deviceStateDao(): DeviceStateDao

    abstract fun choreDao(): ChoreDao

    abstract fun choreSubmissionDao(): ChoreSubmissionDao

    abstract fun rewardDao(): RewardDao

    abstract fun allowanceScheduleDao(): AllowanceScheduleDao

    abstract fun savingsGoalDao(): SavingsGoalDao

    abstract fun interestPolicyDao(): InterestPolicyDao

    abstract fun pendingParentActionDao(): PendingParentActionDao

    abstract fun photoDao(): PhotoDao

    abstract fun householdPolicyDao(): HouseholdPolicyDao

    companion object {
        /**
         * 2 (M2): chores, submissions, rewards, allowance, savings, interest, pending parent actions, photos and the
         * household policy; `ledger_events.parentSignature`, `accounts.lastAccruedOn` and the outbox document
         * columns. See [Migrations.MIGRATION_1_2].
         */
        const val VERSION: Int = 2
    }
}

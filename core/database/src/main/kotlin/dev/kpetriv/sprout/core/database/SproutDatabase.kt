package dev.kpetriv.sprout.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import dev.kpetriv.sprout.core.database.dao.AccountDao
import dev.kpetriv.sprout.core.database.dao.AllowanceScheduleDao
import dev.kpetriv.sprout.core.database.dao.AnomalyDao
import dev.kpetriv.sprout.core.database.dao.AuditLogDao
import dev.kpetriv.sprout.core.database.dao.BalanceDao
import dev.kpetriv.sprout.core.database.dao.BlobDao
import dev.kpetriv.sprout.core.database.dao.ChatHouseholdDao
import dev.kpetriv.sprout.core.database.dao.ChoreDao
import dev.kpetriv.sprout.core.database.dao.ChoreSubmissionDao
import dev.kpetriv.sprout.core.database.dao.DeviceDao
import dev.kpetriv.sprout.core.database.dao.DeviceStateDao
import dev.kpetriv.sprout.core.database.dao.DocumentInboxDao
import dev.kpetriv.sprout.core.database.dao.HouseholdPolicyDao
import dev.kpetriv.sprout.core.database.dao.InterestPolicyDao
import dev.kpetriv.sprout.core.database.dao.KidDao
import dev.kpetriv.sprout.core.database.dao.LedgerEventDao
import dev.kpetriv.sprout.core.database.dao.LedgerInboxDao
import dev.kpetriv.sprout.core.database.dao.MessageDao
import dev.kpetriv.sprout.core.database.dao.OutboxDao
import dev.kpetriv.sprout.core.database.dao.ParentKeyDao
import dev.kpetriv.sprout.core.database.dao.PendingParentActionDao
import dev.kpetriv.sprout.core.database.dao.PhotoDao
import dev.kpetriv.sprout.core.database.dao.PurgeDao
import dev.kpetriv.sprout.core.database.dao.RewardDao
import dev.kpetriv.sprout.core.database.dao.SavingsGoalDao
import dev.kpetriv.sprout.core.database.dao.SessionDao
import dev.kpetriv.sprout.core.database.dao.SyncCursorDao
import dev.kpetriv.sprout.core.database.dao.ThreadKeyDao
import dev.kpetriv.sprout.core.database.entity.AccountEntity
import dev.kpetriv.sprout.core.database.entity.AllowanceScheduleEntity
import dev.kpetriv.sprout.core.database.entity.AnomalyEntity
import dev.kpetriv.sprout.core.database.entity.AuditLogEntity
import dev.kpetriv.sprout.core.database.entity.BalanceEntity
import dev.kpetriv.sprout.core.database.entity.BlobEntity
import dev.kpetriv.sprout.core.database.entity.ChoreEntity
import dev.kpetriv.sprout.core.database.entity.ChoreSubmissionEntity
import dev.kpetriv.sprout.core.database.entity.DeviceEntity
import dev.kpetriv.sprout.core.database.entity.DeviceStateEntity
import dev.kpetriv.sprout.core.database.entity.DocumentInboxEntity
import dev.kpetriv.sprout.core.database.entity.HouseholdPolicyEntity
import dev.kpetriv.sprout.core.database.entity.InterestPolicyEntity
import dev.kpetriv.sprout.core.database.entity.KidEntity
import dev.kpetriv.sprout.core.database.entity.LedgerEventEntity
import dev.kpetriv.sprout.core.database.entity.LedgerInboxEntity
import dev.kpetriv.sprout.core.database.entity.MessageEntity
import dev.kpetriv.sprout.core.database.entity.MessageSeqEntity
import dev.kpetriv.sprout.core.database.entity.OutboxEntity
import dev.kpetriv.sprout.core.database.entity.ParentKeyEntity
import dev.kpetriv.sprout.core.database.entity.PendingParentActionEntity
import dev.kpetriv.sprout.core.database.entity.PhotoEntity
import dev.kpetriv.sprout.core.database.entity.ReadMarkEntity
import dev.kpetriv.sprout.core.database.entity.RewardEntity
import dev.kpetriv.sprout.core.database.entity.SavingsGoalEntity
import dev.kpetriv.sprout.core.database.entity.SessionEntity
import dev.kpetriv.sprout.core.database.entity.SyncCursorEntity
import dev.kpetriv.sprout.core.database.entity.ThreadKeyEntity

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
        DeviceEntity::class,
        ParentKeyEntity::class,
        LedgerInboxEntity::class,
        DocumentInboxEntity::class,
        SyncCursorEntity::class,
        SessionEntity::class,
        MessageEntity::class,
        MessageSeqEntity::class,
        ThreadKeyEntity::class,
        BlobEntity::class,
        ReadMarkEntity::class,
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

    abstract fun deviceDao(): DeviceDao

    abstract fun parentKeyDao(): ParentKeyDao

    abstract fun ledgerInboxDao(): LedgerInboxDao

    abstract fun documentInboxDao(): DocumentInboxDao

    abstract fun syncCursorDao(): SyncCursorDao

    abstract fun sessionDao(): SessionDao

    abstract fun purgeDao(): PurgeDao

    abstract fun messageDao(): MessageDao

    abstract fun threadKeyDao(): ThreadKeyDao

    abstract fun blobDao(): BlobDao

    abstract fun chatHouseholdDao(): ChatHouseholdDao

    companion object {
        /**
         * 2 (M2): chores, submissions, rewards, allowance, savings, interest, pending parent actions, photos and the
         * household policy; `ledger_events.parentSignature`, `accounts.lastAccruedOn` and the outbox document
         * columns. See [Migrations.MIGRATION_1_2].
         *
         * 3 (M3): device registry mirror (`devices`, `parent_keys`), held events and documents (`ledger_inbox`,
         * `document_inbox`), pull cursors, the session, author/signature/tombstone columns on every synced document,
         * `ledger_events.ingestedAtEpochMs` and `anomalies.appliedCopyJson`. See [Migrations.MIGRATION_2_3].
         *
         * 4 (M3 fix pass): `ledger_inbox.reason` and `document_inbox.authorDeviceId` for held events and documents
         * (D-M3-31). See [Migrations.MIGRATION_3_4].
         *
         * 5 (M3 fix pass 2): `sync_cursor` keyed by household. See [Migrations.MIGRATION_4_5].
         *
         * 6 (M4): chat (`messages`, `message_seq`, `thread_keys`, `read_marks`), the blob cache index (`blobs`),
         * `photos.blobId`, the registry mirror's encryption key columns and `outbox.data`. See
         * [Migrations.MIGRATION_5_6].
         *
         * 7 (M5): `ledger_events.serverReceivedAtEpochMs` and `ledger_inbox.serverReceivedAtEpochMs`, the server
         * stamp of pulled events for the games' played-on window (D-M5-5). See [Migrations.MIGRATION_6_7].
         */
        const val VERSION: Int = 7
    }
}

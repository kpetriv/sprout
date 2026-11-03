package dev.kpetriv.sprout.core.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Every schema migration of [SproutDatabase], oldest first. Each statement is copied from the exported schema of the
 * target version (`core/database/schemas`), and `MigrationTest` validates the migrated database against it.
 */
object Migrations {
    /**
     * Version 1 (M1) to 2 (M2). Purely additive: existing rows keep their values and every new column is nullable
     * (null for every M1 row: no M1 event carries a parent signature, no account has accrued interest, no outbox row
     * is a document).
     */
    val MIGRATION_1_2: Migration = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            VERSION_2_STATEMENTS.forEach(db::execSQL)
        }
    }

    val ALL: Array<Migration> = arrayOf(MIGRATION_1_2)

    private val VERSION_2_STATEMENTS = listOf(
        "ALTER TABLE `ledger_events` ADD COLUMN `parentSignature` TEXT",
        "ALTER TABLE `accounts` ADD COLUMN `lastAccruedOn` TEXT",
        "ALTER TABLE `outbox` ADD COLUMN `docType` TEXT",
        "ALTER TABLE `outbox` ADD COLUMN `docId` TEXT",
        "ALTER TABLE `outbox` ADD COLUMN `lamport` INTEGER",
        "CREATE TABLE IF NOT EXISTS `chores` (`choreId` TEXT NOT NULL, `householdId` TEXT NOT NULL, " +
            "`kidId` TEXT NOT NULL, `title` TEXT NOT NULL, `description` TEXT, `rewardMoney` INTEGER, " +
            "`rewardMinutes` INTEGER, `scheduleJson` TEXT NOT NULL, `state` TEXT NOT NULL, " +
            "`createdAtEpochMs` INTEGER NOT NULL, `lamport` INTEGER NOT NULL, PRIMARY KEY(`choreId`))",
        "CREATE INDEX IF NOT EXISTS `index_chores_kidId` ON `chores` (`kidId`)",
        "CREATE TABLE IF NOT EXISTS `chore_submissions` (`submissionId` TEXT NOT NULL, `choreId` TEXT NOT NULL, " +
            "`dueOn` TEXT NOT NULL, `kidId` TEXT NOT NULL, `submittedAtEpochMs` INTEGER NOT NULL, `note` TEXT, " +
            "`photoFileName` TEXT, `photoSha256` TEXT, `status` TEXT NOT NULL, `decidedAtEpochMs` INTEGER, " +
            "`decidedBy` TEXT, `rejectionReason` TEXT, `lamport` INTEGER NOT NULL, PRIMARY KEY(`submissionId`))",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_chore_submissions_choreId_dueOn` " +
            "ON `chore_submissions` (`choreId`, `dueOn`)",
        "CREATE INDEX IF NOT EXISTS `index_chore_submissions_kidId` ON `chore_submissions` (`kidId`)",
        "CREATE INDEX IF NOT EXISTS `index_chore_submissions_status` ON `chore_submissions` (`status`)",
        "CREATE TABLE IF NOT EXISTS `rewards` (`rewardId` TEXT NOT NULL, `householdId` TEXT NOT NULL, " +
            "`title` TEXT NOT NULL, `kind` TEXT NOT NULL, `cost` INTEGER NOT NULL, `currency` TEXT NOT NULL, " +
            "`minutesGranted` INTEGER, `active` INTEGER NOT NULL, `forKidsJson` TEXT NOT NULL, " +
            "`lamport` INTEGER NOT NULL, PRIMARY KEY(`rewardId`))",
        "CREATE TABLE IF NOT EXISTS `allowance_schedules` (`scheduleId` TEXT NOT NULL, `kidId` TEXT NOT NULL, " +
            "`accountId` TEXT NOT NULL, `amount` INTEGER NOT NULL, `currency` TEXT NOT NULL, " +
            "`cadence` TEXT NOT NULL, `cadenceDay` INTEGER NOT NULL, `startsOn` TEXT NOT NULL, " +
            "`active` INTEGER NOT NULL, `lastPaidOn` TEXT, " +
            "`lamport` INTEGER NOT NULL, PRIMARY KEY(`scheduleId`))",
        "CREATE INDEX IF NOT EXISTS `index_allowance_schedules_kidId` ON `allowance_schedules` (`kidId`)",
        "CREATE TABLE IF NOT EXISTS `savings_goals` (`goalId` TEXT NOT NULL, `kidId` TEXT NOT NULL, " +
            "`title` TEXT NOT NULL, `target` INTEGER NOT NULL, `createdOn` TEXT NOT NULL, `achievedOn` TEXT, " +
            "`lamport` INTEGER NOT NULL, PRIMARY KEY(`goalId`))",
        "CREATE INDEX IF NOT EXISTS `index_savings_goals_kidId` ON `savings_goals` (`kidId`)",
        "CREATE TABLE IF NOT EXISTS `interest_policy` (`householdId` TEXT NOT NULL, " +
            "`annualRateBasisPoints` INTEGER NOT NULL, `accrualDay` INTEGER NOT NULL, `lamport` INTEGER NOT NULL, " +
            "PRIMARY KEY(`householdId`))",
        "CREATE TABLE IF NOT EXISTS `pending_parent_actions` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "`key` TEXT NOT NULL, `kind` TEXT NOT NULL, `kidId` TEXT NOT NULL, `accountId` TEXT NOT NULL, " +
            "`amount` INTEGER NOT NULL, `currency` TEXT NOT NULL, `periodKey` TEXT NOT NULL, " +
            "`actionJson` TEXT NOT NULL, `createdAtEpochMs` INTEGER NOT NULL, `doneAtEpochMs` INTEGER, " +
            "`eventId` TEXT, `status` TEXT NOT NULL, `refusals` INTEGER NOT NULL, `failureReason` TEXT)",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_pending_parent_actions_key` ON `pending_parent_actions` (`key`)",
        "CREATE INDEX IF NOT EXISTS `index_pending_parent_actions_status` ON `pending_parent_actions` (`status`)",
        "CREATE TABLE IF NOT EXISTS `photos` (`fileName` TEXT NOT NULL, `sha256` TEXT NOT NULL, " +
            "`kidId` TEXT NOT NULL, `submissionId` TEXT, `createdAtEpochMs` INTEGER NOT NULL, PRIMARY KEY(`fileName`))",
        "CREATE INDEX IF NOT EXISTS `index_photos_kidId` ON `photos` (`kidId`)",
        "CREATE INDEX IF NOT EXISTS `index_photos_submissionId` ON `photos` (`submissionId`)",
        "CREATE TABLE IF NOT EXISTS `household_policy` (`id` INTEGER NOT NULL, " +
            "`requireParentSignature` INTEGER NOT NULL, `parentSignatureFromLamport` INTEGER, " +
            "`parentPublicKey` BLOB, `fiatCurrency` TEXT NOT NULL, `createdAtEpochMs` INTEGER NOT NULL, " +
            "PRIMARY KEY(`id`))",
    )
}

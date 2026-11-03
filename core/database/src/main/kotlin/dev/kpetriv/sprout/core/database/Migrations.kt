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

    /**
     * Version 2 (M2) to 3 (M3 sync and identity). Additive except for one deletion:
     * - new tables: the registry mirror (`devices`, `parent_keys`), `ledger_inbox`, `document_inbox`, `sync_cursor`
     *   and `session`;
     * - every synced document table gets `authorUid`, `authorDeviceId`, `signature` (null) and `deleted` (0); `kids`,
     *   `accounts` and `household_policy` also get `lamport` (0); `ledger_events.ingestedAtEpochMs` and
     *   `anomalies.appliedCopyJson` (null);
     * - every M2 row was written on this device by the local trial parent, so the document tables get
     *   `authorUid = 'local-parent'` and `authorDeviceId` = this device (`device_state`, null if it never wrote):
     *   the reward catalogue keeps accepting the trial household's own rewards. Their signature stays null;
     * - version 2 `DOCUMENT` outbox rows held unsigned JSON that can never be sent and are deleted (the trial household
     *   is purged at linking anyway). Ledger event outbox rows are kept.
     */
    val MIGRATION_2_3: Migration = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            VERSION_3_STATEMENTS.forEach(db::execSQL)
        }
    }

    /**
     * Version 3 to 4 (M3 fix pass, D-M3-31). Purely additive: `ledger_inbox.reason` and `document_inbox.authorDeviceId`
     * (both null for existing rows; a held event gets its reason on the next retry) and an index on the latter for
     * the per-author cap on held rows.
     */
    val MIGRATION_3_4: Migration = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            VERSION_4_STATEMENTS.forEach(db::execSQL)
        }
    }

    /**
     * Version 4 to 5 (M3 fix pass 2): `sync_cursor` is keyed by household (`householdId`, `collection`), so a device
     * that joins another household never resumes the previous one's pull positions. Existing positions are kept for
     * the stored real session's household; without one (signed out, or the trial) they are dropped, which only costs
     * a full pull.
     */
    val MIGRATION_4_5: Migration = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            VERSION_5_STATEMENTS.forEach(db::execSQL)
        }
    }

    val ALL: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)

    private val VERSION_5_STATEMENTS = listOf(
        "CREATE TABLE IF NOT EXISTS `sync_cursor_v5` (`householdId` TEXT NOT NULL, `collection` TEXT NOT NULL, " +
            "`cursor` TEXT NOT NULL, PRIMARY KEY(`householdId`, `collection`))",
        "INSERT INTO `sync_cursor_v5` (`householdId`, `collection`, `cursor`) " +
            "SELECT `session`.`householdId`, `sync_cursor`.`collection`, `sync_cursor`.`cursor` " +
            "FROM `sync_cursor` JOIN `session` ON `session`.`id` = 0 WHERE `session`.`householdId` != 'local'",
        "DROP TABLE `sync_cursor`",
        "ALTER TABLE `sync_cursor_v5` RENAME TO `sync_cursor`",
    )

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

    private val DOCUMENT_TABLES = listOf(
        "chores",
        "chore_submissions",
        "rewards",
        "allowance_schedules",
        "savings_goals",
        "interest_policy",
    )

    private val STAMP_COLUMNS = listOf(
        "ADD COLUMN `authorUid` TEXT",
        "ADD COLUMN `authorDeviceId` TEXT",
        "ADD COLUMN `signature` TEXT",
        "ADD COLUMN `deleted` INTEGER NOT NULL DEFAULT 0",
    )

    private val VERSION_3_STATEMENTS: List<String> =
        (DOCUMENT_TABLES + listOf("kids", "accounts", "household_policy")).flatMap { table ->
            STAMP_COLUMNS.map { "ALTER TABLE `$table` $it" }
        } + listOf("kids", "accounts", "household_policy").map { table ->
            "ALTER TABLE `$table` ADD COLUMN `lamport` INTEGER NOT NULL DEFAULT 0"
        } + (DOCUMENT_TABLES + listOf("kids", "accounts", "household_policy")).map { table ->
            "UPDATE `$table` SET `authorUid` = 'local-parent', " +
                "`authorDeviceId` = (SELECT `deviceId` FROM `device_state` WHERE `id` = 0)"
        } + listOf(
            "ALTER TABLE `ledger_events` ADD COLUMN `ingestedAtEpochMs` INTEGER",
            "ALTER TABLE `anomalies` ADD COLUMN `appliedCopyJson` TEXT",
            "DELETE FROM `outbox` WHERE `kind` = 'DOCUMENT'",
            "CREATE TABLE IF NOT EXISTS `devices` (`deviceId` TEXT NOT NULL, `uid` TEXT NOT NULL, " +
                "`role` TEXT NOT NULL, `kidId` TEXT, `publicKey` BLOB NOT NULL, `status` TEXT NOT NULL, " +
                "`revokedAtLamport` INTEGER, `attestation` TEXT NOT NULL, `registeredAtEpochMs` INTEGER NOT NULL, " +
                "`lamport` INTEGER NOT NULL, PRIMARY KEY(`deviceId`))",
            "CREATE TABLE IF NOT EXISTS `parent_keys` (`fingerprint` TEXT NOT NULL, `publicKey` BLOB NOT NULL, " +
                "`uid` TEXT NOT NULL, `deviceId` TEXT NOT NULL, `validFromLamport` INTEGER NOT NULL, " +
                "`revokedAtLamport` INTEGER, `registeredAtEpochMs` INTEGER NOT NULL, PRIMARY KEY(`fingerprint`))",
            "CREATE TABLE IF NOT EXISTS `ledger_inbox` (`eventId` TEXT NOT NULL, `hash` TEXT NOT NULL, " +
                "`payloadJson` TEXT NOT NULL, `authorDeviceId` TEXT NOT NULL, `lamport` INTEGER NOT NULL, " +
                "`receivedAtEpochMs` INTEGER NOT NULL, PRIMARY KEY(`eventId`, `hash`))",
            "CREATE INDEX IF NOT EXISTS `index_ledger_inbox_authorDeviceId_lamport` " +
                "ON `ledger_inbox` (`authorDeviceId`, `lamport`)",
            "CREATE TABLE IF NOT EXISTS `document_inbox` (`docType` TEXT NOT NULL, `docId` TEXT NOT NULL, " +
                "`envelopeJson` TEXT NOT NULL, `reason` TEXT NOT NULL, `receivedAtEpochMs` INTEGER NOT NULL, " +
                "PRIMARY KEY(`docType`, `docId`))",
            "CREATE TABLE IF NOT EXISTS `sync_cursor` (`collection` TEXT NOT NULL, `cursor` TEXT NOT NULL, " +
                "PRIMARY KEY(`collection`))",
            "CREATE TABLE IF NOT EXISTS `session` (`id` INTEGER NOT NULL, `uid` TEXT NOT NULL, " +
                "`householdId` TEXT NOT NULL, `role` TEXT NOT NULL, `kidId` TEXT, `deviceId` TEXT NOT NULL, " +
                "`deviceStatus` TEXT NOT NULL, `signedInAtEpochMs` INTEGER NOT NULL, PRIMARY KEY(`id`))",
        )

    private val VERSION_4_STATEMENTS: List<String> = listOf(
        "ALTER TABLE `ledger_inbox` ADD COLUMN `reason` TEXT",
        "ALTER TABLE `document_inbox` ADD COLUMN `authorDeviceId` TEXT",
        "CREATE INDEX IF NOT EXISTS `index_document_inbox_authorDeviceId` ON `document_inbox` (`authorDeviceId`)",
    )
}

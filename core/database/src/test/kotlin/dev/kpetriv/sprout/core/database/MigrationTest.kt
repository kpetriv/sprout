package dev.kpetriv.sprout.core.database

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import dev.kpetriv.sprout.core.database.entity.EventOrigin
import dev.kpetriv.sprout.core.database.entity.LedgerEventEntity
import dev.kpetriv.sprout.core.database.entity.OutboxKind
import dev.kpetriv.sprout.core.domainledger.DeviceWriter
import dev.kpetriv.sprout.core.domainledger.Fixtures
import dev.kpetriv.sprout.core.domainledger.LedgerEvent
import dev.kpetriv.sprout.core.domainledger.Money
import dev.kpetriv.sprout.core.model.KidUiTier
import dev.kpetriv.sprout.core.model.RewardId
import dev.kpetriv.sprout.core.model.YearMonth
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Every migration in [Migrations.ALL] runs against a database created at the old version from its exported schema,
 * and the result is validated against the new version's exported schema.
 */
@RunWith(RobolectricTestRunner::class)
class MigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), SproutDatabase::class.java)

    private val chore = DeviceWriter(Fixtures.parentPhone).chore(250)
    private val receivedAt = Fixtures.start.toEpochMilliseconds()

    @Test
    fun version1CreatesAndValidatesAgainstTheExportedSchema() {
        helper.createDatabase(TEST_DB, 1).close()
        helper.runMigrationsAndValidate(TEST_DB, 1, true).close()
    }

    @Test
    fun migration1To2KeepsEveryRowAndAddsTheM2Tables() = runTest {
        helper.createDatabase(TEST_DB, 1).use { db -> insertVersion1Rows(db, chore) }

        helper.runMigrationsAndValidate(TEST_DB, 2, true, Migrations.MIGRATION_1_2).use { db ->
            db.query("SELECT parentSignature, payloadJson, hash FROM ledger_events").use { cursor ->
                cursor.moveToFirst()
                assertNull(cursor.getString(0))
                assertEquals(
                    LedgerEventMapper.toEntity(chore, EventOrigin.LOCAL, receivedAt).payloadJson,
                    cursor.getString(1),
                )
                assertEquals(chore.envelope.hash, cursor.getString(2))
            }
            db.query("SELECT docType, docId, lamport FROM outbox").use { cursor ->
                cursor.moveToFirst()
                assertEquals(listOf(true, true, true), (0..2).map(cursor::isNull))
            }
            NEW_TABLES.forEach { table ->
                db.query("SELECT COUNT(*) FROM $table").use { cursor ->
                    cursor.moveToFirst()
                    assertEquals(0, cursor.getInt(0), table)
                }
            }
        }

        // Room opens the migrated file and reads the M1 rows through the version 2 entities.
        val database = Room.databaseBuilder(RuntimeEnvironment.getApplication(), SproutDatabase::class.java, TEST_DB)
            .addMigrations(*Migrations.ALL)
            .build()
        try {
            val row = database.ledgerEventDao().all().single()
            assertEquals(chore, LedgerEventMapper.toDomain(row))
            assertNull(row.parentSignature)
            val account = database.accountDao().all().single()
            assertEquals(Fixtures.main.id, account.accountId)
            assertNull(account.lastAccruedOn)
            val kid = database.kidDao().observeActive().first().single()
            assertEquals("Mia", kid.firstName)
            assertEquals(KidUiTier.SEEDLING, kid.uiTier)
            val outbox = database.outboxDao().all().single()
            assertEquals(OutboxKind.LEDGER_EVENT, outbox.kind)
            assertNull(outbox.docType)
            assertEquals(Money(250), database.balanceDao().forKid(Fixtures.kid).single().amountMinor)
            assertEquals(1, database.deviceStateDao().get()?.lamport)
            assertNull(database.householdPolicyDao().get())
        } finally {
            database.close()
        }
    }

    @Test
    fun migration2To3StampsTheTrialRowsAndDropsUnsignedDocumentRows() = runTest {
        helper.createDatabase(TEST_DB, 2).use { db ->
            insertVersion1Rows(db, chore)
            db.insertRow(
                "rewards",
                "rewardId" to "treat",
                "householdId" to "local",
                "title" to "Treat",
                "kind" to "TREAT",
                "cost" to 100L,
                "currency" to "GBP",
                "minutesGranted" to null,
                "active" to 1,
                "forKidsJson" to "[]",
                "lamport" to 2L,
            )
            db.insertRow(
                "outbox",
                "kind" to OutboxKind.DOCUMENT.name,
                "refId" to "reward/treat",
                "payload" to "{}",
                "createdAtEpochMs" to receivedAt,
                "docType" to "reward",
                "docId" to "treat",
                "lamport" to 2L,
            )
        }

        helper.runMigrationsAndValidate(TEST_DB, 3, true, Migrations.MIGRATION_2_3).use { db ->
            NEW_TABLES_3.forEach { table ->
                db.query("SELECT COUNT(*) FROM $table").use { cursor ->
                    cursor.moveToFirst()
                    assertEquals(0, cursor.getInt(0), table)
                }
            }
        }

        val database = Room.databaseBuilder(RuntimeEnvironment.getApplication(), SproutDatabase::class.java, TEST_DB)
            .addMigrations(*Migrations.ALL)
            .build()
        try {
            val device = chore.envelope.authorDeviceId
            val reward = assertNotNull(database.rewardDao().get(RewardId("treat")))
            assertEquals(LocalTrial.PARENT, reward.stamp.authorUid)
            assertEquals(device, reward.stamp.authorDeviceId)
            assertNull(reward.stamp.signature)
            assertFalse(reward.stamp.deleted)
            val kid = assertNotNull(database.kidDao().get(Fixtures.kid))
            assertEquals(0, kid.lamport)
            assertEquals(device, kid.stamp.authorDeviceId)
            assertEquals(device, database.accountDao().all().single().stamp.authorDeviceId)
            // The unsigned version 2 document row is gone; the ledger event row stays queued.
            assertEquals(listOf(OutboxKind.LEDGER_EVENT), database.outboxDao().all().map { it.kind })
            assertNull(database.ledgerEventDao().all().single().ingestedAtEpochMs)
            assertNull(database.sessionDao().get())
        } finally {
            database.close()
        }
    }

    @Test
    fun migration1To3KeepsTheM1Rows() = runTest {
        helper.createDatabase(TEST_DB, 1).use { db -> insertVersion1Rows(db, chore) }

        helper.runMigrationsAndValidate(TEST_DB, 3, true, Migrations.MIGRATION_1_2, Migrations.MIGRATION_2_3).close()

        val database = Room.databaseBuilder(RuntimeEnvironment.getApplication(), SproutDatabase::class.java, TEST_DB)
            .addMigrations(*Migrations.ALL)
            .build()
        try {
            assertEquals(chore, LedgerEventMapper.toDomain(database.ledgerEventDao().all().single()))
            assertEquals(Money(250), database.balanceDao().forKid(Fixtures.kid).single().amountMinor)
            assertEquals("Mia", database.kidDao().observeActive().first().single().firstName)
            assertEquals(OutboxKind.LEDGER_EVENT, database.outboxDao().all().single().kind)
            assertEquals(1, database.deviceStateDao().get()?.lamport)
        } finally {
            database.close()
        }
    }

    @Test
    fun migration3To4AddsTheHoldColumnsAndKeepsTheHeldRows() = runTest {
        helper.createDatabase(TEST_DB, 3).use { db ->
            db.insertRow(
                "ledger_inbox",
                "eventId" to chore.eventId.value,
                "hash" to chore.envelope.hash,
                "payloadJson" to "{}",
                "authorDeviceId" to chore.envelope.authorDeviceId.value,
                "lamport" to 1L,
                "receivedAtEpochMs" to receivedAt,
            )
            db.insertRow(
                "document_inbox",
                "docType" to "reward",
                "docId" to "treat",
                "envelopeJson" to "{}",
                "reason" to "LAMPORT_TOO_FAR_AHEAD",
                "receivedAtEpochMs" to receivedAt,
            )
        }

        helper.runMigrationsAndValidate(TEST_DB, 4, true, Migrations.MIGRATION_3_4).use { db ->
            db.query("SELECT eventId, reason FROM ledger_inbox").use { cursor ->
                cursor.moveToFirst()
                assertEquals(chore.eventId.value, cursor.getString(0))
                assertTrue(cursor.isNull(1))
            }
            db.query("SELECT reason, authorDeviceId FROM document_inbox").use { cursor ->
                cursor.moveToFirst()
                assertEquals("LAMPORT_TOO_FAR_AHEAD", cursor.getString(0))
                assertTrue(cursor.isNull(1))
            }
        }

        val database = Room.databaseBuilder(RuntimeEnvironment.getApplication(), SproutDatabase::class.java, TEST_DB)
            .addMigrations(*Migrations.ALL)
            .build()
        try {
            assertNull(database.ledgerInboxDao().all().single().reason)
            assertNull(database.documentInboxDao().all().single().authorDeviceId)
        } finally {
            database.close()
        }
    }

    @Test
    fun migration1To4KeepsTheM1Rows() = runTest {
        helper.createDatabase(TEST_DB, 1).use { db -> insertVersion1Rows(db, chore) }

        helper.runMigrationsAndValidate(
            TEST_DB,
            4,
            true,
            Migrations.MIGRATION_1_2,
            Migrations.MIGRATION_2_3,
            Migrations.MIGRATION_3_4,
        ).close()
    }

    @Test
    fun migration4To5DropsEveryCursorEvenWithARealSession() = runTest {
        helper.createDatabase(TEST_DB, 4).use { db ->
            db.insertRow(
                "session",
                "id" to 0,
                "uid" to "parent-uid",
                "householdId" to "hh-real",
                "role" to "PARENT",
                "kidId" to null,
                "deviceId" to "d".repeat(64),
                "deviceStatus" to "ACTIVE",
                "signedInAtEpochMs" to receivedAt,
            )
            db.insertRow("sync_cursor", "collection" to "events", "cursor" to "2026-11-03T09:00:00Z|e1")
            db.insertRow("sync_cursor", "collection" to "devices", "cursor" to "digest")
        }

        helper.runMigrationsAndValidate(TEST_DB, 5, true, Migrations.MIGRATION_4_5).use { db ->
            db.query("SELECT COUNT(*) FROM sync_cursor").use { cursor ->
                cursor.moveToFirst()
                assertEquals(0, cursor.getInt(0), "the next sync pulls everything again")
            }
            db.query("SELECT householdId FROM session").use { cursor ->
                cursor.moveToFirst()
                assertEquals("hh-real", cursor.getString(0), "the session itself stays")
            }
        }
    }

    @Test
    fun migration4To5DropsCursorsWithoutARealSession() = runTest {
        helper.createDatabase(TEST_DB, 4).use { db ->
            db.insertRow("sync_cursor", "collection" to "events", "cursor" to "x")
        }

        helper.runMigrationsAndValidate(TEST_DB, 5, true, Migrations.MIGRATION_4_5).use { db ->
            db.query("SELECT COUNT(*) FROM sync_cursor").use { cursor ->
                cursor.moveToFirst()
                assertEquals(0, cursor.getInt(0))
            }
        }
    }

    @Test
    fun migration1To5KeepsTheM1Rows() = runTest {
        helper.createDatabase(TEST_DB, 1).use { db -> insertVersion1Rows(db, chore) }

        helper.runMigrationsAndValidate(TEST_DB, 5, true, *Migrations.ALL).close()
    }

    @Test
    fun migration5To6AddsTheChatTablesAndKeepsTheRows() = runTest {
        helper.createDatabase(TEST_DB, 5).use { db ->
            db.insertRow(
                "photos",
                "fileName" to "a.photo",
                "sha256" to "0".repeat(64),
                "kidId" to "kid-1",
                "submissionId" to "sub-1",
                "createdAtEpochMs" to receivedAt,
            )
            db.insertRow(
                "devices",
                "deviceId" to "d".repeat(64),
                "uid" to "parent-uid",
                "role" to "PARENT",
                "kidId" to null,
                "publicKey" to byteArrayOf(1, 2, 3),
                "status" to "ACTIVE",
                "revokedAtLamport" to null,
                "attestation" to "NONE",
                "registeredAtEpochMs" to receivedAt,
                "lamport" to 0L,
            )
            db.insertRow(
                "outbox",
                "kind" to OutboxKind.DOCUMENT.name,
                "refId" to "kid/kid-1",
                "payload" to "{}",
                "createdAtEpochMs" to receivedAt,
            )
            insertSubmissionWithPhoto(db)
        }

        helper.runMigrationsAndValidate(TEST_DB, 6, true, Migrations.MIGRATION_5_6).use { db ->
            assertSubmissionWithoutBlob(db)
            db.query("SELECT fileName, blobId FROM photos").use { cursor ->
                cursor.moveToFirst()
                assertEquals("a.photo", cursor.getString(0))
                assertTrue(cursor.isNull(1), "not uploaded yet")
            }
            db.query("SELECT encryptionKey, encryptionKeySignature, encryptionKeyHouseholdId FROM devices").use {
                it.moveToFirst()
                assertEquals(listOf(true, true, true), (0..2).map(it::isNull), "the next registry pull fills them")
            }
            assertFixPass2Columns(db)
            db.query("SELECT refId, data FROM outbox").use { cursor ->
                cursor.moveToFirst()
                assertEquals("kid/kid-1", cursor.getString(0))
                assertTrue(cursor.isNull(1))
            }
            CHAT_TABLES.forEach { table ->
                db.query("SELECT COUNT(*) FROM $table").use { cursor ->
                    cursor.moveToFirst()
                    assertEquals(0, cursor.getInt(0), table)
                }
            }
        }
    }

    /** The M4 fix pass 2 columns: no device starts excluded; ordering, late messages, key confirmation exist. */
    private fun assertFixPass2Columns(db: SupportSQLiteDatabase) {
        db.query("SELECT encryptionKeyConflict FROM devices").use {
            it.moveToFirst()
            assertEquals(0, it.getInt(0), "no device starts excluded")
        }
        listOf(
            "SELECT displayedAtEpochMs, late FROM messages",
            "SELECT lastLamport, lastDisplayedAtEpochMs, skippedRanges FROM message_seq",
            "SELECT confirmed, olderKeys FROM thread_keys",
        ).forEach { query -> db.query(query).close() }
    }

    @Test
    fun migration1To6KeepsTheM1Rows() = runTest {
        helper.createDatabase(TEST_DB, 1).use { db -> insertVersion1Rows(db, chore) }

        helper.runMigrationsAndValidate(TEST_DB, 6, true, *Migrations.ALL).use { db ->
            db.query("SELECT hash FROM ledger_events").use { cursor ->
                cursor.moveToFirst()
                assertEquals(chore.envelope.hash, cursor.getString(0))
            }
        }
    }

    @Test
    fun migration6To7AddsTheServerStampColumnsAsNull() = runTest {
        helper.createDatabase(TEST_DB, 6).use { db ->
            insertEventRow(db, chore, EventOrigin.REMOTE)
            db.insertRow(
                "ledger_inbox",
                "eventId" to chore.eventId.value,
                "hash" to chore.envelope.hash,
                "payloadJson" to "{}",
                "authorDeviceId" to chore.envelope.authorDeviceId.value,
                "lamport" to 1L,
                "receivedAtEpochMs" to receivedAt,
                "reason" to "CHAIN_GAP",
            )
        }

        helper.runMigrationsAndValidate(TEST_DB, 7, true, Migrations.MIGRATION_6_7).use { db ->
            db.query("SELECT hash, serverReceivedAtEpochMs FROM ledger_events").use { cursor ->
                cursor.moveToFirst()
                assertEquals(chore.envelope.hash, cursor.getString(0))
                assertTrue(cursor.isNull(1), "ingested before the stamp was kept: never date-checked")
            }
            db.query("SELECT reason, serverReceivedAtEpochMs FROM ledger_inbox").use { cursor ->
                cursor.moveToFirst()
                assertEquals("CHAIN_GAP", cursor.getString(0))
                assertTrue(cursor.isNull(1))
            }
        }

        val database = Room.databaseBuilder(RuntimeEnvironment.getApplication(), SproutDatabase::class.java, TEST_DB)
            .addMigrations(*Migrations.ALL)
            .build()
        try {
            assertNull(database.ledgerEventDao().all().single().serverReceivedAtEpochMs)
            assertNull(database.ledgerInboxDao().all().single().serverReceivedAtEpochMs)
        } finally {
            database.close()
        }
    }

    @Test
    fun migration1To7KeepsTheM1Rows() = runTest {
        helper.createDatabase(TEST_DB, 1).use { db -> insertVersion1Rows(db, chore) }

        helper.runMigrationsAndValidate(TEST_DB, 7, true, *Migrations.ALL).use { db ->
            db.query("SELECT hash, serverReceivedAtEpochMs FROM ledger_events").use { cursor ->
                cursor.moveToFirst()
                assertEquals(chore.envelope.hash, cursor.getString(0))
                assertTrue(cursor.isNull(1))
            }
        }
    }

    @Test
    fun migration7To8AddsThePolicyV2ColumnsAsNullAndTheTimeBankClocks() = runTest {
        helper.createDatabase(TEST_DB, 7).use { db ->
            db.insertRow(
                "household_policy",
                "id" to 0,
                "requireParentSignature" to 1,
                "parentSignatureFromLamport" to 4L,
                "fiatCurrency" to "GBP",
                "createdAtEpochMs" to receivedAt,
                "lamport" to 9L,
                "deleted" to 0,
            )
        }

        helper.runMigrationsAndValidate(TEST_DB, 8, true, Migrations.MIGRATION_7_8).use { db ->
            val columns = "requireParentSignature, lamport, chatRetentionDays, dailyGameCapMinor"
            db.query("SELECT $columns FROM household_policy").use { cursor ->
                cursor.moveToFirst()
                assertEquals(1, cursor.getInt(0))
                assertEquals(9L, cursor.getLong(1))
                assertTrue(cursor.isNull(2), "keep chat messages until a parent sets a retention")
                assertTrue(cursor.isNull(3), "the default game cap until a parent sets one")
            }
            db.query("SELECT COUNT(*) FROM time_bank_clocks").use { cursor ->
                cursor.moveToFirst()
                assertEquals(0, cursor.getInt(0))
            }
        }

        val database = Room.databaseBuilder(RuntimeEnvironment.getApplication(), SproutDatabase::class.java, TEST_DB)
            .addMigrations(*Migrations.ALL)
            .build()
        try {
            val policy = assertNotNull(database.householdPolicyDao().get())
            assertNull(policy.chatRetentionDays)
            assertNull(policy.dailyGameCapMinor)
            assertNull(policy.toDomain().chatRetentionDays)
            assertEquals(Money(50), policy.toDomain().dailyGameCap)
        } finally {
            database.close()
        }
    }

    @Test
    fun migration1To8KeepsTheM1Rows() = runTest {
        helper.createDatabase(TEST_DB, 1).use { db -> insertVersion1Rows(db, chore) }

        helper.runMigrationsAndValidate(TEST_DB, 8, true, *Migrations.ALL).use { db ->
            db.query("SELECT hash FROM ledger_events").use { cursor ->
                cursor.moveToFirst()
                assertEquals(chore.envelope.hash, cursor.getString(0))
            }
        }
    }

    @Test
    fun roomOpensTheHelperCreatedDatabase() {
        helper.createDatabase(TEST_DB, SproutDatabase.VERSION).close()
        val database = Room.databaseBuilder(RuntimeEnvironment.getApplication(), SproutDatabase::class.java, TEST_DB)
            .addMigrations(*Migrations.ALL)
            .build()
        try {
            assertEquals(SproutDatabase.VERSION, database.openHelper.writableDatabase.version)
        } finally {
            database.close()
        }
    }

    /** One row in each M1 table that M2 touches, written with the version 1 columns only. */
    private fun insertVersion1Rows(db: SupportSQLiteDatabase, event: LedgerEvent) {
        val row = insertEventRow(db, event)
        val account = Fixtures.main.id.value
        val kid = Fixtures.kid.value
        db.insertRow(
            "accounts",
            "accountId" to account,
            "kidId" to kid,
            "currency" to "GBP",
            "kind" to "MAIN",
            "createdAtEpochMs" to 0L,
        )
        db.insertRow(
            "kids",
            "kidId" to kid,
            "householdId" to Fixtures.household.value,
            "firstName" to "Mia",
            "avatarId" to "avatar-1",
            "birthYearMonth" to YearMonth(2020, 11).toString(),
            "uiTier" to KidUiTier.SEEDLING.name,
            "archived" to 0,
        )
        db.insertRow(
            "balances",
            "accountId" to account,
            "kidId" to kid,
            "currency" to "GBP",
            "amountMinor" to 250L,
            "asOfEpochMs" to receivedAt,
            "lastLamport" to 1L,
        )
        insertOutboxAndClock(db, row)
    }

    /** [event] as a `ledger_events` row with the version 1 columns only. */
    private fun insertEventRow(
        db: SupportSQLiteDatabase,
        event: LedgerEvent,
        origin: EventOrigin = EventOrigin.LOCAL,
    ): LedgerEventEntity {
        val row = LedgerEventMapper.toEntity(event, origin, receivedAt)
        db.insertRow(
            "ledger_events",
            "eventId" to row.eventId.value,
            "householdId" to row.householdId.value,
            "kidId" to row.kidId.value,
            "accountId" to row.accountId.value,
            "authorUid" to row.authorUid.value,
            "authorDeviceId" to row.authorDeviceId.value,
            "authorRole" to row.authorRole.name,
            "lamport" to row.lamport,
            "createdAtEpochMs" to row.createdAtEpochMs,
            "type" to row.type,
            "currency" to Converters.currencyToString(row.currency),
            "schemaVersion" to row.schemaVersion,
            "prevHash" to row.prevHash,
            "hash" to row.hash,
            "signature" to row.signature,
            "payloadJson" to row.payloadJson,
            "receivedAtEpochMs" to row.receivedAtEpochMs,
            "origin" to row.origin.name,
            "quarantined" to 0,
        )
        return row
    }

    private fun insertOutboxAndClock(db: SupportSQLiteDatabase, row: LedgerEventEntity) {
        db.insertRow(
            "outbox",
            "kind" to OutboxKind.LEDGER_EVENT.name,
            "refId" to "${row.eventId.value}:${row.hash}",
            "payload" to row.payloadJson,
            "createdAtEpochMs" to receivedAt,
        )
        db.insertRow("device_state", "id" to 0, "deviceId" to row.authorDeviceId.value, "lamport" to 1L)
    }

    /** A version 5 submission with a photo that was never uploaded (no blob id column yet). */
    private fun insertSubmissionWithPhoto(db: SupportSQLiteDatabase) = db.insertRow(
        "chore_submissions",
        "submissionId" to "sub-1",
        "choreId" to "chore-1",
        "dueOn" to "2026-11-02",
        "kidId" to "kid-1",
        "submittedAtEpochMs" to receivedAt,
        "photoFileName" to "a.photo",
        "photoSha256" to "0".repeat(64),
        "status" to "SUBMITTED",
        "lamport" to 3L,
    )

    private fun assertSubmissionWithoutBlob(db: SupportSQLiteDatabase) =
        db.query("SELECT photoFileName, photoBlobId FROM chore_submissions").use { cursor ->
            cursor.moveToFirst()
            assertEquals("a.photo", cursor.getString(0))
            assertTrue(cursor.isNull(1), "the kid's device has not uploaded it yet")
        }

    private fun SupportSQLiteDatabase.insertRow(table: String, vararg values: Pair<String, Any?>) {
        val content = ContentValues()
        values.forEach { (column, value) ->
            when (value) {
                null -> content.putNull(column)
                is String -> content.put(column, value)
                is Int -> content.put(column, value)
                is Long -> content.put(column, value)
                is ByteArray -> content.put(column, value)
                else -> error("unsupported column type for $column")
            }
        }
        insert(table, SQLiteDatabase.CONFLICT_ABORT, content)
    }

    private companion object {
        const val TEST_DB = "migration-test.db"
        val CHAT_TABLES = listOf("messages", "message_seq", "thread_keys", "blobs", "read_marks")
        val NEW_TABLES = listOf(
            "chores",
            "chore_submissions",
            "rewards",
            "allowance_schedules",
            "savings_goals",
            "interest_policy",
            "pending_parent_actions",
            "photos",
            "household_policy",
        )
        val NEW_TABLES_3 = listOf("devices", "parent_keys", "ledger_inbox", "document_inbox", "sync_cursor", "session")
    }
}

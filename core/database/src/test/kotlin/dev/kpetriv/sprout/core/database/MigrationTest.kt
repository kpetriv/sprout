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
        val row = LedgerEventMapper.toEntity(event, EventOrigin.LOCAL, receivedAt)
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

    private fun SupportSQLiteDatabase.insertRow(table: String, vararg values: Pair<String, Any?>) {
        val content = ContentValues()
        values.forEach { (column, value) ->
            when (value) {
                null -> content.putNull(column)
                is String -> content.put(column, value)
                is Int -> content.put(column, value)
                is Long -> content.put(column, value)
                else -> error("unsupported column type for $column")
            }
        }
        insert(table, SQLiteDatabase.CONFLICT_ABORT, content)
    }

    private companion object {
        const val TEST_DB = "migration-test.db"
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

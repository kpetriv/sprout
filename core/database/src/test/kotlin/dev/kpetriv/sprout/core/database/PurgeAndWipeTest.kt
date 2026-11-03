package dev.kpetriv.sprout.core.database

import dev.kpetriv.sprout.core.database.entity.AuditAction
import dev.kpetriv.sprout.core.database.entity.RegistrationStatus
import dev.kpetriv.sprout.core.database.usecase.WipeLocalDataUseCase
import dev.kpetriv.sprout.core.domainhousehold.Chore
import dev.kpetriv.sprout.core.domainhousehold.ChoreReward
import dev.kpetriv.sprout.core.domainhousehold.PendingParentAction
import dev.kpetriv.sprout.core.domainhousehold.Reward
import dev.kpetriv.sprout.core.domainhousehold.SavingsGoal
import dev.kpetriv.sprout.core.domainledger.GamePlayed
import dev.kpetriv.sprout.core.domainledger.Money
import dev.kpetriv.sprout.core.model.CalendarDate
import dev.kpetriv.sprout.core.model.ChoreId
import dev.kpetriv.sprout.core.model.ChoreSchedule
import dev.kpetriv.sprout.core.model.GoalId
import dev.kpetriv.sprout.core.model.RewardId
import dev.kpetriv.sprout.core.model.RewardKind
import dev.kpetriv.sprout.core.model.Role
import dev.kpetriv.sprout.core.model.ScheduleId
import dev.kpetriv.sprout.core.model.UserId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class PurgeAndWipeTest {
    private val household = testHousehold()
    private val today = CalendarDate(2026, 11, 2)

    @After
    fun close() = household.close()

    /** The trial household with a bit of everything: kid, accounts, events, documents, a photo, a queued action. */
    private suspend fun seedTrial() {
        val (kid, accounts) = household.addKid()
        val main = with(household) { accounts.money() }
        household.writer.append(kid.id, main.id) { GamePlayed(it, household.gbp, Money(5), "memory", 3, today) }
        household.policies.ensure(household.gbp)
        household.chores.upsert(
            Chore(
                ChoreId("bed"),
                LocalTrial.HOUSEHOLD,
                kid.id,
                "Bed",
                reward = ChoreReward(money = Money(20)),
                schedule = ChoreSchedule.Daily.EVERY_DAY,
                createdAt = household.clock.now(),
            ),
        )
        val photo = household.photos.save(kid.id, byteArrayOf(1, 2, 3))
        household.chores.submit(ChoreId("bed"), today, photo = photo)
        household.rewards.upsert(
            Reward(RewardId("treat"), LocalTrial.HOUSEHOLD, "Treat", RewardKind.TREAT, Money(100), household.gbp),
        )
        household.goals.upsert(SavingsGoal(GoalId("bike"), kid.id, "Bike", Money(5_000), today))
        household.pending.enqueue(
            PendingParentAction.PayAllowance(
                ScheduleId("weekly"),
                kid.id,
                main.id,
                Money(300),
                household.gbp,
                "2026-W45",
                today,
            ),
        )
        household.interest.set(dev.kpetriv.sprout.core.model.InterestPolicy(LocalTrial.HOUSEHOLD, 100, 1))
    }

    private fun count(table: String): Int =
        household.database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table").use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }

    @Test
    fun purgeDeletesTheTrialHouseholdAndKeepsTheNewSession() = runTest {
        seedTrial()
        val trialKey = dev.kpetriv.sprout.core.database.entity.ParentKeyEntity(
            fingerprint = "a".repeat(64),
            publicKey = byteArrayOf(1),
            uid = LocalTrial.PARENT,
            deviceId = household.deviceId,
            validFromLamport = 0,
            revokedAtLamport = null,
            registeredAtEpochMs = 0,
        )
        household.database.parentKeyDao().upsert(trialKey)
        val realKey = trialKey.copy(fingerprint = "b".repeat(64), uid = UserId("parent-uid"))
        household.database.parentKeyDao().upsert(realKey)
        household.database.syncCursorDao().upsert(
            listOf(
                dev.kpetriv.sprout.core.database.entity.SyncCursorEntity(LocalTrial.HOUSEHOLD, "events", "1"),
                dev.kpetriv.sprout.core.database.entity.SyncCursorEntity(HOME, "events", "2"),
            ),
        )
        assertTrue(household.photoDirectory.listFiles().orEmpty().isNotEmpty())
        val real =
            Session(
                UserId("parent-uid"),
                HOME,
                Role.PARENT,
                null,
                household.deviceId,
                RegistrationStatus.ACTIVE,
                household.clock.now(),
            )
        household.sessions.save(real)

        val report = household.purge()

        for (table in PURGED_TABLES) assertEquals(0, count(table), table)
        assertTrue(report.total > 0)
        assertEquals(1, report.counts.getValue("kids"))
        assertEquals(1, report.counts.getValue("photos"))
        assertEquals(1, report.counts.getValue("parent_keys"), "only the trial parent's key")
        assertEquals(listOf("b".repeat(64)), household.database.parentKeyDao().all().map { it.fingerprint })
        assertEquals(1, report.counts.getValue("sync_cursor"))
        assertEquals(listOf("2"), household.database.syncCursorDao().all(HOME).map { it.cursor })
        assertTrue(household.photoDirectory.listFiles().orEmpty().isEmpty(), "photo files are deleted")
        assertEquals(real, household.sessions.get())
        val audit = household.database.auditLogDao().all()
        assertTrue(audit.none { it.actorUid == LocalTrial.PARENT })
        val purged = audit.single { it.action == AuditAction.LOCAL_HOUSEHOLD_PURGED }
        assertEquals(real.uid, purged.actorUid)
        assertTrue("\"kids\":1" in purged.detailsJson)
    }

    @Test
    fun purgeWithoutANewSessionAlsoRemovesTheTrialSession() = runTest {
        seedTrial()
        household.purge()
        assertNull(household.sessions.get())
        assertEquals(0, count("session"))
    }

    @Test
    fun wipeClearsTheSessionClosesTheDatabaseAndDeletesTheFiles() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val name = "wipe-${UUID.randomUUID()}.db"
        val database = fileDatabase(name)
        val sessions = RoomSessionStore(database, household.clock)
        sessions.ensureLocalTrialSession(household.deviceId)
        val photos = File(context.noBackupFilesDir, "photos-${UUID.randomUUID()}").apply { mkdirs() }
        File(photos, "a.photo").writeBytes(byteArrayOf(1))
        val secrets = File(context.noBackupFilesDir, "secrets-${UUID.randomUUID()}").apply { mkdirs() }
        File(secrets, "db.passphrase.bin").writeBytes(byteArrayOf(1))
        File(secrets, "photos.key.bin").writeBytes(byteArrayOf(1))
        val files = LocalDataFiles(context.getDatabasePath(name), photos, secrets)
        assertTrue(files.databaseFile.exists())

        assertTrue(WipeLocalDataUseCase(database, sessions, files, Dispatchers.Unconfined)(), "everything is gone")

        assertFalse(database.isOpen)
        assertTrue(files.databaseFiles.none { it.exists() })
        assertFalse(photos.exists())
        assertTrue(secrets.listFiles().orEmpty().isEmpty())
        // Reopening finds an empty database: no session, so the app lands on onboarding.
        val reopened = fileDatabase(name)
        try {
            assertNull(RoomSessionStore(reopened, household.clock).get())
        } finally {
            reopened.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun aWipeOfAnAlreadyClosedDatabaseStillDeletesTheFiles() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val name = "wipe-closed-${UUID.randomUUID()}.db"
        val database = fileDatabase(name)
        val sessions = RoomSessionStore(database, household.clock)
        sessions.ensureLocalTrialSession(household.deviceId)
        database.close()
        val photos = File(context.noBackupFilesDir, "photos-${UUID.randomUUID()}")
        val secrets = File(context.noBackupFilesDir, "secrets-${UUID.randomUUID()}").apply { mkdirs() }
        File(secrets, "pin.hash.bin").writeBytes(byteArrayOf(1))
        val files = LocalDataFiles(context.getDatabasePath(name), photos, secrets)

        assertTrue(WipeLocalDataUseCase(database, sessions, files, Dispatchers.Unconfined)())
        assertTrue(files.databaseFiles.none { it.exists() })
        assertTrue(secrets.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun aWipeThatLeavesAFileBehindSaysSo() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val name = "wipe-stuck-${UUID.randomUUID()}.db"
        val database = fileDatabase(name)
        val sessions = RoomSessionStore(database, household.clock)
        val locked = File(context.noBackupFilesDir, "locked-${UUID.randomUUID()}").apply { mkdirs() }
        val photos = File(locked, "photos").apply { mkdirs() }
        File(photos, "a.photo").writeBytes(byteArrayOf(1))
        val secrets = File(context.noBackupFilesDir, "secrets-${UUID.randomUUID()}").apply { mkdirs() }
        val files = LocalDataFiles(context.getDatabasePath(name), photos, secrets)
        // The photo directory's parent refuses deletions, so the photo directory itself cannot go.
        check(photos.setWritable(false) && locked.setWritable(false))
        try {
            assertFalse(WipeLocalDataUseCase(database, sessions, files, Dispatchers.Unconfined)())
            assertTrue(files.databaseFiles.none { it.exists() }, "the other files still go")
        } finally {
            locked.setWritable(true)
            photos.setWritable(true)
            locked.deleteRecursively()
        }
    }

    private companion object {
        val PURGED_TABLES = listOf(
            "kids",
            "accounts",
            "ledger_events",
            "balances",
            "anomalies",
            "chores",
            "chore_submissions",
            "rewards",
            "allowance_schedules",
            "savings_goals",
            "interest_policy",
            "pending_parent_actions",
            "photos",
            "household_policy",
            "outbox",
            "ledger_inbox",
            "document_inbox",
        )
    }
}

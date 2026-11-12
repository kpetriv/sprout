package dev.kpetriv.sprout.core.database

import dev.kpetriv.sprout.core.crypto.EncryptionKeyStore
import dev.kpetriv.sprout.core.crypto.FilesDirBlobStorage
import dev.kpetriv.sprout.core.crypto.InMemoryBlobStorage
import dev.kpetriv.sprout.core.crypto.InMemoryKeyWrapper
import dev.kpetriv.sprout.core.crypto.JceX25519KeyAgreement
import dev.kpetriv.sprout.core.crypto.SecretStoreEncryptionKeyStore
import dev.kpetriv.sprout.core.crypto.SecretUnavailableException
import dev.kpetriv.sprout.core.crypto.WrappedSecretStore
import dev.kpetriv.sprout.core.database.entity.AuditAction
import dev.kpetriv.sprout.core.database.entity.DeviceStateEntity
import dev.kpetriv.sprout.core.database.entity.MessageEntity
import dev.kpetriv.sprout.core.database.entity.MessageSeqEntity
import dev.kpetriv.sprout.core.database.entity.MessageStatus
import dev.kpetriv.sprout.core.database.entity.ReadMarkEntity
import dev.kpetriv.sprout.core.database.entity.RegistrationStatus
import dev.kpetriv.sprout.core.database.entity.ThreadKeyEntity
import dev.kpetriv.sprout.core.database.usecase.ForgetChatKeyUseCase
import dev.kpetriv.sprout.core.database.usecase.WipeLocalDataUseCase
import dev.kpetriv.sprout.core.domainhousehold.Chore
import dev.kpetriv.sprout.core.domainhousehold.ChoreReward
import dev.kpetriv.sprout.core.domainhousehold.PendingParentAction
import dev.kpetriv.sprout.core.domainhousehold.Reward
import dev.kpetriv.sprout.core.domainhousehold.SavingsGoal
import dev.kpetriv.sprout.core.domainledger.GamePlayed
import dev.kpetriv.sprout.core.domainledger.JvmSha256Hasher
import dev.kpetriv.sprout.core.domainledger.Money
import dev.kpetriv.sprout.core.domainledger.chat.BlobContext
import dev.kpetriv.sprout.core.domainledger.chat.MessageKind
import dev.kpetriv.sprout.core.domainledger.toHex
import dev.kpetriv.sprout.core.model.BlobId
import dev.kpetriv.sprout.core.model.CalendarDate
import dev.kpetriv.sprout.core.model.ChoreId
import dev.kpetriv.sprout.core.model.ChoreSchedule
import dev.kpetriv.sprout.core.model.DeviceId
import dev.kpetriv.sprout.core.model.GoalId
import dev.kpetriv.sprout.core.model.HouseholdId
import dev.kpetriv.sprout.core.model.RewardId
import dev.kpetriv.sprout.core.model.RewardKind
import dev.kpetriv.sprout.core.model.Role
import dev.kpetriv.sprout.core.model.ScheduleId
import dev.kpetriv.sprout.core.model.ThreadId
import dev.kpetriv.sprout.core.model.UserId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.security.SecureRandom
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
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
        household.writer.append(kid.id, main.id) { GamePlayed(it, household.gbp, Money(5), "memory.sprout", 6, today) }
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
    fun purgeAlsoDeletesTheTrialHouseholdsChatRowsAndBlobs() = runTest {
        val (kid, _) = household.addKid()
        val thread = kid.id.value
        val messages = household.database.messageDao()
        messages.upsert(
            MessageEntity(
                "m-1", LocalTrial.HOUSEHOLD, thread, LocalTrial.PARENT, household.deviceId, Role.PARENT, 1, 1, 0, 0,
                MessageKind.TEXT, "AA==", null, null, 1, byteArrayOf(1), MessageStatus.SENT_LOCAL, null, 0,
            ),
        )
        messages.upsertSeq(MessageSeqEntity(thread, household.deviceId, 2))
        messages.upsertReadMark(ReadMarkEntity(thread, 0))
        household.database.threadKeyDao().upsert(
            ThreadKeyEntity(
                thread, 0, household.deviceId, ByteArray(32), "$thread.0", "{}", 1, LocalTrial.PARENT, null, 0,
            ),
        )
        val context =
            BlobContext(LocalTrial.HOUSEHOLD, BlobId("b-1"), ThreadId(thread), kid.id, null, household.deviceId, 0)
        household.blobCache.put(context, JvmSha256Hasher.sha256(byteArrayOf(9)).toHex(), byteArrayOf(9))
        assertTrue(household.blobDirectory.listFiles().orEmpty().isNotEmpty())

        val report = household.purge()

        for (table in listOf("messages", "message_seq", "thread_keys", "read_marks", "blobs")) {
            assertEquals(0, count(table), table)
            assertEquals(1, report.counts.getValue(table), table)
        }
        assertTrue(household.blobDirectory.listFiles().orEmpty().isEmpty(), "blob files are deleted")
    }

    /** A file database, a blob in the blob cache and one blob per [LocalDataFiles.SECRET_NAMES]. */
    private fun filesWithEverySecret(prefix: String): Pair<SproutDatabase, LocalDataFiles> {
        val context = RuntimeEnvironment.getApplication()
        val name = "$prefix-${UUID.randomUUID()}.db"
        val database = fileDatabase(name)
        val photos = File(context.noBackupFilesDir, "photos-${UUID.randomUUID()}")
        val blobs = File(context.noBackupFilesDir, "blobs-${UUID.randomUUID()}").apply { mkdirs() }
        File(blobs, "b-1.blob").writeBytes(byteArrayOf(1))
        val secrets = File(context.noBackupFilesDir, "secrets-${UUID.randomUUID()}").apply { mkdirs() }
        LocalDataFiles.SECRET_NAMES.forEach { File(secrets, "$it.bin").writeBytes(byteArrayOf(1)) }
        return database to LocalDataFiles(context.getDatabasePath(name), photos, secrets, blobs)
    }

    private fun LocalDataFiles.chatKey() = File(secretsDirectory, "${EncryptionKeyStore.SECRET_NAME}.bin")

    // A revoked device, a household that is gone, a kid's phone, an erase while unverified: nothing of it stays.
    @Test
    fun wipeDeletesTheBlobCacheAndTheChatKey() = runTest {
        val (database, files) = filesWithEverySecret("wipe-chat")
        assertTrue(EncryptionKeyStore.SECRET_NAME in LocalDataFiles.SECRET_NAMES)
        assertTrue(BlobCache.BLOB_KEY_SECRET in LocalDataFiles.SECRET_NAMES)
        assertEquals(files.secretFiles(EncryptionKeyStore.SECRET_NAME).first(), files.chatKey())

        assertTrue(wipe(database, files)(keepDeviceIdentity = false))

        assertFalse(files.blobDirectory.exists())
        assertFalse(files.chatKey().exists())
        assertTrue(files.secretsDirectory.listFiles().orEmpty().isEmpty())
    }

    /** A keeper over an in-memory secret store, for wipes whose secrets are plain files. */
    private fun memoryKeeper(database: SproutDatabase) = ChatIdentityKeeper(
        database,
        WrappedSecretStore(InMemoryKeyWrapper(), InMemoryBlobStorage(), SecureRandom(), Dispatchers.Unconfined),
    )

    private fun wipe(database: SproutDatabase, files: LocalDataFiles, cache: File? = null) = WipeLocalDataUseCase(
        database,
        RoomSessionStore(database, household.clock),
        files,
        memoryKeeper(database),
        Dispatchers.Unconfined,
        cache,
    )

    /**
     * The production secrets directory (`noBackupFilesDir/sprout_secrets`) with a real [FilesDirBlobStorage] behind a
     * [WrappedSecretStore] whose wrapping key survives every wipe (the Keystore key), so files and secrets agree.
     */
    private inner class RealSecrets(prefix: String) : AutoCloseable {
        val context = RuntimeEnvironment.getApplication()
        val secrets = File(context.noBackupFilesDir, LocalDataFiles.SECRETS_DIRECTORY_NAME).apply {
            deleteRecursively()
        }
        private val wrapper = InMemoryKeyWrapper()
        private val name = "$prefix-${UUID.randomUUID()}.db"
        val files =
            LocalDataFiles(
                context.getDatabasePath(name),
                File(context.noBackupFilesDir, "p-${UUID.randomUUID()}"),
                secrets,
            )

        /** A fresh store over the same files: what the next process sees. */
        fun store() = WrappedSecretStore(
            wrapper,
            FilesDirBlobStorage(context, Dispatchers.Unconfined),
            SecureRandom(),
            Dispatchers.Unconfined,
        )

        fun keys() = SecretStoreEncryptionKeyStore(store(), JceX25519KeyAgreement(SecureRandom()))

        fun database() = fileDatabase(name)

        fun names(): Set<String> = secrets.listFiles().orEmpty().map { it.name }.toSet()

        override fun close() {
            secrets.deleteRecursively()
            context.deleteDatabase(name)
        }
    }

    private val home = HouseholdId("home")

    private fun parentSession(role: Role = Role.PARENT) = Session(
        UserId("parent-1"),
        home,
        role,
        if (role == Role.KID) KID_X else null,
        household.deviceId,
        RegistrationStatus.ACTIVE,
        household.clock.now(),
    )

    // The sign-out of a parent keeps the device id, so the registry still lists this key for it (D-REL-6), and the
    // record of its own chat counters goes with it (D-REL-30).
    @Test
    fun aParentSignOutWipeKeepsTheChatKeyAndItsRecordAndNothingElse() = runTest {
        RealSecrets("wipe-signout").use { real ->
            val registered = real.keys().publicKey()
            // A write AtomicFile never finished: the next read through the store discards it.
            File(real.secrets, "${EncryptionKeyStore.SECRET_NAME}.bin.new").writeBytes(byteArrayOf(2))
            (LocalDataFiles.SECRET_NAMES - ChatIdentityKeeper.NAMES).forEach {
                File(real.secrets, "$it.bin").writeBytes(byteArrayOf(1))
            }
            val database = real.database()
            RoomSessionStore(database, household.clock).save(parentSession())
            database.messageDao().upsertSeq(MessageSeqEntity("kid-x", household.deviceId, 4, lastLamport = 9))
            database.messageDao().upsertSeq(MessageSeqEntity("kid-x", DeviceId("someone-else"), 40, lastLamport = 90))
            database.deviceStateDao().upsert(DeviceStateEntity(deviceId = household.deviceId, lamport = 12))
            val keeper = ChatIdentityKeeper(database, real.store())
            assertEquals(
                listOf(EncryptionKeyStore.SECRET_NAME, ChatIdentityKeeper.RECORD),
                LocalDataFiles.DEVICE_IDENTITY_SECRETS,
            )

            assertTrue(
                WipeLocalDataUseCase(
                    database,
                    RoomSessionStore(database, household.clock),
                    real.files,
                    keeper,
                    Dispatchers.Unconfined,
                )(
                    keepDeviceIdentity = true,
                ),
                "complete: only the kept key and its record are left",
            )

            assertTrue(real.files.databaseFiles.none { it.exists() })
            assertEquals(
                setOf("chat.x25519.v1.bin", "chat.identity.v1.bin"),
                real.names(),
            )
            assertEquals(registered, real.keys().keyPair(createIfMissing = false).use { it.publicKey })
            assertEquals(
                KeptChatIdentity(home, household.deviceId, 12, listOf(OwnThreadMark(ThreadId("kid-x"), 4, 9))),
                ChatIdentityKeeper(fileDatabase("unused-${UUID.randomUUID()}.db"), real.store()).kept(),
            )
        }
    }

    // A kid's phone never keeps its key (D-REL-28): the wipe erases it although the caller asked to keep it.
    @Test
    fun aSignOutWipeWithoutAParentRecordErasesTheChatKey() = runTest {
        RealSecrets("wipe-kid").use { real ->
            real.keys().publicKey()
            val database = real.database()
            RoomSessionStore(database, household.clock).save(parentSession(Role.KID))
            val keeper = ChatIdentityKeeper(database, real.store())

            assertTrue(
                WipeLocalDataUseCase(
                    database,
                    RoomSessionStore(database, household.clock),
                    real.files,
                    keeper,
                    Dispatchers.Unconfined,
                )(
                    keepDeviceIdentity = true,
                ),
            )

            assertTrue(real.names().isEmpty())
        }
    }

    // A resumed wipe whose first attempt wrote the record and cleared the session keeps it; a record found while a
    // session is still here predates that session (a restore that had not deleted it yet) and keeps nothing (D-REL-38).
    @Test
    fun aRecordAloneKeepsTheKeyOnlyWhenNoSessionIsLeft() = runTest {
        RealSecrets("wipe-resumed").use { real ->
            real.keys().publicKey()
            real.store().writeRecord(ChatIdentityKeeper.RECORD, KeptChatIdentity(home, household.deviceId).encode())
            val database = real.database()
            val sessions = RoomSessionStore(database, household.clock)
            // A session whose sign-out keeps nothing of its own (a kid's), next to an older record.
            sessions.save(parentSession(Role.KID))

            assertTrue(
                WipeLocalDataUseCase(
                    database,
                    sessions,
                    real.files,
                    ChatIdentityKeeper(database, real.store()),
                    Dispatchers.Unconfined,
                )(keepDeviceIdentity = true),
            )
            assertTrue(real.names().isEmpty(), "a stale record keeps nothing")

            real.keys().publicKey()
            real.store().writeRecord(ChatIdentityKeeper.RECORD, KeptChatIdentity(home, household.deviceId).encode())
            val resumed = real.database()
            assertTrue(
                WipeLocalDataUseCase(
                    resumed,
                    RoomSessionStore(resumed, household.clock),
                    real.files,
                    ChatIdentityKeeper(resumed, real.store()),
                    Dispatchers.Unconfined,
                )(keepDeviceIdentity = true),
            )
            assertEquals(setOf("chat.x25519.v1.bin", "chat.identity.v1.bin"), real.names(), "the first attempt's")
        }
    }

    // A new device id (a new household, a second parent phone, a kid link) never inherits the kept key, and a failed
    // registration does not destroy it either (D-REL-7, D-REL-29).
    @Test
    fun aStashedKeyDoesNotOpenUntilItsOwnDeviceIsRestored() = runTest {
        RealSecrets("wipe-stash").use { real ->
            val registered = real.keys().publicKey()
            val database = real.database()
            RoomSessionStore(database, household.clock).save(parentSession())
            val keeper = ChatIdentityKeeper(database, real.store())
            assertTrue(
                WipeLocalDataUseCase(
                    database,
                    RoomSessionStore(database, household.clock),
                    real.files,
                    keeper,
                    Dispatchers.Unconfined,
                )(
                    keepDeviceIdentity = true,
                ),
            )
            val fresh = ChatIdentityKeeper(fileDatabase("unused-${UUID.randomUUID()}.db"), real.store())

            ForgetChatKeyUseCase(fresh)(household.deviceId)

            assertFalse(fresh.hasKey())
            assertFailsWith<SecretUnavailableException> { real.keys().keyPair(createIfMissing = false) }
            assertEquals(setOf("chat.x25519.v1.stash.bin", "chat.identity.v1.stash.bin"), real.names())
            // A registration attempt makes a key of its own (registerParentDevice's encryption key), then fails.
            val attempt = real.keys().publicKey()
            assertNotEquals(registered, attempt, "a new device id gets a fresh key")
            // Trying again keeps the stash and drops the failed attempt's key.
            ForgetChatKeyUseCase(fresh)(household.deviceId)
            assertEquals(household.deviceId, fresh.stashed()?.deviceId)
            assertFalse(fresh.hasKey())
            real.keys().publicKey()

            // The parent goes back to the old account: the stash replaces whatever key is live.
            assertTrue(fresh.unstash(household.deviceId))
            assertEquals(registered, real.keys().keyPair(createIfMissing = false).use { it.publicKey })
            assertEquals(home, fresh.kept()?.householdId, "the sign-out's record comes back with it")
            assertNull(fresh.stashed())

            // A registration the server accepted deletes the stash for good.
            ForgetChatKeyUseCase(fresh)(household.deviceId)
            fresh.dropStash()
            assertTrue(real.names().isEmpty())
            assertFalse(fresh.unstash(household.deviceId))
        }
    }

    @Test
    fun aStashOfAnotherDeviceIsStaleAndNeverRestored() = runTest {
        val store =
            WrappedSecretStore(InMemoryKeyWrapper(), InMemoryBlobStorage(), SecureRandom(), Dispatchers.Unconfined)
        val own = ChatIdentityKeeper(household.database, store)
        SecretStoreEncryptionKeyStore(store, JceX25519KeyAgreement(SecureRandom())).publicKey()
        own.stash(DeviceId("old-device"))
        assertEquals(DeviceId("old-device"), own.stashed()?.deviceId)
        assertNull(own.stashed()?.householdId, "a key no sign-out kept is tagged with its device only")

        assertFalse(own.unstash(DeviceId("new-device")))
        assertNull(own.stashed())
    }

    @Test
    fun forgettingTheChatKeyLeavesTheOtherSecretsAlone() = runTest {
        RealSecrets("forget-only").use { real ->
            real.keys().publicKey()
            val others = LocalDataFiles.SECRET_NAMES - ChatIdentityKeeper.NAMES
            others.forEach { File(real.secrets, "$it.bin").writeBytes(byteArrayOf(1)) }
            val keeper = ChatIdentityKeeper(fileDatabase("unused-${UUID.randomUUID()}.db"), real.store())

            keeper.stash(household.deviceId)
            keeper.dropStash()
            keeper.forget()

            assertEquals(others.map { "$it.bin" }.toSet(), real.names())
        }
    }

    @Test
    fun wipeClearsTheCacheDirectoryWithItsVoiceNoteTempFiles() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val name = "wipe-cache-${UUID.randomUUID()}.db"
        val database = fileDatabase(name)
        val photos = File(context.noBackupFilesDir, "photos-${UUID.randomUUID()}")
        val secrets = File(context.noBackupFilesDir, "secrets-${UUID.randomUUID()}").apply { mkdirs() }
        val cache = File(context.cacheDir, "cache-${UUID.randomUUID()}").apply { mkdirs() }
        val clip = File(cache, "play123.m4a").apply { writeBytes(byteArrayOf(1)) }
        val nested = File(cache, "capture").apply { mkdirs() }
        File(nested, "a.jpg").writeBytes(byteArrayOf(2))
        val files = LocalDataFiles(context.getDatabasePath(name), photos, secrets)

        assertTrue(wipe(database, files, cache)())

        assertFalse(clip.exists())
        assertFalse(nested.exists())
        assertTrue(cache.isDirectory, "the directory itself stays")
        cache.delete()
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

        assertTrue(wipe(database, files)(), "everything is gone")

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

        assertTrue(wipe(database, files)())
        assertTrue(files.databaseFiles.none { it.exists() })
        assertTrue(secrets.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun aWipeThatLeavesAFileBehindSaysSo() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val name = "wipe-stuck-${UUID.randomUUID()}.db"
        val database = fileDatabase(name)
        val locked = File(context.noBackupFilesDir, "locked-${UUID.randomUUID()}").apply { mkdirs() }
        val photos = File(locked, "photos").apply { mkdirs() }
        File(photos, "a.photo").writeBytes(byteArrayOf(1))
        val secrets = File(context.noBackupFilesDir, "secrets-${UUID.randomUUID()}").apply { mkdirs() }
        val files = LocalDataFiles(context.getDatabasePath(name), photos, secrets)
        // The photo directory's parent refuses deletions, so the photo directory itself cannot go.
        check(photos.setWritable(false) && locked.setWritable(false))
        try {
            assertFalse(wipe(database, files)())
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

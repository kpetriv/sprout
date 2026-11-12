package dev.kpetriv.sprout.core.database

import dev.kpetriv.sprout.core.crypto.BlobStorage
import dev.kpetriv.sprout.core.crypto.InMemoryBlobStorage
import dev.kpetriv.sprout.core.crypto.InMemoryKeyWrapper
import dev.kpetriv.sprout.core.crypto.JceX25519KeyAgreement
import dev.kpetriv.sprout.core.crypto.SecretStoreEncryptionKeyStore
import dev.kpetriv.sprout.core.crypto.WrappedSecretStore
import dev.kpetriv.sprout.core.crypto.X25519PublicKey
import dev.kpetriv.sprout.core.database.ChatIdentityKeeper.Companion.KEY
import dev.kpetriv.sprout.core.database.ChatIdentityKeeper.Companion.RECORD
import dev.kpetriv.sprout.core.database.ChatIdentityKeeper.Companion.STASH_KEY
import dev.kpetriv.sprout.core.database.ChatIdentityKeeper.Companion.STASH_RECORD
import dev.kpetriv.sprout.core.database.entity.DeviceStateEntity
import dev.kpetriv.sprout.core.database.entity.MessageSeqEntity
import dev.kpetriv.sprout.core.database.entity.RegistrationStatus
import dev.kpetriv.sprout.core.database.entity.SyncCursorEntity
import dev.kpetriv.sprout.core.model.DeviceId
import dev.kpetriv.sprout.core.model.HouseholdId
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.Role
import dev.kpetriv.sprout.core.model.ThreadId
import dev.kpetriv.sprout.core.model.UserId
import dev.kpetriv.sprout.core.sync.SyncCursor
import dev.kpetriv.sprout.core.time.FakeClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.security.SecureRandom
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The chat key's record and stash (D-REL-30, D-REL-35, D-REL-36, D-REL-38): the counters a restore seeds, and the
 * stash steps, each one cut short by a process death and run again.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ChatIdentityKeeperTest {
    private val blobs = InMemoryBlobStorage()
    private val store = WrappedSecretStore(InMemoryKeyWrapper(), blobs, SecureRandom(), Dispatchers.Unconfined)
    private val keys = SecretStoreEncryptionKeyStore(store, JceX25519KeyAgreement(SecureRandom()))
    private val databases = mutableListOf<SproutDatabase>()
    private val device = DeviceId("d".repeat(64))
    private val home = HouseholdId("home")
    private val thread = ThreadId("kid-x")

    @After
    fun close() = databases.forEach { it.close() }

    /** A fresh database for this phone (what a restore after a sign-out wipe starts from), with its keeper. */
    private fun phone(): Pair<SproutDatabase, ChatIdentityKeeper> {
        val database = inMemoryDatabase().also { databases += it }
        return database to ChatIdentityKeeper(database, store)
    }

    private fun session(deviceId: DeviceId = device, householdId: HouseholdId = home, role: Role = Role.PARENT) =
        Session(
            UserId("parent-1"),
            householdId,
            role,
            if (role == Role.KID) KidId("kid-x") else null,
            deviceId,
            RegistrationStatus.ACTIVE,
            Instant.fromEpochSeconds(0),
        )

    private suspend fun liveKey(): X25519PublicKey = keys.keyPair(createIfMissing = false).use { it.publicKey }

    /** A parent's phone that sent up to `seq` 4 (lamport 9) in [thread], with its clock at 12, then signed out. */
    private suspend fun signedOut(deviceLamport: Long = 12): X25519PublicKey {
        val key = keys.publicKey()
        val (database, keeper) = phone()
        RoomSessionStore(database, FakeClock()).save(session())
        database.messageDao().upsertSeq(MessageSeqEntity(thread.value, device, 5, lastLamport = 9))
        database.deviceStateDao().upsert(DeviceStateEntity(deviceId = device, lamport = deviceLamport))
        assertTrue(keeper.keepForSignOut())
        return key
    }

    // ---- seeding (D-REL-30) ----

    @Test
    fun `a restore raises the device clock to the kept one and sets the seq floors`() = runTest {
        signedOut(deviceLamport = 12)
        val (database, keeper) = phone()

        assertTrue(keeper.seed(session()))

        assertEquals(12L, database.deviceStateDao().get()?.lamport, "the device clock goes on from the kept one")
        assertEquals(5L, database.ownSeqFloor(home, thread))
        assertEquals(KeptChatIdentity(home, device, 12, listOf(OwnThreadMark(thread, 5, 9))), keeper.kept())
    }

    @Test
    fun `the device clock also goes above every thread's last lamport`() = runTest {
        keys.publicKey()
        store.writeRecord(RECORD, KeptChatIdentity(home, device, 3, listOf(OwnThreadMark(thread, 2, 30))).encode())
        val (database, keeper) = phone()

        assertTrue(keeper.seed(session()))

        assertEquals(30L, database.deviceStateDao().get()?.lamport)
    }

    @Test
    fun `a record is refused for another device, another household, a kid or the trial`() = runTest {
        signedOut()
        val (database, keeper) = phone()

        assertFalse(keeper.seed(session(deviceId = DeviceId("e".repeat(64)))))
        assertFalse(keeper.seed(session(householdId = HouseholdId("elsewhere"))))
        assertFalse(keeper.seed(session(role = Role.KID)))
        assertFalse(keeper.seed(Session.localTrial(device, Instant.fromEpochSeconds(0))))

        assertNull(database.deviceStateDao().get())
        assertTrue(database.ownSeqFloors(home).isEmpty())
        assertEquals(home, keeper.kept()?.householdId, "refusing deletes nothing")
    }

    @Test
    fun `two sign-outs in a row carry the floors though nothing was sent in between`() = runTest {
        signedOut(deviceLamport = 12)
        val (database, keeper) = phone()
        assertTrue(keeper.seed(session()))
        RoomSessionStore(database, FakeClock()).save(session())
        keeper.discardRecord()
        assertNull(keeper.kept())

        assertTrue(keeper.keepForSignOut(), "the second sign-out")

        assertEquals(KeptChatIdentity(home, device, 12, listOf(OwnThreadMark(thread, 5, 0))), keeper.kept())
        val (third, restored) = phone()
        assertTrue(restored.seed(session()))
        assertEquals(5L, third.ownSeqFloor(home, thread))
        assertEquals(12L, third.deviceStateDao().get()?.lamport)
    }

    @Test
    fun `a start that finds the session and its own record seeds again and deletes the record`() = runTest {
        signedOut()
        // The process died after the session was saved and before the record went.
        val (database, keeper) = phone()
        assertTrue(keeper.seed(session()))
        RoomSessionStore(database, FakeClock()).save(session())

        val next = ChatIdentityKeeper(database, store)
        assertFalse(next.resumeSeed(session(deviceId = DeviceId("e".repeat(64)))), "not this session's record")
        assertEquals(home, next.kept()?.householdId)
        assertTrue(next.resumeSeed(session()))
        assertNull(next.kept())
        assertEquals(5L, database.ownSeqFloor(home, thread), "seeding twice changes nothing")
        assertEquals(12L, database.deviceStateDao().get()?.lamport)
        assertFalse(next.resumeSeed(session()))
    }

    @Test
    fun `the pull cursor never carries a seq floor`() = runTest {
        val household = TestHousehold(
            org.robolectric.RuntimeEnvironment.getApplication(),
            sessionFor = TestHousehold.parentSession(home, "parent-1"),
        )
        try {
            household.applier.saveCursor(SyncCursor(mapOf("messages" to "c1")))
            household.database.syncCursorDao().upsert(
                listOf(SyncCursorEntity(home, ChatIdentityKeeper.floorKey(thread), "5")),
            )

            assertEquals(mapOf("messages" to "c1"), household.applier.cursor().perCollection)
            assertEquals(5L, household.database.ownSeqFloor(home, thread))
        } finally {
            household.close()
        }
    }

    // ---- stash steps cut short (D-REL-36) ----

    @Test
    fun `a stash cut after its tag was written leaves the live key, and running it again finishes it`() = runTest {
        val key = signedOut()
        val keeper = phone().second
        store.writeRecord(STASH_RECORD, KeptChatIdentity(home, device).encode())

        assertFalse(keeper.hasStash(), "a tag without its key is no stash")
        assertFalse(keeper.unstash(device))
        assertEquals(key, liveKey(), "the live key is untouched")
        assertFalse(store.contains(STASH_RECORD), "and the stray tag is gone")

        store.writeRecord(STASH_RECORD, KeptChatIdentity(home, device).encode())
        keeper.stash(device)
        assertEquals(KeptChatIdentity(home, device, 12, listOf(OwnThreadMark(thread, 5, 9))), keeper.stashed())
        assertFalse(keeper.hasKey())
        assertNull(keeper.kept())
    }

    @Test
    fun `a stash cut after the key moved is finished by the next stash or undone by an unstash`() = runTest {
        val key = signedOut()
        val keeper = phone().second
        val record = requireNotNull(keeper.kept())
        store.writeRecord(STASH_RECORD, record.encode())
        assertTrue(store.rename(KEY, STASH_KEY))

        // Undone: the parent goes back to the same account.
        assertTrue(keeper.unstash(device))
        assertEquals(key, liveKey())
        assertEquals(record, keeper.kept())
        assertFalse(keeper.hasStash())

        // Finished: the next registration attempt stashes again over the same split.
        store.writeRecord(STASH_RECORD, record.encode())
        assertTrue(store.rename(KEY, STASH_KEY))
        keeper.stash(device)
        assertEquals(record, keeper.stashed())
        assertNull(keeper.kept())
        assertFalse(keeper.hasKey())
        assertTrue(keeper.unstash(device))
        assertEquals(key, liveKey())
    }

    @Test
    fun `an unstash cut after the record was written keeps the stash for the next try`() = runTest {
        val key = signedOut()
        val keeper = phone().second
        keeper.stash(device)
        val tag = requireNotNull(keeper.stashed())
        val failed = keys.publicKey()
        assertNotEquals(key, failed, "a failed registration made a key of its own")
        store.writeRecord(RECORD, tag.encode())

        // The parent tries another registration: the stash stays, the failed attempt's key goes.
        keeper.stash(device)
        assertEquals(tag, keeper.stashed())
        assertFalse(keeper.hasKey())
        assertNull(keeper.kept())

        store.writeRecord(RECORD, tag.encode())
        assertTrue(keeper.unstash(device))
        assertEquals(key, liveKey())
        assertEquals(tag, keeper.kept())
    }

    @Test
    fun `an unstash cut after the key moved back leaves the live key and a stray tag that goes`() = runTest {
        val key = signedOut()
        val keeper = phone().second
        keeper.stash(device)
        val tag = requireNotNull(keeper.stashed())
        store.writeRecord(RECORD, tag.encode())
        assertTrue(store.rename(STASH_KEY, KEY))

        assertFalse(keeper.hasStash())
        assertNull(keeper.stashed())
        assertFalse(keeper.unstash(device))
        assertEquals(key, liveKey())
        assertEquals(tag, keeper.kept())
        assertFalse(store.contains(STASH_RECORD))

        store.writeRecord(STASH_RECORD, KeptChatIdentity(null, DeviceId("old")).encode())
        keeper.stash(device)
        assertEquals(tag, keeper.stashed(), "a fresh tag replaces the stray one")
    }

    @Test
    fun `a stashed key without a readable tag is never restored and goes`() = runTest {
        signedOut()
        val keeper = phone().second
        assertTrue(store.rename(KEY, STASH_KEY))

        assertTrue(keeper.hasStash())
        assertNull(keeper.stashed())
        assertFalse(keeper.unstash(device))
        assertFalse(keeper.hasStash())

        // With a live key too: the untagged stash goes and the live key is stashed in its place.
        val live = keys.publicKey()
        assertTrue(store.rename(KEY, STASH_KEY))
        keys.publicKey()
        keeper.stash(device)
        assertEquals(device, keeper.stashed()?.deviceId)
        assertTrue(keeper.unstash(device))
        assertNotEquals(live, liveKey(), "the untagged one was dropped")
    }

    /** The first half of a `BlobStorage.rename`: [from]'s blob copied to [to], [from] not deleted yet. */
    private suspend fun copiedNotDeleted(from: String, to: String) {
        blobs.write(to, requireNotNull(blobs.read(from)))
    }

    @Test
    fun `a stash cut after the key was copied but before the original went converges either way`() = runTest {
        val key = signedOut()
        val keeper = phone().second
        val record = requireNotNull(keeper.kept())
        // {K, SK, ST, R}: the tag is written and the key copied to the stash, the live key not deleted yet.
        store.writeRecord(STASH_RECORD, record.encode())
        copiedNotDeleted(KEY, STASH_KEY)

        // The next registration attempt finishes the stash: complete, with the same key.
        keeper.stash(device)
        assertEquals(record, keeper.stashed())
        assertFalse(keeper.hasKey())
        assertNull(keeper.kept())
        assertTrue(keeper.unstash(device))
        assertEquals(key, liveKey(), "the stash was the live key itself")

        // The same split undone instead: the parent goes back to the same account.
        store.writeRecord(STASH_RECORD, record.encode())
        copiedNotDeleted(KEY, STASH_KEY)
        assertTrue(keeper.unstash(device))
        assertEquals(key, liveKey(), "the live key is the registered one, never deleted")
        assertEquals(record, keeper.kept())
        assertFalse(keeper.hasStash())
        assertFalse(store.contains(STASH_RECORD))
    }

    @Test
    fun `an unstash cut after the record and the key copy were written converges either way`() = runTest {
        val key = signedOut()
        val keeper = phone().second
        keeper.stash(device)
        val tag = requireNotNull(keeper.stashed())
        // {K', SK, ST, R-from-tag}: the record written from the tag and the stash copied back, the stash not deleted.
        store.writeRecord(RECORD, tag.encode())
        copiedNotDeleted(STASH_KEY, KEY)
        assertEquals(key, liveKey(), "the copy already opens under the live name")

        // Running the unstash again finishes it.
        assertTrue(keeper.unstash(device))
        assertEquals(key, liveKey())
        assertEquals(tag, keeper.kept())
        assertFalse(keeper.hasStash())
        assertFalse(store.contains(STASH_RECORD))

        // The same split followed by another registration attempt: a complete stash, the key never lost.
        keeper.stash(device)
        store.writeRecord(RECORD, tag.encode())
        copiedNotDeleted(STASH_KEY, KEY)
        keeper.stash(device)
        assertEquals(tag, keeper.stashed())
        assertFalse(keeper.hasKey())
        assertNull(keeper.kept())
        assertTrue(keeper.unstash(device))
        assertEquals(key, liveKey())
    }

    // ---- deletions that check first (D-REL-35) ----

    /** [BlobStorage] whose first delete of [KEY] waits for [gate] ([entered] completes when it is reached). */
    private class GatedDeletes(private val inner: BlobStorage) : BlobStorage by inner {
        val entered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val calls = mutableListOf<String>()

        override suspend fun read(name: String): ByteArray? = inner.read(name).also { calls += "read:$name" }

        override suspend fun write(name: String, blob: ByteArray) {
            calls += "write:$name"
            inner.write(name, blob)
        }

        override suspend fun delete(name: String) {
            calls += "delete:$name"
            if (name == KEY && !entered.isCompleted) {
                entered.complete(Unit)
                gate.await()
            }
            inner.delete(name)
        }
    }

    @Test
    fun `a stash started while forgetIf is deleting waits for the lock`() = runTest {
        val storage = GatedDeletes(InMemoryBlobStorage())
        val wrapper = InMemoryKeyWrapper()
        // Two stores over the same files: each store's own mutex covers one call only, so nothing but the keeper's
        // lock keeps the stash out of the check-then-delete.
        val checking = WrappedSecretStore(wrapper, storage, SecureRandom(), Dispatchers.Unconfined)
        val registering = WrappedSecretStore(wrapper, storage, SecureRandom(), Dispatchers.Unconfined)
        SecretStoreEncryptionKeyStore(checking, JceX25519KeyAgreement(SecureRandom())).publicKey()
        val checked = KeptChatIdentity(home, device)
        checking.writeRecord(RECORD, checked.encode())
        val database = inMemoryDatabase().also { databases += it }

        val forgetting = launch { assertTrue(ChatIdentityKeeper(database, checking).forgetIf(checked)) }
        runCurrent()
        assertTrue(storage.entered.isCompleted, "forgetIf is inside its delete of the live key")
        val before = storage.calls.size
        val stashing = launch { ChatIdentityKeeper(database, registering).stash(device) }
        runCurrent()

        assertTrue(stashing.isActive, "the stash waits for the lock")
        assertEquals(before, storage.calls.size, "and touches nothing meanwhile")

        storage.gate.complete(Unit)
        forgetting.join()
        stashing.join()
        assertFalse(checking.contains(KEY))
        assertFalse(checking.contains(RECORD))
        assertFalse(checking.contains(STASH_KEY), "nothing was left to stash")
        assertFalse(checking.contains(STASH_RECORD))
    }

    @Test
    fun `forgetIf and dropStashIf delete only what was checked`() = runTest {
        signedOut()
        val keeper = phone().second
        val checked = requireNotNull(keeper.kept())

        assertFalse(keeper.forgetIf(checked.copy(deviceLamport = 99)))
        assertTrue(keeper.hasKey())

        keeper.stash(device)
        assertFalse(keeper.forgetIf(checked), "stashed since: no record to match")
        assertFalse(keeper.dropStashIf(null), "tagged, not untagged")
        assertTrue(keeper.dropStashIf(checked))
        assertFalse(keeper.hasStash())
        assertFalse(store.contains(STASH_RECORD))
    }
}

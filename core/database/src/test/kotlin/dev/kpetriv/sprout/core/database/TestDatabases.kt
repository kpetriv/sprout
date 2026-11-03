package dev.kpetriv.sprout.core.database

import androidx.room.Room
import dev.kpetriv.sprout.core.database.entity.AccountEntity
import dev.kpetriv.sprout.core.domainledger.Account
import dev.kpetriv.sprout.core.domainledger.FakeParentAuthorization
import dev.kpetriv.sprout.core.domainledger.Fixtures
import dev.kpetriv.sprout.core.domainledger.LedgerPolicy
import dev.kpetriv.sprout.core.domainledger.RewardPurchased
import dev.kpetriv.sprout.core.time.Clock
import dev.kpetriv.sprout.core.time.FakeClock
import org.robolectric.RuntimeEnvironment
import java.io.File

internal fun testClock() = FakeClock(Fixtures.start)

/** An unencrypted in-memory database on the platform SQLite (no SQLCipher native library on the JVM). */
internal fun inMemoryDatabase(): SproutDatabase = inMemoryDatabase(RuntimeEnvironment.getApplication())

/** The production-wired data layer of one device ([TestHousehold]) under the trial session, on Robolectric. */
internal fun testHousehold(clock: FakeClock = testClock()): TestHousehold =
    TestHousehold(RuntimeEnvironment.getApplication(), clock)

/** A plain (unencrypted) file database, for tests that close and reopen. */
internal fun fileDatabase(name: String): SproutDatabase =
    Room.databaseBuilder(RuntimeEnvironment.getApplication(), SproutDatabase::class.java, name).build()

internal suspend fun SproutDatabase.register(accounts: List<Account>) {
    accounts.forEach { accountDao().insertIfAbsent(AccountEntity.from(it, createdAtEpochMs = 0)) }
}

/**
 * Accepts every event as local. Only for tests that exercise storage, chaining, validation and conflicts across
 * several author devices (the domain contract and the M3 ingest behaviour); production always binds
 * [DeviceKeyEventCheck], whose rules `LocalEventCheckTest` covers.
 */
internal object AcceptAnyEvent : LocalEventCheck {
    override suspend fun bind(): LocalEventCheck.Bound = LocalEventCheck.Bound { _, _ -> true }
}

/** Accepts every purchase, for tests whose purchases name no catalogue reward (the domain contract). */
internal object AnyPurchase : PurchaseCatalogue {
    override suspend fun matches(event: RewardPurchased): Boolean = true
}

/**
 * A fixed [policy] whose parent signatures are checked with [parentKey] (by default the domain fixtures'
 * [FakeParentAuthorization], which `DeviceWriter(parentKey = ...)` signs with).
 */
internal fun fixedPolicy(
    policy: LedgerPolicy = LedgerPolicy(),
    parentKey: ParentSignatureVerifier? = FakeParentAuthorization().let { key -> ParentSignatureVerifier(key::verify) },
) = LedgerPolicySource { ActivePolicy(policy, parentKey) }

/**
 * A repository that skips the local-origin check ([AcceptAnyEvent]) and the reward catalogue ([AnyPurchase]), under a
 * fixed [policy].
 */
internal fun multiDeviceRepository(
    database: SproutDatabase,
    clock: Clock = testClock(),
    policy: LedgerPolicy = LedgerPolicy(),
) = RoomLedgerRepository(database, clock, AcceptAnyEvent, fixedPolicy(policy), AnyPurchase)

/** A fresh, not yet created directory under `noBackupFilesDir` for a test's photos. */
internal fun newPhotoDirectory(): File = newPhotoDirectory(RuntimeEnvironment.getApplication())

/** An [EncryptedPhotoStore] with in-memory key storage, writing to [directory]. */
internal fun testPhotoStore(
    database: SproutDatabase,
    authors: SessionAuthorProvider,
    clock: Clock,
    directory: File = newPhotoDirectory(),
) = encryptedPhotoStore(database, authors, clock, directory)

/** A ledger in which no chore occurrence is paid, for chore repositories built without one. */
internal val NothingPaid = ChoreRewardLedger { _, _, _ -> emptyList() }

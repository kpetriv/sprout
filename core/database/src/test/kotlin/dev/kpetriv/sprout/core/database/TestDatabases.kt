package dev.kpetriv.sprout.core.database

import androidx.room.Room
import dev.kpetriv.sprout.core.database.entity.AccountEntity
import dev.kpetriv.sprout.core.domainledger.Account
import dev.kpetriv.sprout.core.domainledger.Fixtures
import dev.kpetriv.sprout.core.domainledger.LedgerPolicy
import dev.kpetriv.sprout.core.time.Clock
import dev.kpetriv.sprout.core.time.FakeClock
import org.robolectric.RuntimeEnvironment

internal fun testClock() = FakeClock(Fixtures.start)

/** An unencrypted in-memory database on the platform SQLite (no SQLCipher native library on the JVM). */
internal fun inMemoryDatabase(): SproutDatabase =
    Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), SproutDatabase::class.java).build()

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

/** A repository that skips the local-origin check; see [AcceptAnyEvent]. */
internal fun multiDeviceRepository(database: SproutDatabase, clock: Clock = testClock()) =
    RoomLedgerRepository(database, clock, AcceptAnyEvent, LedgerPolicy())

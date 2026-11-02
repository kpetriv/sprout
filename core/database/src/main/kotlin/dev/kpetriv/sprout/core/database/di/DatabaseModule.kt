package dev.kpetriv.sprout.core.database.di

import android.content.Context
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.kpetriv.sprout.core.crypto.SecretStore
import dev.kpetriv.sprout.core.database.AccountRepository
import dev.kpetriv.sprout.core.database.DatabaseFactory
import dev.kpetriv.sprout.core.database.FiatCurrencyProvider
import dev.kpetriv.sprout.core.database.KidRepository
import dev.kpetriv.sprout.core.database.LocalOnlyAuthorProvider
import dev.kpetriv.sprout.core.database.LocaleFiatCurrencyProvider
import dev.kpetriv.sprout.core.database.RoomAccountRepository
import dev.kpetriv.sprout.core.database.RoomKidRepository
import dev.kpetriv.sprout.core.database.RoomLedgerRepository
import dev.kpetriv.sprout.core.database.RoomLedgerWriter
import dev.kpetriv.sprout.core.database.SessionAuthorProvider
import dev.kpetriv.sprout.core.database.SproutDatabase
import dev.kpetriv.sprout.core.database.dao.AccountDao
import dev.kpetriv.sprout.core.database.dao.AnomalyDao
import dev.kpetriv.sprout.core.database.dao.AuditLogDao
import dev.kpetriv.sprout.core.database.dao.BalanceDao
import dev.kpetriv.sprout.core.database.dao.DeviceStateDao
import dev.kpetriv.sprout.core.database.dao.KidDao
import dev.kpetriv.sprout.core.database.dao.LedgerEventDao
import dev.kpetriv.sprout.core.database.dao.OutboxDao
import dev.kpetriv.sprout.core.domainledger.LedgerRepository
import dev.kpetriv.sprout.core.domainledger.LedgerWriter
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
internal abstract class DatabaseModule {
    @Binds
    abstract fun ledgerRepository(impl: RoomLedgerRepository): LedgerRepository

    @Binds
    abstract fun ledgerWriter(impl: RoomLedgerWriter): LedgerWriter

    @Binds
    abstract fun kidRepository(impl: RoomKidRepository): KidRepository

    @Binds
    abstract fun accountRepository(impl: RoomAccountRepository): AccountRepository

    /** TEMPORARY until M3 auth: a single local parent. */
    @Binds
    abstract fun sessionAuthorProvider(impl: LocalOnlyAuthorProvider): SessionAuthorProvider

    companion object {
        @Provides
        @Singleton
        fun database(@ApplicationContext context: Context, secretStore: SecretStore): SproutDatabase =
            DatabaseFactory.encrypted(context, secretStore)

        @Provides
        fun fiatCurrencyProvider(): FiatCurrencyProvider = LocaleFiatCurrencyProvider()

        @Provides
        fun ledgerEventDao(database: SproutDatabase): LedgerEventDao = database.ledgerEventDao()

        @Provides
        fun accountDao(database: SproutDatabase): AccountDao = database.accountDao()

        @Provides
        fun kidDao(database: SproutDatabase): KidDao = database.kidDao()

        @Provides
        fun balanceDao(database: SproutDatabase): BalanceDao = database.balanceDao()

        @Provides
        fun anomalyDao(database: SproutDatabase): AnomalyDao = database.anomalyDao()

        @Provides
        fun outboxDao(database: SproutDatabase): OutboxDao = database.outboxDao()

        @Provides
        fun auditLogDao(database: SproutDatabase): AuditLogDao = database.auditLogDao()

        @Provides
        fun deviceStateDao(database: SproutDatabase): DeviceStateDao = database.deviceStateDao()
    }
}

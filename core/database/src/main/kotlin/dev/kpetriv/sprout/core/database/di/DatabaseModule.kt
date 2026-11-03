package dev.kpetriv.sprout.core.database.di

import android.content.Context
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.kpetriv.sprout.core.common.Dispatcher
import dev.kpetriv.sprout.core.common.SproutDispatchers
import dev.kpetriv.sprout.core.crypto.Aead
import dev.kpetriv.sprout.core.crypto.SecretStore
import dev.kpetriv.sprout.core.database.AccountRepository
import dev.kpetriv.sprout.core.database.AllowanceRepository
import dev.kpetriv.sprout.core.database.ChoreRepository
import dev.kpetriv.sprout.core.database.DatabaseFactory
import dev.kpetriv.sprout.core.database.EncryptedPhotoStore
import dev.kpetriv.sprout.core.database.EnglishPendingActionDescriber
import dev.kpetriv.sprout.core.database.FiatCurrencyProvider
import dev.kpetriv.sprout.core.database.HouseholdPolicyRepository
import dev.kpetriv.sprout.core.database.InterestPolicyRepository
import dev.kpetriv.sprout.core.database.KidRepository
import dev.kpetriv.sprout.core.database.LocalOnlyAuthorProvider
import dev.kpetriv.sprout.core.database.LocaleFiatCurrencyProvider
import dev.kpetriv.sprout.core.database.PendingActionDescriber
import dev.kpetriv.sprout.core.database.PendingParentActionRepository
import dev.kpetriv.sprout.core.database.PhotoStore
import dev.kpetriv.sprout.core.database.RewardRepository
import dev.kpetriv.sprout.core.database.RoomAccountRepository
import dev.kpetriv.sprout.core.database.RoomAllowanceRepository
import dev.kpetriv.sprout.core.database.RoomChoreRepository
import dev.kpetriv.sprout.core.database.RoomHouseholdPolicyRepository
import dev.kpetriv.sprout.core.database.RoomInterestPolicyRepository
import dev.kpetriv.sprout.core.database.RoomKidRepository
import dev.kpetriv.sprout.core.database.RoomLedgerRepository
import dev.kpetriv.sprout.core.database.RoomLedgerWriter
import dev.kpetriv.sprout.core.database.RoomPendingParentActionRepository
import dev.kpetriv.sprout.core.database.RoomRewardRepository
import dev.kpetriv.sprout.core.database.RoomSavingsGoalRepository
import dev.kpetriv.sprout.core.database.SavingsGoalRepository
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
import dev.kpetriv.sprout.core.time.Clock
import kotlinx.coroutines.CoroutineDispatcher
import java.io.File
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
@Suppress("TooManyFunctions") // One binding per repository and one provider per DAO.
internal abstract class DatabaseModule {
    @Binds
    abstract fun ledgerRepository(impl: RoomLedgerRepository): LedgerRepository

    @Binds
    abstract fun ledgerWriter(impl: RoomLedgerWriter): LedgerWriter

    @Binds
    abstract fun kidRepository(impl: RoomKidRepository): KidRepository

    @Binds
    abstract fun accountRepository(impl: RoomAccountRepository): AccountRepository

    @Binds
    abstract fun choreRepository(impl: RoomChoreRepository): ChoreRepository

    @Binds
    abstract fun rewardRepository(impl: RoomRewardRepository): RewardRepository

    @Binds
    abstract fun allowanceRepository(impl: RoomAllowanceRepository): AllowanceRepository

    @Binds
    abstract fun savingsGoalRepository(impl: RoomSavingsGoalRepository): SavingsGoalRepository

    @Binds
    abstract fun interestPolicyRepository(impl: RoomInterestPolicyRepository): InterestPolicyRepository

    @Binds
    abstract fun householdPolicyRepository(impl: RoomHouseholdPolicyRepository): HouseholdPolicyRepository

    @Binds
    abstract fun pendingParentActionRepository(impl: RoomPendingParentActionRepository): PendingParentActionRepository

    /** English prompt reasons for queued actions; the app may replace it with a localised describer. */
    @Binds
    abstract fun pendingActionDescriber(impl: EnglishPendingActionDescriber): PendingActionDescriber

    /** TEMPORARY until M3 auth: a single local parent. */
    @Binds
    abstract fun sessionAuthorProvider(impl: LocalOnlyAuthorProvider): SessionAuthorProvider

    @Suppress("TooManyFunctions") // One provider per DAO.
    companion object {
        @Provides
        @Singleton
        fun database(@ApplicationContext context: Context, secretStore: SecretStore): SproutDatabase =
            DatabaseFactory.encrypted(context, secretStore)

        @Provides
        fun fiatCurrencyProvider(): FiatCurrencyProvider = LocaleFiatCurrencyProvider()

        /** Photos live encrypted in `noBackupFilesDir/photos`, outside cloud backups (D-M2-3). */
        @Provides
        @Singleton
        @Suppress("LongParameterList") // Every collaborator of the photo store comes from the graph.
        fun photoStore(
            @ApplicationContext context: Context,
            database: SproutDatabase,
            aead: Aead,
            secretStore: SecretStore,
            authors: SessionAuthorProvider,
            clock: Clock,
            @Dispatcher(SproutDispatchers.IO) dispatcher: CoroutineDispatcher,
        ): PhotoStore = EncryptedPhotoStore(
            database = database,
            directory = File(context.noBackupFilesDir, EncryptedPhotoStore.DIRECTORY_NAME),
            aead = aead,
            secretStore = secretStore,
            authors = authors,
            clock = clock,
            dispatcher = dispatcher,
        )

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

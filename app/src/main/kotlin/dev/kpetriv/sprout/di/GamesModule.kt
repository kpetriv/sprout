package dev.kpetriv.sprout.di

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import dev.kpetriv.sprout.core.database.FiatCurrencyProvider
import dev.kpetriv.sprout.core.database.LocalDataFiles
import dev.kpetriv.sprout.core.database.LocalPreferenceStore
import dev.kpetriv.sprout.core.database.usecase.GameCapReader
import dev.kpetriv.sprout.core.database.usecase.GameHistoryReader
import dev.kpetriv.sprout.core.domainledger.Currency
import dev.kpetriv.sprout.core.games.GameKind
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.feature.games.GameCapSource
import dev.kpetriv.sprout.feature.games.GameCreditScope
import dev.kpetriv.sprout.feature.games.GameCreditSubmitter
import dev.kpetriv.sprout.feature.games.GameCurrencySource
import dev.kpetriv.sprout.feature.games.GameHistorySource
import dev.kpetriv.sprout.feature.games.GamePlaySummary
import dev.kpetriv.sprout.feature.games.TablesSetupStore
import dev.kpetriv.sprout.games.DataStoreTablesSetupStore
import dev.kpetriv.sprout.games.UseCaseGameCreditSubmitter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Singleton

/**
 * The brain games ports (`feature/games`, M5 §5): credits through `PlayGameUseCase` on the [ApplicationScope] (so
 * leaving the result screen never cancels one, M5 review M1), today's remaining cap from [GameCapReader], past plays
 * from [GameHistoryReader] (M9-games §6.2), amounts shown in the household policy's fiat currency
 * ([FiatCurrencyProvider], followed as it changes), and each kid's Times tables setup in a local DataStore at
 * [LocalDataFiles.tablesSetupFile] (D-M9-G7; the wipe deletes it). `feature/games`' own module binds the seed source
 * and the monotonic round timer.
 */
@Module
@InstallIn(SingletonComponent::class)
internal object GamesModule {
    @Provides
    fun creditSubmitter(impl: UseCaseGameCreditSubmitter): GameCreditSubmitter = impl

    @Provides
    fun creditScope(@ApplicationScope scope: CoroutineScope): GameCreditScope = GameCreditScope(scope)

    @Provides
    fun capSource(reader: GameCapReader): GameCapSource = GameCapSource(reader::observeRemaining)

    @Provides
    fun historySource(reader: GameHistoryReader): GameHistorySource = object : GameHistorySource {
        override fun observeRecent(kidId: KidId, kind: GameKind, limit: Int): Flow<List<GamePlaySummary>> =
            reader.observeRecent(kidId, kind, limit).map { plays ->
                plays.map { GamePlaySummary(it.playedOn, it.gameId, it.score, it.amount) }
            }

        override fun observeBestScoreThisWeek(kidId: KidId, kind: GameKind): Flow<Int?> =
            reader.observeBestScoreThisWeek(kidId, kind)
    }

    /** One DataStore per file and process, so a singleton. */
    @Provides
    @Singleton
    fun dataStoreTablesSetupStore(files: LocalDataFiles): DataStoreTablesSetupStore =
        DataStoreTablesSetupStore(PreferenceDataStoreFactory.create { files.tablesSetupFile })

    @Provides
    fun tablesSetupStore(store: DataStoreTablesSetupStore): TablesSetupStore = store

    /** The wipe, the trial purge and an account switch clear it before its file is deleted. */
    @Provides
    @IntoSet
    fun tablesSetupPreferences(store: DataStoreTablesSetupStore): LocalPreferenceStore = store

    @Provides
    fun currencySource(provider: FiatCurrencyProvider): GameCurrencySource = object : GameCurrencySource {
        override fun fiatCurrency(): Currency.Fiat = provider.fiatCurrency()

        override fun observeFiatCurrency(): Flow<Currency.Fiat> = provider.observeFiatCurrency()
    }
}

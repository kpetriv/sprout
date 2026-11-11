package dev.kpetriv.sprout.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.kpetriv.sprout.core.database.FiatCurrencyProvider
import dev.kpetriv.sprout.core.database.usecase.GameCapReader
import dev.kpetriv.sprout.core.domainledger.Currency
import dev.kpetriv.sprout.feature.games.GameCapSource
import dev.kpetriv.sprout.feature.games.GameCreditScope
import dev.kpetriv.sprout.feature.games.GameCreditSubmitter
import dev.kpetriv.sprout.feature.games.GameCurrencySource
import dev.kpetriv.sprout.games.UseCaseGameCreditSubmitter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow

/**
 * The brain games ports (`feature/games`, M5 §5): credits through `PlayGameUseCase` on the [ApplicationScope] (so
 * leaving the result screen never cancels one, M5 review M1), today's remaining cap from [GameCapReader], amounts
 * shown in the household policy's fiat currency ([FiatCurrencyProvider], followed as it changes). `feature/games`' own
 * module binds the seed source and the monotonic round timer.
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
    fun currencySource(provider: FiatCurrencyProvider): GameCurrencySource = object : GameCurrencySource {
        override fun fiatCurrency(): Currency.Fiat = provider.fiatCurrency()

        override fun observeFiatCurrency(): Flow<Currency.Fiat> = provider.observeFiatCurrency()
    }
}

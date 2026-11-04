package dev.kpetriv.sprout.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.kpetriv.sprout.core.database.FiatCurrencyProvider
import dev.kpetriv.sprout.core.database.usecase.GameCapReader
import dev.kpetriv.sprout.feature.games.GameCapSource
import dev.kpetriv.sprout.feature.games.GameCreditSubmitter
import dev.kpetriv.sprout.feature.games.GameCurrencySource
import dev.kpetriv.sprout.games.UseCaseGameCreditSubmitter

/**
 * The brain games ports (`feature/games`, M5 §5): credits through `PlayGameUseCase`, today's remaining cap from
 * [GameCapReader], amounts shown in the household's fiat currency ([FiatCurrencyProvider]). `feature/games`' own
 * module binds the seed source.
 */
@Module
@InstallIn(SingletonComponent::class)
internal object GamesModule {
    @Provides
    fun creditSubmitter(impl: UseCaseGameCreditSubmitter): GameCreditSubmitter = impl

    @Provides
    fun capSource(reader: GameCapReader): GameCapSource = GameCapSource(reader::observeRemaining)

    @Provides
    fun currencySource(provider: FiatCurrencyProvider): GameCurrencySource = GameCurrencySource(provider::fiatCurrency)
}

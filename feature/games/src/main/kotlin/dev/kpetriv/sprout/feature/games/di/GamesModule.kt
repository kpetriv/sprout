package dev.kpetriv.sprout.feature.games.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ViewModelComponent
import dev.kpetriv.sprout.feature.games.GameSeedSource

/**
 * Games bindings that need nothing from the app: a [GameSeedSource] over `Random.Default`. The app binds
 * [dev.kpetriv.sprout.feature.games.GameCreditSubmitter] (`PlayGameUseCase`),
 * [dev.kpetriv.sprout.feature.games.GameCapSource] (`GameCapReader`) and
 * [dev.kpetriv.sprout.feature.games.GameCurrencySource] (the household's fiat currency); `Clock` comes from
 * `core/common`.
 */
@Module
@InstallIn(ViewModelComponent::class)
object GamesModule {
    @Provides
    fun seedSource(): GameSeedSource = GameSeedSource.Random
}

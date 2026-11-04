package dev.kpetriv.sprout.feature.games.di

import android.os.SystemClock
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ViewModelComponent
import dev.kpetriv.sprout.feature.games.GameSeedSource
import dev.kpetriv.sprout.feature.games.GameTimer

/**
 * Games bindings that need nothing from the app: a [GameSeedSource] over `Random.Default` and a [GameTimer] over
 * [SystemClock.elapsedRealtime] (monotonic, M5 audit I2). The app binds
 * [dev.kpetriv.sprout.feature.games.GameCreditSubmitter] (`PlayGameUseCase`),
 * [dev.kpetriv.sprout.feature.games.GameCreditScope] (its application scope),
 * [dev.kpetriv.sprout.feature.games.GameCapSource] (`GameCapReader`) and
 * [dev.kpetriv.sprout.feature.games.GameCurrencySource] (the household's fiat currency).
 */
@Module
@InstallIn(ViewModelComponent::class)
object GamesModule {
    @Provides
    fun seedSource(): GameSeedSource = GameSeedSource.Random

    @Provides
    fun timer(): GameTimer = GameTimer(SystemClock::elapsedRealtime)
}

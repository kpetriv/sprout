package dev.kpetriv.sprout.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.kpetriv.sprout.startup.AppInitializer
import dev.kpetriv.sprout.startup.StartupStatus

/** Exposes [AppInitializer]'s progress to screens through [StartupStatus]. */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class StartupModule {
    @Binds
    abstract fun startupStatus(impl: AppInitializer): StartupStatus
}

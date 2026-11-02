package dev.kpetriv.sprout.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.kpetriv.sprout.core.common.Dispatcher
import dev.kpetriv.sprout.core.common.SproutDispatchers
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import javax.inject.Singleton

/** App-shell bindings. */
@Module
@InstallIn(SingletonComponent::class)
internal object AppModule {
    /** Lives as long as the process; a failing child never cancels its siblings. Runs on IO. */
    @Provides
    @Singleton
    @ApplicationScope
    fun applicationScope(@Dispatcher(SproutDispatchers.IO) io: CoroutineDispatcher): CoroutineScope =
        CoroutineScope(SupervisorJob() + io)
}

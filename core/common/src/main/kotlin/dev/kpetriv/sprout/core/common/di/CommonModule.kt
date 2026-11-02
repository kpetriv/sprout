package dev.kpetriv.sprout.core.common.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.kpetriv.sprout.core.common.Dispatcher
import dev.kpetriv.sprout.core.common.SproutDispatchers
import dev.kpetriv.sprout.core.time.Clock
import dev.kpetriv.sprout.core.time.SystemClock
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

@Module
@InstallIn(SingletonComponent::class)
internal object CommonModule {
    @Provides
    @Dispatcher(SproutDispatchers.IO)
    fun ioDispatcher(): CoroutineDispatcher = Dispatchers.IO

    @Provides
    @Dispatcher(SproutDispatchers.Default)
    fun defaultDispatcher(): CoroutineDispatcher = Dispatchers.Default

    /** The wall clock; tests replace it with FakeClock from :core:time's test fixtures. */
    @Provides
    fun clock(): Clock = SystemClock
}

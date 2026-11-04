package dev.kpetriv.sprout.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.kpetriv.sprout.widget.GlanceWidgetRefresher
import dev.kpetriv.sprout.widget.RemoteViewsWidgetBlanker
import dev.kpetriv.sprout.widget.SessionWidgetStateSource
import dev.kpetriv.sprout.widget.WidgetBlanker
import dev.kpetriv.sprout.widget.WidgetRefreshScheduler
import dev.kpetriv.sprout.widget.WidgetRefresher
import dev.kpetriv.sprout.widget.WidgetStateSource
import dev.kpetriv.sprout.widget.WorkManagerWidgetRefreshScheduler

/** The home screen widget (M7 §4): its state, refresh requests, the removal's blanking and the periodic fallback. */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class WidgetModule {
    @Binds
    abstract fun widgetStateSource(impl: SessionWidgetStateSource): WidgetStateSource

    @Binds
    abstract fun widgetRefresher(impl: GlanceWidgetRefresher): WidgetRefresher

    @Binds
    abstract fun widgetBlanker(impl: RemoteViewsWidgetBlanker): WidgetBlanker

    @Binds
    abstract fun widgetRefreshScheduler(impl: WorkManagerWidgetRefreshScheduler): WidgetRefreshScheduler
}

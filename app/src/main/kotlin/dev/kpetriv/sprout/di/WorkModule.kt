package dev.kpetriv.sprout.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.kpetriv.sprout.work.PlanScheduler
import dev.kpetriv.sprout.work.ScheduledActionsPlanner
import dev.kpetriv.sprout.work.UseCaseScheduledActionsPlanner
import dev.kpetriv.sprout.work.WorkManagerPlanScheduler

/** The daily allowance and interest planner. */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class WorkModule {
    @Binds
    abstract fun scheduledActionsPlanner(impl: UseCaseScheduledActionsPlanner): ScheduledActionsPlanner

    @Binds
    abstract fun planScheduler(impl: WorkManagerPlanScheduler): PlanScheduler
}

package dev.kpetriv.sprout.feature.timebank.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ViewModelComponent
import dev.kpetriv.sprout.feature.timebank.HouseholdTimeBankClock
import dev.kpetriv.sprout.feature.timebank.TimeBankClock

/**
 * Time bank bindings that need nothing from the app: the [TimeBankClock] over `core/time`. The app binds
 * [dev.kpetriv.sprout.feature.timebank.TimeBankStateSource] (the ledger projection) and
 * [dev.kpetriv.sprout.feature.timebank.TimeBankControl] (the start and stop use cases).
 */
@Module
@InstallIn(ViewModelComponent::class)
abstract class TimeBankModule {
    @Binds
    abstract fun timeBankClock(impl: HouseholdTimeBankClock): TimeBankClock
}

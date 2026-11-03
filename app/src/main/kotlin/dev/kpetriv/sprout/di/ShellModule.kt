package dev.kpetriv.sprout.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.kpetriv.sprout.feature.lock.ParentKeyRegistry
import dev.kpetriv.sprout.feature.lock.ParentSession
import dev.kpetriv.sprout.feature.settings.LockPreferences
import dev.kpetriv.sprout.feature.settings.ParentKeyRegistrar
import dev.kpetriv.sprout.feature.settings.PinChanger
import dev.kpetriv.sprout.navigation.ParentLock
import dev.kpetriv.sprout.settings.HouseholdParentKeyRegistry
import dev.kpetriv.sprout.settings.LockParentKeyRegistrar
import dev.kpetriv.sprout.settings.LockSettingsPreferences
import dev.kpetriv.sprout.settings.ParentSessionPinChanger

/**
 * What features need from each other, adapted in the app (features never depend on each other): settings' ports over
 * `feature/lock`, `feature/lock`'s parent-key registry over `core/database`, and the shell's [ParentLock] over the
 * parent session.
 */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class ShellModule {
    @Binds
    abstract fun pinChanger(impl: ParentSessionPinChanger): PinChanger

    @Binds
    abstract fun lockPreferences(impl: LockSettingsPreferences): LockPreferences

    @Binds
    abstract fun parentKeyRegistrar(impl: LockParentKeyRegistrar): ParentKeyRegistrar

    /** `feature/lock` reads and (only through its PIN-gated enrolment and renewal) writes the registered key here. */
    @Binds
    abstract fun parentKeyRegistry(impl: HouseholdParentKeyRegistry): ParentKeyRegistry

    companion object {
        @Provides
        fun parentLock(session: ParentSession): ParentLock = ParentLock(session::lock)
    }
}

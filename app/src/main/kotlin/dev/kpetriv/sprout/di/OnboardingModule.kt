package dev.kpetriv.sprout.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.kpetriv.sprout.feature.onboarding.OnboardingAuth
import dev.kpetriv.sprout.feature.onboarding.OnboardingHousehold
import dev.kpetriv.sprout.feature.onboarding.ParentKeyRegistrar
import dev.kpetriv.sprout.feature.onboarding.ParentSigningSetup
import dev.kpetriv.sprout.feature.onboarding.RegistrationBackend
import dev.kpetriv.sprout.navigation.RemovalSignal
import dev.kpetriv.sprout.onboarding.FirebaseOnboardingAuth
import dev.kpetriv.sprout.onboarding.FunctionsParentKeyRegistrar
import dev.kpetriv.sprout.onboarding.FunctionsRegistrationBackend
import dev.kpetriv.sprout.onboarding.LockParentSigningSetup
import dev.kpetriv.sprout.onboarding.RoomOnboardingHousehold
import dev.kpetriv.sprout.sync.SyncController

/** `feature/onboarding`'s ports over core/sync-firebase, core/database and `feature/lock`, and the shell's removal. */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class OnboardingModule {
    @Binds
    abstract fun onboardingAuth(impl: FirebaseOnboardingAuth): OnboardingAuth

    @Binds
    abstract fun registrationBackend(impl: FunctionsRegistrationBackend): RegistrationBackend

    @Binds
    abstract fun onboardingHousehold(impl: RoomOnboardingHousehold): OnboardingHousehold

    @Binds
    abstract fun parentSigningSetup(impl: LockParentSigningSetup): ParentSigningSetup

    @Binds
    abstract fun parentKeyRegistrar(impl: FunctionsParentKeyRegistrar): ParentKeyRegistrar

    companion object {
        @Provides
        fun removalSignal(sync: SyncController): RemovalSignal = RemovalSignal { sync.removal }
    }
}

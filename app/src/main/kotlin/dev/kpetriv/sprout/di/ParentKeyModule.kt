package dev.kpetriv.sprout.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.kpetriv.sprout.feature.lock.ParentKeyChallengeSource
import dev.kpetriv.sprout.household.ServerParentKeyChallengeSource

/** `feature/lock`'s parent-key ports that the server answers. */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class ParentKeyModule {
    /** The parent key's attestation challenge comes from the server's `beginDeviceRegistration` (D-M3-34). */
    @Binds
    abstract fun parentKeyChallengeSource(impl: ServerParentKeyChallengeSource): ParentKeyChallengeSource
}

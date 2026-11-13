package dev.kpetriv.sprout.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import dev.kpetriv.sprout.core.database.LocalPreferenceStore
import dev.kpetriv.sprout.core.sync.RecoveryCallables
import dev.kpetriv.sprout.feature.lock.RecoveryCheckUploader
import dev.kpetriv.sprout.feature.onboarding.AccountRecovery
import dev.kpetriv.sprout.feature.onboarding.RecoveredPinKeeper
import dev.kpetriv.sprout.feature.settings.AccountRecoverySetup
import dev.kpetriv.sprout.recovery.CredentialGateSignIn
import dev.kpetriv.sprout.recovery.FunctionsRecoveryCallables
import dev.kpetriv.sprout.recovery.LockAccountRecoverySetup
import dev.kpetriv.sprout.recovery.LockRecoveredPinKeeper
import dev.kpetriv.sprout.recovery.RecoveryPinFlagStore
import dev.kpetriv.sprout.recovery.RecoverySignIn
import dev.kpetriv.sprout.recovery.ServerRecoveryCheckUploader
import dev.kpetriv.sprout.recovery.SyncAccountRecovery

/** Account recovery (ADR-0013): the callables and the features' recovery ports. */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class RecoveryModule {
    @Binds
    abstract fun recoveryCallables(impl: FunctionsRecoveryCallables): RecoveryCallables

    @Binds
    abstract fun accountRecovery(impl: SyncAccountRecovery): AccountRecovery

    @Binds
    abstract fun recoverySignIn(impl: CredentialGateSignIn): RecoverySignIn

    @Binds
    abstract fun recoveredPinKeeper(impl: LockRecoveredPinKeeper): RecoveredPinKeeper

    @Binds
    abstract fun recoveryCheckUploader(impl: ServerRecoveryCheckUploader): RecoveryCheckUploader

    @Binds
    abstract fun accountRecoverySetup(impl: LockAccountRecoverySetup): AccountRecoverySetup

    /** The registration mark goes with the household's local data. */
    @Binds
    @IntoSet
    abstract fun recoveryPinFlagStore(impl: RecoveryPinFlagStore): LocalPreferenceStore
}

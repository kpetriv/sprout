package dev.kpetriv.sprout.feature.lock.di

import android.content.Context
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.kpetriv.sprout.core.common.Dispatcher
import dev.kpetriv.sprout.core.common.SproutDispatchers
import dev.kpetriv.sprout.core.crypto.Aead
import dev.kpetriv.sprout.core.crypto.FilesDirBlobStorage
import dev.kpetriv.sprout.core.crypto.ParentKeyHealth
import dev.kpetriv.sprout.core.crypto.SecretStore
import dev.kpetriv.sprout.core.domainledger.ParentAuthorizationRequester
import dev.kpetriv.sprout.feature.lock.AppForegroundSource
import dev.kpetriv.sprout.feature.lock.EncryptedParentPinStore
import dev.kpetriv.sprout.feature.lock.HostedParentAuthorizationRequester
import dev.kpetriv.sprout.feature.lock.HostedParentKeyProofPrompter
import dev.kpetriv.sprout.feature.lock.LockScope
import dev.kpetriv.sprout.feature.lock.LockSettings
import dev.kpetriv.sprout.feature.lock.ParentKeyProofPrompter
import dev.kpetriv.sprout.feature.lock.ParentKeyStatusCheck
import dev.kpetriv.sprout.feature.lock.ParentPinStore
import dev.kpetriv.sprout.feature.lock.ProcessLifecycleForegroundSource
import dev.kpetriv.sprout.feature.lock.SharedPreferencesLockSettings
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Singleton

/**
 * Parent-lock bindings. Everything else ([dev.kpetriv.sprout.feature.lock.ParentSession], the prompt, the
 * ViewModels) is `@Inject`; the crypto pieces come from `core/crypto`. The app binds
 * [dev.kpetriv.sprout.feature.lock.ParentKeyRegistry] (the household's registered parent key lives in
 * `core/database`).
 */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class LockModule {
    /** The ledger's per-event requester: prompts over the activity hosting `ParentAuthorizationHost()`. */
    @Binds
    abstract fun parentAuthorizationRequester(impl: HostedParentAuthorizationRequester): ParentAuthorizationRequester

    @Binds
    abstract fun appForegroundSource(impl: ProcessLifecycleForegroundSource): AppForegroundSource

    /** `PinLockoutPolicy` closes the PIN path at the cap only while a biometric unlock can really succeed. */
    @Binds
    abstract fun parentKeyHealth(impl: ParentKeyStatusCheck): ParentKeyHealth

    /** The enrolment and renewal proof prompt, over the activity hosting `ParentAuthorizationHost()`. */
    @Binds
    abstract fun parentKeyProofPrompter(impl: HostedParentKeyProofPrompter): ParentKeyProofPrompter

    companion object {
        @Provides
        @Singleton
        fun parentPinStore(
            @ApplicationContext context: Context,
            secretStore: SecretStore,
            aead: Aead,
            @Dispatcher(SproutDispatchers.IO) dispatcher: CoroutineDispatcher,
        ): ParentPinStore = EncryptedParentPinStore(
            secretStore = secretStore,
            storage = FilesDirBlobStorage(context, dispatcher),
            aead = aead,
        )

        @Provides
        @Singleton
        fun lockSettings(@ApplicationContext context: Context): LockSettings = SharedPreferencesLockSettings(context)

        /** Lives as long as the process, like the singleton session that uses it. */
        @Provides
        @Singleton
        @LockScope
        fun lockScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}

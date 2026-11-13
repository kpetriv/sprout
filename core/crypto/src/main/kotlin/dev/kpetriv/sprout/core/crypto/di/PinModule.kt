package dev.kpetriv.sprout.core.crypto.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.kpetriv.sprout.core.common.Dispatcher
import dev.kpetriv.sprout.core.common.SproutDispatchers
import dev.kpetriv.sprout.core.crypto.Aead
import dev.kpetriv.sprout.core.crypto.AndroidBootClock
import dev.kpetriv.sprout.core.crypto.Argon2KtFunction
import dev.kpetriv.sprout.core.crypto.Argon2idPinHasher
import dev.kpetriv.sprout.core.crypto.BootClock
import dev.kpetriv.sprout.core.crypto.EncryptedLockoutStateStore
import dev.kpetriv.sprout.core.crypto.FilesDirBlobStorage
import dev.kpetriv.sprout.core.crypto.KeystoreHmacPepper
import dev.kpetriv.sprout.core.crypto.LockoutStateStore
import dev.kpetriv.sprout.core.crypto.PinHasher
import dev.kpetriv.sprout.core.crypto.PinPepper
import dev.kpetriv.sprout.core.crypto.RecoveryPinVerifier
import dev.kpetriv.sprout.core.crypto.SecretStore
import kotlinx.coroutines.CoroutineDispatcher
import java.security.SecureRandom
import javax.inject.Singleton

/**
 * Parent PIN bindings (ADR-0004 section 3): the Keystore HMAC pepper, the peppered Argon2id hasher, and the
 * persisted lockout (`PinLockoutPolicy` itself is `@Inject`). Kept apart from [CryptoModule] to keep both small.
 */
@Module
@InstallIn(SingletonComponent::class)
internal object PinModule {
    @Provides
    @Singleton
    fun pinPepper(@ApplicationContext context: Context): PinPepper = KeystoreHmacPepper(context)

    @Provides
    @Singleton
    fun pinHasher(
        random: SecureRandom,
        @Dispatcher(SproutDispatchers.Default) dispatcher: CoroutineDispatcher,
        pepper: PinPepper,
    ): PinHasher = Argon2idPinHasher(random, dispatcher, pepper)

    /** The recovery PIN check value (ADR-0013 §1): Argon2id through argon2kt, no pepper. */
    @Provides
    @Singleton
    fun recoveryPinVerifier(
        random: SecureRandom,
        @Dispatcher(SproutDispatchers.Default) dispatcher: CoroutineDispatcher,
    ): RecoveryPinVerifier = RecoveryPinVerifier(random, dispatcher, Argon2KtFunction())

    @Provides
    @Singleton
    fun bootClock(): BootClock = AndroidBootClock()

    @Provides
    @Singleton
    fun lockoutStateStore(
        @ApplicationContext context: Context,
        secretStore: SecretStore,
        aead: Aead,
        @Dispatcher(SproutDispatchers.IO) dispatcher: CoroutineDispatcher,
    ): LockoutStateStore = EncryptedLockoutStateStore(
        secretStore = secretStore,
        storage = FilesDirBlobStorage(context, dispatcher),
        aead = aead,
    )
}

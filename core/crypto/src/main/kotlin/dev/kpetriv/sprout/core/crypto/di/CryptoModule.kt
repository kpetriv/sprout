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
import dev.kpetriv.sprout.core.crypto.AesGcmAead
import dev.kpetriv.sprout.core.crypto.AndroidKeystoreDeviceKeyStore
import dev.kpetriv.sprout.core.crypto.AndroidKeystoreKeyWrapper
import dev.kpetriv.sprout.core.crypto.AndroidKeystoreParentAuthKeyStore
import dev.kpetriv.sprout.core.crypto.BiometricParentAuthAvailabilityChecker
import dev.kpetriv.sprout.core.crypto.DeviceKeyStore
import dev.kpetriv.sprout.core.crypto.FilesDirBlobStorage
import dev.kpetriv.sprout.core.crypto.JceEcdsaVerifier
import dev.kpetriv.sprout.core.crypto.JceX25519KeyAgreement
import dev.kpetriv.sprout.core.crypto.KeystoreParentAuthorizer
import dev.kpetriv.sprout.core.crypto.ParentAuthAvailabilityChecker
import dev.kpetriv.sprout.core.crypto.ParentAuthKeyStore
import dev.kpetriv.sprout.core.crypto.ParentAuthorizer
import dev.kpetriv.sprout.core.crypto.SecretStore
import dev.kpetriv.sprout.core.crypto.Verifier
import dev.kpetriv.sprout.core.crypto.WrappedSecretStore
import dev.kpetriv.sprout.core.crypto.X25519KeyAgreement
import kotlinx.coroutines.CoroutineDispatcher
import java.security.SecureRandom
import javax.inject.Singleton

/** Production crypto bindings: Android Keystore for keys, JCE for everything that needs no private key. */
@Module
@InstallIn(SingletonComponent::class)
internal object CryptoModule {
    @Provides
    @Singleton
    fun secureRandom(): SecureRandom = SecureRandom()

    @Provides
    @Singleton
    fun aead(random: SecureRandom): Aead = AesGcmAead(random)

    @Provides
    @Singleton
    fun verifier(): Verifier = JceEcdsaVerifier()

    @Provides
    @Singleton
    fun keyAgreement(random: SecureRandom): X25519KeyAgreement = JceX25519KeyAgreement(random)

    @Provides
    @Singleton
    fun deviceKeyStore(
        @ApplicationContext context: Context,
        @Dispatcher(SproutDispatchers.IO) dispatcher: CoroutineDispatcher,
    ): DeviceKeyStore = AndroidKeystoreDeviceKeyStore(context, dispatcher)

    @Provides
    @Singleton
    fun parentAuthKeyStore(
        @ApplicationContext context: Context,
        @Dispatcher(SproutDispatchers.IO) dispatcher: CoroutineDispatcher,
    ): ParentAuthKeyStore = AndroidKeystoreParentAuthKeyStore(context, dispatcher)

    @Provides
    @Singleton
    fun parentAuthAvailabilityChecker(@ApplicationContext context: Context): ParentAuthAvailabilityChecker =
        BiometricParentAuthAvailabilityChecker(context)

    @Provides
    @Singleton
    fun parentAuthorizer(
        keyStore: ParentAuthKeyStore,
        availabilityChecker: ParentAuthAvailabilityChecker,
    ): ParentAuthorizer = KeystoreParentAuthorizer(keyStore, availabilityChecker)

    @Provides
    @Singleton
    fun secretStore(
        @ApplicationContext context: Context,
        random: SecureRandom,
        @Dispatcher(SproutDispatchers.IO) dispatcher: CoroutineDispatcher,
    ): SecretStore = WrappedSecretStore(
        wrapper = AndroidKeystoreKeyWrapper(context),
        storage = FilesDirBlobStorage(context, dispatcher),
        random = random,
        dispatcher = dispatcher,
    )
}

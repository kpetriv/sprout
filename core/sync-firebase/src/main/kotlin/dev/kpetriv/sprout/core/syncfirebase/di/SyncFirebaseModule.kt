package dev.kpetriv.sprout.core.syncfirebase.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.kpetriv.sprout.core.syncfirebase.CallableInvoker
import dev.kpetriv.sprout.core.syncfirebase.FirebaseCallableInvoker
import dev.kpetriv.sprout.core.syncfirebase.FirestoreFacade
import dev.kpetriv.sprout.core.syncfirebase.SdkFirestoreFacade

/**
 * Binds the SDK seams. `app` provides [dev.kpetriv.sprout.core.syncfirebase.FirebaseConfig], a
 * [dev.kpetriv.sprout.core.syncfirebase.HouseholdScope], the
 * [dev.kpetriv.sprout.core.syncfirebase.AppForeground] signal, and binds `SyncTransport` to
 * [dev.kpetriv.sprout.core.syncfirebase.FirebaseSyncTransport].
 */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class SyncFirebaseModule {
    @Binds
    abstract fun firestoreFacade(impl: SdkFirestoreFacade): FirestoreFacade

    @Binds
    abstract fun callableInvoker(impl: FirebaseCallableInvoker): CallableInvoker
}

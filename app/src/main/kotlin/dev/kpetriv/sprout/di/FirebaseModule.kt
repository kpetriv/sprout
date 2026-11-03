package dev.kpetriv.sprout.di

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.kpetriv.sprout.BuildConfig
import dev.kpetriv.sprout.core.database.RoomOutboxSource
import dev.kpetriv.sprout.core.sync.RemoteApplier
import dev.kpetriv.sprout.core.sync.SyncClock
import dev.kpetriv.sprout.core.sync.SyncEngine
import dev.kpetriv.sprout.core.sync.SyncTransport
import dev.kpetriv.sprout.core.syncfirebase.AppForeground
import dev.kpetriv.sprout.core.syncfirebase.FirebaseConfig
import dev.kpetriv.sprout.core.syncfirebase.FirebaseSyncTransport
import dev.kpetriv.sprout.core.syncfirebase.HouseholdScope
import dev.kpetriv.sprout.core.time.Clock
import dev.kpetriv.sprout.sync.SessionHouseholdScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Singleton

/**
 * What core/sync-firebase needs from the app (M3 §5, D-M3-1/2/6) and the sync engine built on it:
 * - [FirebaseConfig] from `BuildConfig`: debug builds use the Emulator Suite at `sprout.firebaseEmulatorHost` (default
 *   `10.0.2.2`) and the debug App Check provider; release builds the real project and Play Integrity;
 * - the synced household and this device from the session ([SessionHouseholdScope]);
 * - the [AppForeground] signal from [ProcessLifecycleOwner] (snapshot listeners only while started);
 * - [SyncTransport] = [FirebaseSyncTransport], and one process-wide [SyncEngine] over Room's outbox and applier.
 */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class FirebaseModule {
    @Binds
    abstract fun householdScope(impl: SessionHouseholdScope): HouseholdScope

    @Binds
    abstract fun syncTransport(impl: FirebaseSyncTransport): SyncTransport

    companion object {
        /**
         * Callables go to the HTTP server (`server/`, D-M3-29) at `BuildConfig.FUNCTIONS_BASE_URL` (gradle property
         * `sprout.functionsBaseUrl`; debug default the dev server `http://10.0.2.2:8787`, release null = Cloud
         * Functions unless the property is set).
         */
        @Provides
        @Singleton
        fun firebaseConfig(): FirebaseConfig = FirebaseConfig(
            emulatorHost = BuildConfig.FIREBASE_EMULATOR_HOST,
            debugAppCheck = BuildConfig.DEBUG,
            googleServerClientId = BuildConfig.GOOGLE_SERVER_CLIENT_ID,
            functionsBaseUrl = BuildConfig.FUNCTIONS_BASE_URL,
        )

        @Provides
        @AppForeground
        fun appForeground(): Flow<Boolean> = ProcessLifecycleOwner.get().lifecycle.currentStateFlow
            .map { it.isAtLeast(Lifecycle.State.STARTED) }
            .distinctUntilChanged()

        @Provides
        fun syncClock(clock: Clock): SyncClock = SyncClock { clock.now().toEpochMilliseconds() }

        /** One engine per process: its mutex serialises the foreground loop and the SyncWorker. */
        @Provides
        @Singleton
        fun syncEngine(
            transport: SyncTransport,
            outbox: RoomOutboxSource,
            applier: RemoteApplier,
            clock: SyncClock,
        ): SyncEngine = SyncEngine(transport, outbox, applier, clock)
    }
}

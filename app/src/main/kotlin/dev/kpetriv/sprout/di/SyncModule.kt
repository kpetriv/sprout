package dev.kpetriv.sprout.di

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.kpetriv.sprout.core.database.RoomOutboxSource
import dev.kpetriv.sprout.sync.AppRestarter
import dev.kpetriv.sprout.sync.AuthStateReader
import dev.kpetriv.sprout.sync.BackgroundSync
import dev.kpetriv.sprout.sync.EngineSyncRunner
import dev.kpetriv.sprout.sync.FirebaseAuthStateReader
import dev.kpetriv.sprout.sync.ForegroundState
import dev.kpetriv.sprout.sync.HouseholdRemovals
import dev.kpetriv.sprout.sync.LocalDataEraser
import dev.kpetriv.sprout.sync.OutboxSyncTrigger
import dev.kpetriv.sprout.sync.ProcessAppRestarter
import dev.kpetriv.sprout.sync.RejectionAuditor
import dev.kpetriv.sprout.sync.SessionBackgroundSync
import dev.kpetriv.sprout.sync.SharedShellPreferences
import dev.kpetriv.sprout.sync.ShellPreferences
import dev.kpetriv.sprout.sync.SyncController
import dev.kpetriv.sprout.sync.SyncRunner
import dev.kpetriv.sprout.sync.SyncScheduler
import dev.kpetriv.sprout.sync.SyncTrigger
import dev.kpetriv.sprout.sync.WipingLocalDataEraser
import dev.kpetriv.sprout.sync.WorkManagerSyncScheduler
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** The app's sync shell: trigger, scheduling, worker seam, session reconciliation, wipe and restart (M3 §6). */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class SyncModule {
    @Binds
    abstract fun syncRunner(impl: EngineSyncRunner): SyncRunner

    @Binds
    abstract fun syncTrigger(impl: OutboxSyncTrigger): SyncTrigger

    @Binds
    abstract fun syncScheduler(impl: WorkManagerSyncScheduler): SyncScheduler

    @Binds
    abstract fun shellPreferences(impl: SharedShellPreferences): ShellPreferences

    @Binds
    abstract fun backgroundSync(impl: SessionBackgroundSync): BackgroundSync

    @Binds
    abstract fun authStateReader(impl: FirebaseAuthStateReader): AuthStateReader

    @Binds
    abstract fun appRestarter(impl: ProcessAppRestarter): AppRestarter

    @Binds
    abstract fun localDataEraser(impl: WipingLocalDataEraser): LocalDataEraser

    companion object {
        @Provides
        fun householdRemovals(sync: SyncController): HouseholdRemovals = HouseholdRemovals(sync::householdGone)

        @Provides
        fun rejectionAuditor(outbox: RoomOutboxSource): RejectionAuditor = RejectionAuditor(outbox::auditRejections)

        @Provides
        fun foregroundState(): ForegroundState = object : ForegroundState {
            private val lifecycle get() = ProcessLifecycleOwner.get().lifecycle

            override fun isForeground(): Boolean = lifecycle.currentStateFlow.value.isAtLeast(Lifecycle.State.STARTED)

            override fun changes(): Flow<Boolean> =
                lifecycle.currentStateFlow.map { it.isAtLeast(Lifecycle.State.STARTED) }.distinctUntilChanged()
        }
    }
}

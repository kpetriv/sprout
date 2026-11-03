package dev.kpetriv.sprout.feature.parenthome.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ViewModelComponent
import dev.kpetriv.sprout.core.database.usecase.DrainPendingParentActionsUseCase
import dev.kpetriv.sprout.feature.parenthome.PendingActionSigner

/** Parent dashboard bindings that need nothing from the app. Repositories come from `core/database`. */
@Module
@InstallIn(ViewModelComponent::class)
object ParentHomeModule {
    /** Each prompt carries the describer's reason ("Pay Leo 3.00 GBP allowance for 2026-W45"). */
    @Provides
    fun pendingActionSigner(drain: DrainPendingParentActionsUseCase): PendingActionSigner =
        PendingActionSigner { requester -> drain.drainWith(requester) }
}

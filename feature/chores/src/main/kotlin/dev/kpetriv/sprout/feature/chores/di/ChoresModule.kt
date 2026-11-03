package dev.kpetriv.sprout.feature.chores.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ViewModelComponent
import dev.kpetriv.sprout.feature.chores.ApprovalReasonText
import dev.kpetriv.sprout.feature.chores.PhotoDecoder
import dev.kpetriv.sprout.feature.chores.ResourceApprovalReasonText
import dev.kpetriv.sprout.feature.chores.SubmissionApprover
import dev.kpetriv.sprout.feature.chores.UseCaseSubmissionApprover

/**
 * Chores bindings that need nothing from the app. `core/domain-ledger`'s `ParentAuthorizationRequester` is bound by
 * `feature/lock`; the repositories, `PhotoStore`, `FiatCurrencyProvider`, `Clock` and `HouseholdCalendar` come from
 * `core/database` and `core/common`.
 */
@Module
@InstallIn(ViewModelComponent::class)
abstract class ChoresModule {
    @Binds
    internal abstract fun submissionApprover(impl: UseCaseSubmissionApprover): SubmissionApprover

    @Binds
    internal abstract fun approvalReasonText(impl: ResourceApprovalReasonText): ApprovalReasonText

    companion object {
        @Provides
        fun photoDecoder(): PhotoDecoder = PhotoDecoder.Platform
    }
}

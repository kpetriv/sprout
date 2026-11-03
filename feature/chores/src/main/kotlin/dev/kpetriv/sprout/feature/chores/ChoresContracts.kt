package dev.kpetriv.sprout.feature.chores

import dev.kpetriv.sprout.core.database.usecase.ApprovalResult
import dev.kpetriv.sprout.core.database.usecase.ApproveSubmissionUseCase
import dev.kpetriv.sprout.core.domainhousehold.ChoreRewardIntent
import dev.kpetriv.sprout.core.domainledger.ParentAuthorization
import dev.kpetriv.sprout.core.model.SubmissionId
import javax.inject.Inject

/**
 * Approves one submission, paying each reward currency with its own authorisation. Bound to
 * [ApproveSubmissionUseCase]; a seam so the approvals inbox can be tested without a ledger. Internal, so the data
 * layer's [ApprovalResult] stays out of this module's API (`core/database` is an implementation dependency).
 */
internal fun interface SubmissionApprover {
    /** See [ApproveSubmissionUseCase.invoke]: [authorize] is called once per reward still to pay. */
    suspend fun approve(
        submissionId: SubmissionId,
        authorize: suspend (ChoreRewardIntent) -> ParentAuthorization,
    ): ApprovalResult
}

/** [SubmissionApprover] over the data layer's [ApproveSubmissionUseCase]. */
internal class UseCaseSubmissionApprover @Inject constructor(private val useCase: ApproveSubmissionUseCase) :
    SubmissionApprover {
    override suspend fun approve(
        submissionId: SubmissionId,
        authorize: suspend (ChoreRewardIntent) -> ParentAuthorization,
    ): ApprovalResult = useCase(submissionId, authorize)
}

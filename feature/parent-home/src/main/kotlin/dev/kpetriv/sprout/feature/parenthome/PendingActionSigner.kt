package dev.kpetriv.sprout.feature.parenthome

import dev.kpetriv.sprout.core.database.usecase.DrainResult
import dev.kpetriv.sprout.core.domainledger.ParentAuthorizationRequester

/**
 * Signs every queued allowance and interest payment, prompting through [requester] once per event with a reason that
 * names the kid, the amount and the period. Bound in [dev.kpetriv.sprout.feature.parenthome.di.ParentHomeModule] to
 * `DrainPendingParentActionsUseCase.drainWith`; a seam so the ViewModel can be tested without Room. Throws whatever
 * the requester throws (the parent cancelled the prompt).
 */
fun interface PendingActionSigner {
    suspend fun signAll(requester: ParentAuthorizationRequester): DrainResult
}

package dev.kpetriv.sprout.feature.chores

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kpetriv.sprout.core.designsystem.components.Pictogram
import dev.kpetriv.sprout.core.designsystem.components.SproutTopBar
import dev.kpetriv.sprout.core.designsystem.theme.SproutSpacing
import dev.kpetriv.sprout.core.ui.ContentWidth
import dev.kpetriv.sprout.core.ui.withGutters

/** Navigation entry for the parent's approvals inbox (submissions and chore proposals), a pushed screen. */
@Composable
fun ApprovalsInboxRoute(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val viewModel = choresViewModel<ApprovalsViewModel>(key = "chores:approvals")
    val state by viewModel.state.collectAsStateWithLifecycle()
    ApprovalsInboxScreen(state = state, onEvent = viewModel::onEvent, onBack = onBack, modifier = modifier)
}

/**
 * Pending submissions newest first (photo, note, Approve / Reject) and Sapling kids' proposals (Accept / Decline), as
 * white cards with the kid's avatar, on parent tokens. A pushed screen: the bar has a back arrow. Results arrive as
 * snackbars; Reject opens a sheet for an optional reason.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApprovalsInboxScreen(
    state: ApprovalsUiState,
    onEvent: (ApprovalsEvent) -> Unit,
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
) {
    val snackbar = remember { SnackbarHostState() }
    val ready = state as? ApprovalsUiState.Ready
    Scaffold(
        modifier = modifier,
        topBar = { SproutTopBar(title = stringResource(R.string.feature_chores_approvals_title), onBack = onBack) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            when (state) {
                ApprovalsUiState.Loading -> LoadingContent()

                ApprovalsUiState.Error -> ErrorContent(
                    text = stringResource(R.string.feature_chores_error_load_failed),
                    onRetry = { onEvent(ApprovalsEvent.Retry) },
                )

                is ApprovalsUiState.Ready -> if (state.isEmpty) EmptyInbox() else Inbox(state, onEvent)
            }
        }
    }
    if (ready != null) {
        MessageEffect(snackbar, ready.message, ready.message?.let { approvalsMessage(it) }) {
            onEvent(ApprovalsEvent.MessageShown)
        }
        val rejecting = ready.rejecting
        if (rejecting != null) {
            ModalBottomSheet(
                onDismissRequest = { onEvent(ApprovalsEvent.DismissReject) },
                containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
            ) {
                RejectForm(
                    reject = rejecting,
                    busy = ready.busy != null,
                    onReasonChange = { onEvent(ApprovalsEvent.RejectReasonChanged(it)) },
                    onConfirm = { onEvent(ApprovalsEvent.ConfirmReject) },
                    onCancel = { onEvent(ApprovalsEvent.DismissReject) },
                    modifier = Modifier.padding(
                        start = SproutSpacing.lg,
                        end = SproutSpacing.lg,
                        bottom = SproutSpacing.lg,
                    ),
                )
            }
        }
    }
}

@Composable
private fun EmptyInbox() {
    Column(
        modifier = Modifier.fillMaxSize().padding(SproutSpacing.lg),
        verticalArrangement = Arrangement.spacedBy(SproutSpacing.md, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Pictogram(Pictogram.PARTY, size = EmptyPictogram)
        Text(
            text = stringResource(R.string.feature_chores_approvals_empty),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun Inbox(state: ApprovalsUiState.Ready, onEvent: (ApprovalsEvent) -> Unit) {
    ContentWidth(modifier = Modifier.fillMaxSize()) { gutters ->
        LazyColumn(
            contentPadding = PaddingValues(horizontal = SproutSpacing.gutter, vertical = SproutSpacing.md)
                .withGutters(gutters),
            verticalArrangement = Arrangement.spacedBy(SproutSpacing.md),
            modifier = Modifier.fillMaxSize(),
        ) {
            if (state.submissions.isNotEmpty()) {
                item(key = "submissions") {
                    SectionHeading(
                        text = stringResource(R.string.feature_chores_approvals_submissions),
                        style = MaterialTheme.typography.titleLarge,
                    )
                }
                items(state.submissions, key = { it.id.value }) { submission ->
                    SubmissionCard(
                        submission = submission,
                        tintIndex = submission.tintIndex,
                        busy = state.busy == submission.id.value,
                        enabled = state.busy == null,
                        onApprove = { onEvent(ApprovalsEvent.Approve(submission.id)) },
                        onReject = { onEvent(ApprovalsEvent.OpenReject(submission.id)) },
                    )
                }
            }
            if (state.proposals.isNotEmpty()) {
                item(key = "proposals") {
                    SectionHeading(
                        text = stringResource(R.string.feature_chores_approvals_proposals),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(top = SproutSpacing.sm),
                    )
                }
                items(state.proposals, key = { it.choreId.value }) { proposal ->
                    ProposalCard(
                        proposal = proposal,
                        tintIndex = proposal.tintIndex,
                        enabled = state.busy == null,
                        onAccept = { onEvent(ApprovalsEvent.AcceptProposal(proposal.choreId)) },
                        onDecline = { onEvent(ApprovalsEvent.DeclineProposal(proposal.choreId)) },
                    )
                }
            }
        }
    }
}

@Composable
private fun approvalsMessage(message: ApprovalsMessage): String = when (message) {
    is ApprovalsMessage.Approved -> if (message.unsigned) {
        approvedText(message) + " " + stringResource(R.string.feature_chores_approved_unsigned_suffix)
    } else {
        approvedText(message)
    }

    is ApprovalsMessage.Rejected -> stringResource(R.string.feature_chores_rejected_message, message.choreTitle)

    is ApprovalsMessage.SignatureRequired, is ApprovalsMessage.RewardRefused, is ApprovalsMessage.PartlyPaid,
    is ApprovalsMessage.KeyInvalidated, is ApprovalsMessage.AlreadyPaid, is ApprovalsMessage.AuthorizationCancelled,
    -> unfinishedApprovalMessage(message)

    is ApprovalsMessage.NoAccount -> stringResource(R.string.feature_chores_no_account_message, message.kidName)

    ApprovalsMessage.NotFound -> stringResource(R.string.feature_chores_not_found_message)

    is ApprovalsMessage.ProposalAccepted -> stringResource(R.string.feature_chores_proposal_accepted, message.title)

    is ApprovalsMessage.ProposalDeclined -> stringResource(R.string.feature_chores_proposal_declined, message.title)

    ApprovalsMessage.Failed -> stringResource(R.string.feature_chores_something_failed)
}

/** Why a submission is still in the inbox after the parent acted on it. */
@Composable
private fun unfinishedApprovalMessage(message: ApprovalsMessage): String = when (message) {
    is ApprovalsMessage.SignatureRequired ->
        stringResource(R.string.feature_chores_signature_required_message, message.choreTitle)

    is ApprovalsMessage.RewardRefused -> stringResource(R.string.feature_chores_refused_message, message.choreTitle)

    is ApprovalsMessage.PartlyPaid -> stringResource(
        R.string.feature_chores_partly_paid_message,
        message.choreTitle,
        message.paid,
        message.total,
    )

    is ApprovalsMessage.KeyInvalidated ->
        stringResource(R.string.feature_chores_key_invalidated_message, message.choreTitle)

    is ApprovalsMessage.AlreadyPaid -> stringResource(R.string.feature_chores_already_paid_message, message.choreTitle)

    is ApprovalsMessage.AuthorizationCancelled ->
        stringResource(R.string.feature_chores_cancelled_message, message.choreTitle)

    else -> stringResource(R.string.feature_chores_something_failed)
}

@Composable
private fun approvedText(message: ApprovalsMessage.Approved): String = when {
    message.alreadyPaid == 0 ->
        stringResource(R.string.feature_chores_approved_message, message.kidName, message.choreTitle)

    message.paidNow == 0 ->
        stringResource(R.string.feature_chores_all_already_paid, message.kidName, message.choreTitle)

    else -> pluralStringResource(
        R.plurals.feature_chores_approved_already_paid,
        message.alreadyPaid,
        message.kidName,
        message.choreTitle,
        message.alreadyPaid,
    )
}

private val EmptyPictogram = 96.dp

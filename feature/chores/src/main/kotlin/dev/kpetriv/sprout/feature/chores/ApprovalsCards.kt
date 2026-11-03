package dev.kpetriv.sprout.feature.chores

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.kpetriv.sprout.core.model.MAX_NOTE_LENGTH
import dev.kpetriv.sprout.core.ui.rememberMoneyFormatter

private val PhotoHeight = 180.dp

@Composable
internal fun SubmissionCard(
    submission: SubmissionUi,
    busy: Boolean,
    enabled: Boolean,
    onApprove: () -> Unit,
    onReject: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val formatter = rememberMoneyFormatter()
    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(modifier = Modifier.semantics(mergeDescendants = true) {}) {
                Text(
                    text = stringResource(
                        R.string.feature_chores_submission_heading,
                        submission.kidName,
                        submission.choreTitle,
                    ),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(R.string.feature_chores_due_on, formatDate(submission.dueOn)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(text = submission.reward.format(formatter), style = MaterialTheme.typography.labelLarge)
                submission.note?.let {
                    Text(
                        text = stringResource(R.string.feature_chores_submission_note, it),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
            submission.photo?.let { SubmissionPhoto(it, submission.kidName, submission.choreTitle) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                val rejectDescription =
                    stringResource(R.string.feature_chores_reject_described, submission.choreTitle, submission.kidName)
                val approveDescription = stringResource(
                    if (submission.partlyPaid) {
                        R.string.feature_chores_finish_approval_described
                    } else {
                        R.string.feature_chores_approve_described
                    },
                    submission.choreTitle,
                    submission.kidName,
                )
                OutlinedButton(
                    onClick = onReject,
                    enabled = enabled,
                    modifier = Modifier.weight(1f).semantics { contentDescription = rejectDescription },
                ) { Text(stringResource(R.string.feature_chores_reject)) }
                Button(
                    onClick = onApprove,
                    enabled = enabled,
                    modifier = Modifier.weight(1f).semantics { contentDescription = approveDescription },
                ) {
                    if (busy) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Text(
                            stringResource(
                                if (submission.partlyPaid) {
                                    R.string.feature_chores_finish_approval
                                } else {
                                    R.string.feature_chores_approve
                                },
                            ),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SubmissionPhoto(photo: PhotoUi, kidName: String, choreTitle: String) {
    val shape = MaterialTheme.shapes.medium
    when (photo) {
        is PhotoUi.Loaded -> Image(
            bitmap = photo.image,
            contentDescription = stringResource(R.string.feature_chores_photo_from, kidName, choreTitle),
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxWidth().height(PhotoHeight).clip(shape),
        )

        PhotoUi.Loading -> {
            val description = stringResource(R.string.feature_chores_photo_loading)
            Box(
                modifier = Modifier.fillMaxWidth().height(PhotoHeight).semantics { contentDescription = description },
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }
        }

        PhotoUi.Unavailable -> Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = shape,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = stringResource(R.string.feature_chores_photo_unavailable),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}

@Composable
internal fun ProposalCard(
    proposal: ProposalUi,
    enabled: Boolean,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val formatter = rememberMoneyFormatter()
    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(modifier = Modifier.semantics(mergeDescendants = true) {}) {
                Text(
                    text = stringResource(R.string.feature_chores_proposal_heading, proposal.kidName, proposal.title),
                    style = MaterialTheme.typography.titleMedium,
                )
                proposal.description?.let { Text(text = it, style = MaterialTheme.typography.bodyMedium) }
                Text(text = scheduleLabel(proposal.schedule), style = MaterialTheme.typography.bodyMedium)
                Text(text = proposal.reward.format(formatter), style = MaterialTheme.typography.labelLarge)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = onDecline, enabled = enabled, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.feature_chores_decline))
                }
                Button(onClick = onAccept, enabled = enabled, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.feature_chores_accept))
                }
            }
        }
    }
}

/** The reject sheet's content: an optional reason (at most [MAX_NOTE_LENGTH] characters) and the buttons. */
@Composable
internal fun RejectForm(
    reject: RejectUi,
    busy: Boolean,
    onReasonChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SectionHeading(
            text = stringResource(R.string.feature_chores_reject_title, reject.choreTitle),
            style = MaterialTheme.typography.titleLarge,
        )
        OutlinedTextField(
            value = reject.reason,
            onValueChange = onReasonChange,
            label = { Text(stringResource(R.string.feature_chores_reject_reason)) },
            supportingText = {
                Text(stringResource(R.string.feature_chores_counter, reject.reason.length, MAX_NOTE_LENGTH))
            },
            minLines = 2,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = onCancel, enabled = !busy, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.feature_chores_cancel))
            }
            Button(onClick = onConfirm, enabled = !busy, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.feature_chores_reject))
            }
        }
    }
}

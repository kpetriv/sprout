package dev.kpetriv.sprout.feature.chores

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.kpetriv.sprout.core.designsystem.components.InitialAvatar
import dev.kpetriv.sprout.core.designsystem.components.SproutButton
import dev.kpetriv.sprout.core.designsystem.components.SproutButtonStyle
import dev.kpetriv.sprout.core.designsystem.components.SproutCard
import dev.kpetriv.sprout.core.designsystem.icon.SproutIcons
import dev.kpetriv.sprout.core.designsystem.theme.STACK_FONT_SCALE
import dev.kpetriv.sprout.core.designsystem.theme.SproutSpacing
import dev.kpetriv.sprout.core.model.KidUiTier
import dev.kpetriv.sprout.core.model.MAX_NOTE_LENGTH
import dev.kpetriv.sprout.core.ui.MoneyFormatter
import dev.kpetriv.sprout.core.ui.rememberMoneyFormatter

private val PhotoHeight = 180.dp
private val AvatarSize = 44.dp

/**
 * A submission waiting for the parent: the kid's avatar ([tintIndex], their place in the household) beside
 * "Leo: Make bed", the due date, the reward in Botanical and the kid's note, then the photo (or why there is none),
 * and the Reject (Linen) and Approve (Botanical) pills.
 */
@Composable
internal fun SubmissionCard(
    submission: SubmissionUi,
    tintIndex: Int,
    busy: Boolean,
    enabled: Boolean,
    onApprove: () -> Unit,
    onReject: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val formatter = rememberMoneyFormatter()
    SproutCard(modifier = modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(SproutSpacing.md)) {
            KidHeader(
                kidName = submission.kidName,
                tintIndex = tintIndex,
                heading = stringResource(
                    R.string.feature_chores_submission_heading,
                    submission.kidName,
                    submission.choreTitle,
                ),
                lines = listOf(stringResource(R.string.feature_chores_due_on, formatDate(submission.dueOn))),
                reward = submission.reward,
                formatter = formatter,
                note = submission.note?.let { stringResource(R.string.feature_chores_submission_note, it) },
            )
            submission.photo?.let { SubmissionPhoto(it, submission.kidName, submission.choreTitle) }
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
            DecisionButtons(
                no = stringResource(R.string.feature_chores_reject),
                noDescription = rejectDescription,
                onNo = onReject,
                yes = stringResource(
                    if (submission.partlyPaid) {
                        R.string.feature_chores_finish_approval
                    } else {
                        R.string.feature_chores_approve
                    },
                ),
                yesDescription = approveDescription,
                onYes = onApprove,
                enabled = enabled,
                busy = busy,
            )
        }
    }
}

/** The avatar beside a heading, detail [lines], the reward and an optional quoted [note], read as one. */
@Composable
private fun KidHeader(
    kidName: String,
    tintIndex: Int,
    heading: String,
    lines: List<String>,
    reward: RewardUi,
    formatter: MoneyFormatter,
    note: String? = null,
) {
    Row(
        modifier = Modifier.semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(SproutSpacing.md),
    ) {
        InitialAvatar(
            initial = kidName.take(1).uppercase(),
            tintIndex = tintIndex,
            size = AvatarSize,
            modifier = Modifier.clearAndSetSemantics {},
        )
        Column(verticalArrangement = Arrangement.spacedBy(SproutSpacing.xs)) {
            Text(text = heading, style = MaterialTheme.typography.titleMedium)
            lines.forEach {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            RewardText(reward, formatter, KidUiTier.SAPLING, MaterialTheme.typography.titleMedium)
            note?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(top = SproutSpacing.xs),
                )
            }
        }
    }
}

/**
 * The "no" (Linen) and "yes" (Botanical) pills side by side, stacked at large text sizes. While [busy], a spinner
 * stands before them.
 */
@Composable
private fun DecisionButtons(
    no: String,
    onNo: () -> Unit,
    yes: String,
    onYes: () -> Unit,
    enabled: Boolean,
    noDescription: String? = null,
    yesDescription: String? = null,
    yesIcon: ImageVector = SproutIcons.Check,
    busy: Boolean = false,
) {
    val noModifier = noDescription?.let { Modifier.semantics { contentDescription = it } } ?: Modifier
    val yesModifier = yesDescription?.let { Modifier.semantics { contentDescription = it } } ?: Modifier
    if (LocalDensity.current.fontScale >= STACK_FONT_SCALE) {
        Column(verticalArrangement = Arrangement.spacedBy(SproutSpacing.sm)) {
            if (busy) Busy()
            SproutButton(yes, onYes, yesModifier.fillMaxWidth(), enabled = enabled, icon = yesIcon)
            SproutButton(no, onNo, noModifier.fillMaxWidth(), style = SproutButtonStyle.Tertiary, enabled = enabled)
        }
    } else {
        Row(
            horizontalArrangement = Arrangement.spacedBy(SproutSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (busy) Busy()
            SproutButton(no, onNo, noModifier.weight(1f), style = SproutButtonStyle.Tertiary, enabled = enabled)
            SproutButton(yes, onYes, yesModifier.weight(1f), enabled = enabled, icon = yesIcon)
        }
    }
}

@Composable
private fun Busy() {
    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
}

@Composable
private fun SubmissionPhoto(photo: PhotoUi, kidName: String, choreTitle: String) {
    val shape = MaterialTheme.shapes.small
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

        PhotoUi.Unavailable -> PhotoNote(SproutIcons.Close, stringResource(R.string.feature_chores_photo_unavailable))

        PhotoUi.OnKidDevice -> PhotoNote(SproutIcons.Lock, stringResource(R.string.feature_chores_photo_on_kid_device))
    }
}

/** Why there is no photo to show, on a Linen panel with an icon. */
@Composable
private fun PhotoNote(icon: ImageVector, text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow, MaterialTheme.shapes.small)
            .padding(SproutSpacing.md),
        horizontalArrangement = Arrangement.spacedBy(SproutSpacing.sm + SproutSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text = text, style = MaterialTheme.typography.bodyMedium)
    }
}

/** A Sapling kid's chore idea: avatar, "Ava suggests: Walk the dog", schedule and reward, Decline and Accept. */
@Composable
internal fun ProposalCard(
    proposal: ProposalUi,
    tintIndex: Int,
    enabled: Boolean,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val formatter = rememberMoneyFormatter()
    SproutCard(modifier = modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(SproutSpacing.md)) {
            KidHeader(
                kidName = proposal.kidName,
                tintIndex = tintIndex,
                heading = stringResource(R.string.feature_chores_proposal_heading, proposal.kidName, proposal.title),
                lines = listOfNotNull(proposal.description, scheduleLabel(proposal.schedule)),
                reward = proposal.reward,
                formatter = formatter,
            )
            DecisionButtons(
                no = stringResource(R.string.feature_chores_decline),
                onNo = onDecline,
                yes = stringResource(R.string.feature_chores_accept),
                onYes = onAccept,
                enabled = enabled,
            )
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
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(SproutSpacing.md)) {
        SectionHeading(
            text = stringResource(R.string.feature_chores_reject_title, reject.choreTitle),
            style = MaterialTheme.typography.headlineSmall,
        )
        OutlinedTextField(
            value = reject.reason,
            onValueChange = onReasonChange,
            label = { Text(stringResource(R.string.feature_chores_reject_reason)) },
            supportingText = {
                Text(stringResource(R.string.feature_chores_counter, reject.reason.length, MAX_NOTE_LENGTH))
            },
            minLines = 2,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth(),
        )
        DecisionButtons(
            no = stringResource(R.string.feature_chores_cancel),
            onNo = onCancel,
            yes = stringResource(R.string.feature_chores_reject),
            onYes = onConfirm,
            enabled = !busy,
            yesIcon = SproutIcons.Close,
        )
    }
}

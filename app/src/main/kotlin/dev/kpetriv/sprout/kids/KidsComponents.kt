package dev.kpetriv.sprout.kids

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.kpetriv.sprout.R
import dev.kpetriv.sprout.core.designsystem.theme.SproutTheme
import dev.kpetriv.sprout.core.model.AgeBand
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.KidUiTier
import dev.kpetriv.sprout.startup.StartupFailure

/**
 * Picks a kid from [state] (the [KidsViewModel] list) for [purpose]: one row per kid with age band and UI tier.
 * Shows a spinner while loading and a message when there are no kids or they could not be read.
 */
@Composable
fun KidPickerDialog(state: KidsUiState, purpose: KidPickPurpose, onPick: (KidId) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    when (purpose) {
                        KidPickPurpose.KID_MODE -> R.string.kids_picker_kid_mode_title
                        KidPickPurpose.SHOP -> R.string.kids_picker_shop_title
                    },
                ),
            )
        },
        text = {
            when (state) {
                KidsUiState.Loading -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }

                is KidsUiState.Error -> Text(stringResource(R.string.kids_error))

                is KidsUiState.Ready -> if (state.kids.isEmpty()) {
                    Text(stringResource(R.string.kids_empty))
                } else {
                    KidList(state.kids, onPick, Modifier.heightIn(max = 360.dp))
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.kids_cancel)) } },
    )
}

@Composable
private fun KidList(kids: List<KidRow>, onPick: (KidId) -> Unit, modifier: Modifier = Modifier) {
    LazyColumn(modifier = modifier) {
        items(kids, key = { it.id.value }) { kid ->
            ListItem(
                headlineContent = { Text(kid.firstName) },
                supportingContent = { Text(stringResource(kid.ageBand.labelRes())) },
                // A label, not a button: the row is the only focus and click target.
                trailingContent = { TierLabel(stringResource(kid.tier.labelRes())) },
                modifier = Modifier.clickable(role = Role.Button) { onPick(kid.id) },
            )
            HorizontalDivider()
        }
    }
}

/** The tier shown like a chip but with no click action, so it is not a separate focus target. */
@Composable
private fun TierLabel(text: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

/** Full-screen error when the process-start work failed ([StartupFailure]), with a retry button. */
@Composable
fun StartupErrorScreen(failure: StartupFailure, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Surface(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.padding(24.dp).semantics { liveRegion = LiveRegionMode.Polite },
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.startup_failed_title),
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(failure.messageRes()),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
            )
            Button(onClick = onRetry) { Text(stringResource(R.string.startup_retry)) }
        }
    }
}

@StringRes
private fun StartupFailure.messageRes(): Int = when (this) {
    StartupFailure.KEYS -> R.string.startup_failed_keys
    StartupFailure.DATABASE -> R.string.startup_failed_database
    StartupFailure.UNKNOWN -> R.string.startup_failed_unknown
}

@StringRes
private fun AgeBand?.labelRes(): Int = when (this) {
    null -> R.string.kids_age_band_unknown
    AgeBand.UNDER_5 -> R.string.kids_age_band_under_5
    AgeBand.AGES_5_TO_7 -> R.string.kids_age_band_5_to_7
    AgeBand.AGES_8_TO_11 -> R.string.kids_age_band_8_to_11
    AgeBand.AGES_12_PLUS -> R.string.kids_age_band_12_plus
}

@StringRes
private fun KidUiTier.labelRes(): Int = when (this) {
    KidUiTier.SEEDLING -> R.string.kids_tier_seedling
    KidUiTier.SPROUT -> R.string.kids_tier_sprout
    KidUiTier.SAPLING -> R.string.kids_tier_sapling
}

@Preview
@Composable
private fun KidPickerDialogPreview() {
    SproutTheme {
        KidPickerDialog(
            state = KidsUiState.Ready(
                listOf(
                    KidRow(KidId("k1"), "Mia", AgeBand.AGES_5_TO_7, KidUiTier.SEEDLING),
                    KidRow(KidId("k2"), "Leo", AgeBand.AGES_8_TO_11, KidUiTier.SPROUT),
                    KidRow(KidId("k3"), "Zoe", AgeBand.AGES_12_PLUS, KidUiTier.SAPLING),
                ),
            ),
            purpose = KidPickPurpose.KID_MODE,
            onPick = {},
            onDismiss = {},
        )
    }
}

@Preview
@Composable
private fun StartupErrorScreenPreview() {
    SproutTheme {
        StartupErrorScreen(StartupFailure.DATABASE, onRetry = {})
    }
}

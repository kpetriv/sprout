package dev.kpetriv.sprout.feature.chores

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.kpetriv.sprout.core.domainledger.Currency
import dev.kpetriv.sprout.core.domainledger.Money
import dev.kpetriv.sprout.core.ui.rememberMoneyFormatter

/** The "Suggest chores" sheet over the editor (parents only): 3 to 5 idea cards, or loading, empty and error states. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChoreSuggestionsSheet(
    sheet: SuggestionsUi,
    kidName: String?,
    currency: Currency.Fiat,
    onEvent: (ChoreEditorEvent) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = { onEvent(ChoreEditorEvent.SuggestDismissed) }, sheetState = sheetState) {
        ChoreSuggestionsContent(
            sheet = sheet,
            kidName = kidName,
            currency = currency,
            onEvent = onEvent,
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
        )
    }
}

/** The sheet's content; tapping a card fills the editor ([ChoreEditorEvent.SuggestionPicked]), it never saves. */
@Composable
internal fun ChoreSuggestionsContent(
    sheet: SuggestionsUi,
    kidName: String?,
    currency: Currency.Fiat,
    onEvent: (ChoreEditorEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = if (kidName != null) {
                stringResource(R.string.feature_chores_suggest_title_for, kidName)
            } else {
                stringResource(R.string.feature_chores_suggest_title)
            },
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            text = stringResource(R.string.feature_chores_suggest_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        when (sheet) {
            SuggestionsUi.Loading -> SuggestionsLoading()

            is SuggestionsUi.Ready -> sheet.items.forEach { item ->
                SuggestionCard(item, currency, onClick = { onEvent(ChoreEditorEvent.SuggestionPicked(item)) })
            }

            SuggestionsUi.Empty -> SheetMessage(stringResource(R.string.feature_chores_suggest_empty))

            SuggestionsUi.Failed -> {
                SheetMessage(stringResource(R.string.feature_chores_suggest_failed))
                Button(onClick = { onEvent(ChoreEditorEvent.SuggestRetry) }) {
                    Text(stringResource(R.string.feature_chores_retry))
                }
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { onEvent(ChoreEditorEvent.SuggestDismissed) }) {
                Text(stringResource(R.string.feature_chores_suggest_close))
            }
        }
    }
}

@Composable
private fun SuggestionsLoading() {
    val loading = stringResource(R.string.feature_chores_suggest_loading)
    Box(modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(
            modifier = Modifier.semantics {
                contentDescription = loading
                liveRegion = LiveRegionMode.Polite
            },
        )
    }
}

@Composable
private fun SheetMessage(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
}

@Composable
private fun SuggestionCard(item: ChoreSuggestionUi, currency: Currency.Fiat, onClick: () -> Unit) {
    val formatter = rememberMoneyFormatter()
    val reward = RewardUi(item.money, item.minutes?.let(::Money), currency).format(formatter)
    val details = listOf(reward, scheduleLabel(item.schedule)).filter { it.isNotEmpty() }.joinToString(" · ")
    OutlinedCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(item.title, style = MaterialTheme.typography.titleMedium)
            item.description?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(details, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
    }
}

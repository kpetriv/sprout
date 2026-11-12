package dev.kpetriv.sprout.feature.parenthome

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kpetriv.sprout.core.domainledger.Currency
import dev.kpetriv.sprout.core.domainledger.ParentAuthorizationRequester
import dev.kpetriv.sprout.core.model.CalendarDate
import dev.kpetriv.sprout.core.model.DeviceId
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.KidUiTier
import dev.kpetriv.sprout.core.ui.ContentWidthDefaults
import dev.kpetriv.sprout.core.ui.contentWidth
import dev.kpetriv.sprout.core.ui.rememberMoneyFormatter
import dev.kpetriv.sprout.core.ui.rememberWindowWidthClass
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * The parent dashboard. Must sit behind the parent lock.
 *
 * @param requester one strong-biometric prompt per call, with the reason the drain gives (kid, amount, period),
 *   returning its single-use authorisation; the app passes `feature/lock`'s requester.
 * @param onOpenKid the kid's wallet in parent view, where their ledger anomalies are listed.
 * @param onRegisterSigningKey "Signing key not registered with the household: Retry": Settings → Security.
 * @param onOpenAiSettings the summary card's "Download the on-device model": Settings → On-device AI.
 */
@Suppress("LongParameterList") // One callback per destination the dashboard links to.
@Composable
fun ParentHomeRoute(
    requester: ParentAuthorizationRequester,
    onOpenApprovals: () -> Unit,
    onOpenShop: () -> Unit,
    onSwitchToKidMode: () -> Unit,
    onOpenKid: (KidId) -> Unit,
    modifier: Modifier = Modifier,
    onSetUpSync: () -> Unit = {},
    onReviewDevice: (DeviceId) -> Unit = {},
    onRegisterSigningKey: () -> Unit = {},
    onOpenAiSettings: () -> Unit = {},
) {
    val links = ParentHomeLinks(
        onOpenApprovals,
        onOpenShop,
        onSwitchToKidMode,
        onOpenKid,
        onSetUpSync,
        onReviewDevice,
        onRegisterSigningKey,
        onOpenAiSettings,
    )
    val viewModel: ParentHomeViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val current by rememberUpdatedState(requester)
    ParentHomeScreen(
        state = state,
        onEvent = viewModel::onEvent,
        onSignNow = { viewModel.onEvent(ParentHomeEvent.SignNow { reason -> current.request(reason) }) },
        links = links,
        modifier = modifier,
    )
}

/** The dashboard for any [ParentHomeUiState]. [onSignNow] starts signing the queued payments. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ParentHomeScreen(
    state: ParentHomeUiState,
    onEvent: (ParentHomeEvent) -> Unit,
    onSignNow: () -> Unit,
    links: ParentHomeLinks,
    modifier: Modifier = Modifier,
) {
    val snackbar = remember { SnackbarHostState() }
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.feature_parent_home_title),
                        modifier = Modifier.semantics { heading() },
                    )
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            when (state) {
                ParentHomeUiState.Loading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))

                ParentHomeUiState.Error -> Text(
                    stringResource(R.string.feature_parent_home_load_failed),
                    modifier = Modifier.align(Alignment.Center),
                )

                is ParentHomeUiState.Ready -> Dashboard(state, onSignNow, onEvent, links)
            }
        }
    }
    val result = (state as? ParentHomeUiState.Ready)?.signResult
    val resultText = result?.let { signResultText(it) }
    LaunchedEffect(result, resultText) {
        if (resultText != null) {
            snackbar.showSnackbar(resultText)
            onEvent(ParentHomeEvent.SignResultShown)
        }
    }
}

/**
 * The dashboard's cards. Compact: one column in the order below. Medium and wider (D-M7-2): two columns, household
 * status and payments on the left, the kids, the weekly summary and the links on the right, together capped at
 * [TWO_COLUMN_MAX_WIDTH] and centred.
 */
@Composable
private fun Dashboard(
    state: ParentHomeUiState.Ready,
    onSignNow: () -> Unit,
    onEvent: (ParentHomeEvent) -> Unit,
    links: ParentHomeLinks,
) {
    val twoColumns = rememberWindowWidthClass().isAtLeastMedium
    val spacing = Arrangement.spacedBy(12.dp)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .contentWidth(if (twoColumns) TWO_COLUMN_MAX_WIDTH else ContentWidthDefaults.Max)
            .padding(16.dp),
        verticalArrangement = spacing,
    ) {
        if (twoColumns) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = spacing) {
                    StatusCards(state, onSignNow, onEvent, links)
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = spacing) {
                    KidsAndSummary(state, onEvent, links)
                }
            }
        } else {
            StatusCards(state, onSignNow, onEvent, links)
            KidsAndSummary(state, onEvent, links)
        }
    }
}

@Composable
private fun StatusCards(
    state: ParentHomeUiState.Ready,
    onSignNow: () -> Unit,
    onEvent: (ParentHomeEvent) -> Unit,
    links: ParentHomeLinks,
) {
    HouseholdCards(state.household, links)
    ApprovalsCard(state.pendingApprovals, links.onOpenApprovals)
    PendingActionsCard(state.pendingActions, state.signing, onSignNow)
    if (state.attention.isNotEmpty()) AttentionCard(state.attention) { onEvent(ParentHomeEvent.Dismiss(it)) }
    AnomaliesCard(state, links.onOpenKid)
}

@Composable
private fun KidsAndSummary(state: ParentHomeUiState.Ready, onEvent: (ParentHomeEvent) -> Unit, links: ParentHomeLinks) {
    SectionTitle(stringResource(R.string.feature_parent_home_kids_title))
    if (state.kids.isEmpty()) {
        Text(stringResource(R.string.feature_parent_home_kids_none), style = MaterialTheme.typography.bodyMedium)
    }
    state.kids.forEach { KidCard(it, onOpen = { links.onOpenKid(it.kid.id) }) }
    WeeklySummaryCard(
        summary = state.summary,
        week = state.summaryWeek,
        onWeekChange = { onEvent(ParentHomeEvent.SummaryWeekChanged(it)) },
        onDownloadModel = links.onOpenAiSettings,
    )
    QuickLinks(links)
}

/** Two dashboard columns of at most about [ContentWidthDefaults.Max] each. */
private val TWO_COLUMN_MAX_WIDTH = 1_200.dp

@Composable
private fun DashboardCard(
    title: String,
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colors = if (highlighted) {
        CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    } else {
        CardDefaults.cardColors()
    }
    Card(modifier = modifier.fillMaxWidth(), colors = colors) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionTitle(title)
            content()
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
}

@Composable
private fun ApprovalsCard(count: Int, onReview: () -> Unit) {
    DashboardCard(stringResource(R.string.feature_parent_home_approvals_title), highlighted = count > 0) {
        Text(
            if (count == 0) {
                stringResource(R.string.feature_parent_home_approvals_none)
            } else {
                pluralStringResource(R.plurals.feature_parent_home_approvals_count, count, count)
            },
            style = MaterialTheme.typography.bodyLarge,
        )
        if (count > 0) {
            Button(onClick = onReview) { Text(stringResource(R.string.feature_parent_home_approvals_review)) }
        }
    }
}

@Composable
private fun PendingActionsCard(pending: PendingActionsUi, signing: SigningProgress?, onSignNow: () -> Unit) {
    DashboardCard(stringResource(R.string.feature_parent_home_signing_title), highlighted = pending.total > 0) {
        Text(pendingText(pending), style = MaterialTheme.typography.bodyLarge)
        if (signing != null) {
            LinearProgressIndicator(
                progress = { if (signing.total == 0) 0f else signing.signed.toFloat() / signing.total },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                pluralStringResource(
                    R.plurals.feature_parent_home_signing_progress,
                    signing.total,
                    signing.signed,
                    signing.total,
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (pending.total > 0) {
            Button(onClick = onSignNow, enabled = signing == null) {
                Text(stringResource(R.string.feature_parent_home_sign_now))
            }
        }
    }
}

@Composable
private fun AttentionCard(items: List<AttentionItemUi>, onDismiss: (String) -> Unit) {
    DashboardCard(stringResource(R.string.feature_parent_home_attention_title), highlighted = true) {
        Text(
            stringResource(R.string.feature_parent_home_attention_explainer),
            style = MaterialTheme.typography.bodyMedium,
        )
        val formatter = rememberMoneyFormatter()
        items.forEach { item ->
            // One traversal group per payment, so each "Dismiss" is read right after the payment it dismisses.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.semantics { isTraversalGroup = true },
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(
                            when (item.kind) {
                                PaymentKind.ALLOWANCE -> R.string.feature_parent_home_attention_allowance
                                PaymentKind.INTEREST -> R.string.feature_parent_home_attention_interest
                            },
                            item.kidName,
                            formatter.format(item.amount.amount, item.amount.currency),
                            item.period,
                        ),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        stringResource(
                            when (item.reason) {
                                AttentionReason.FAILED -> R.string.feature_parent_home_attention_failed
                                AttentionReason.SKIPPED -> R.string.feature_parent_home_attention_skipped
                            },
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                TextButton(onClick = { onDismiss(item.key) }) {
                    Text(stringResource(R.string.feature_parent_home_attention_dismiss))
                }
            }
        }
    }
}

@Composable
private fun pendingText(pending: PendingActionsUi): String {
    val allowances = pluralStringResource(
        R.plurals.feature_parent_home_signing_allowances,
        pending.allowances,
        pending.allowances,
    )
    val interest =
        pluralStringResource(R.plurals.feature_parent_home_signing_interest, pending.interest, pending.interest)
    val parts = when {
        pending.allowances > 0 && pending.interest > 0 ->
            stringResource(R.string.feature_parent_home_signing_and, allowances, interest)

        pending.allowances > 0 -> allowances

        pending.interest > 0 -> interest

        else -> null
    }
    return parts?.let { stringResource(R.string.feature_parent_home_signing_waiting, it) }
        ?: stringResource(R.string.feature_parent_home_signing_none)
}

@Composable
private fun signResultText(result: SignResult): String = when (result) {
    is SignResult.Finished -> {
        val done = result.signed + result.alreadyPaid
        val parts = mutableListOf(
            pluralStringResource(R.plurals.feature_parent_home_sign_finished, result.total, done, result.total),
        )
        if (result.alreadyPaid > 0) {
            parts += pluralStringResource(
                R.plurals.feature_parent_home_sign_already_paid,
                result.alreadyPaid,
                result.alreadyPaid,
            )
        }
        if (result.failed > 0) {
            parts += pluralStringResource(R.plurals.feature_parent_home_sign_failed, result.failed, result.failed)
        }
        if (result.unsigned > 0) {
            parts += pluralStringResource(R.plurals.feature_parent_home_sign_unsigned, result.unsigned, result.unsigned)
        }
        if (result.needsAttention > 0) {
            parts += pluralStringResource(
                R.plurals.feature_parent_home_sign_needs_attention,
                result.needsAttention,
                result.needsAttention,
            )
        }
        if (result.cancelled > 0) {
            parts +=
                pluralStringResource(R.plurals.feature_parent_home_sign_cancelled, result.cancelled, result.cancelled)
        }
        if (result.replanned > 0) {
            parts +=
                pluralStringResource(R.plurals.feature_parent_home_sign_replanned, result.replanned, result.replanned)
        }
        parts.joinToString(". ")
    }

    is SignResult.Stopped ->
        pluralStringResource(R.plurals.feature_parent_home_sign_stopped, result.total, result.signed, result.total)

    is SignResult.SignatureRequired -> stringResource(R.string.feature_parent_home_sign_signature_required)

    is SignResult.KeyInvalidated -> stringResource(R.string.feature_parent_home_sign_key_invalidated)

    SignResult.AlreadyRunning -> stringResource(R.string.feature_parent_home_sign_already_running)
}

@Composable
private fun AnomaliesCard(state: ParentHomeUiState.Ready, onOpenKid: (KidId) -> Unit) {
    val total = state.anomaliesTotal
    DashboardCard(stringResource(R.string.feature_parent_home_anomalies_title)) {
        Text(
            if (total == 0) {
                stringResource(R.string.feature_parent_home_anomalies_none)
            } else {
                pluralStringResource(R.plurals.feature_parent_home_anomalies_count, total, total)
            },
            style = MaterialTheme.typography.bodyLarge,
        )
        state.firstKidWithAnomalies?.let { kid ->
            OutlinedButton(onClick = { onOpenKid(kid.id) }) {
                Text(stringResource(R.string.feature_parent_home_anomalies_review))
            }
        }
    }
}

@Composable
private fun KidCard(summary: KidSummaryUi, onOpen: () -> Unit) {
    val formatter = rememberMoneyFormatter()
    Card(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    summary.kid.firstName,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                SuggestionChip(onClick = onOpen, label = { Text(tierName(summary.kid.uiTier)) })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                summary.money?.let {
                    Balance(
                        stringResource(R.string.feature_parent_home_kid_money),
                        formatter.format(it.amount, it.currency),
                    )
                }
                summary.minutes?.let {
                    Balance(
                        stringResource(R.string.feature_parent_home_kid_minutes),
                        formatter.format(it, Currency.Minutes),
                    )
                }
            }
            Text(
                summary.nextAllowance?.let {
                    stringResource(R.string.feature_parent_home_kid_next_allowance, formatDate(it))
                } ?: stringResource(R.string.feature_parent_home_kid_no_allowance),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (summary.anomalies > 0) {
                Text(
                    pluralStringResource(
                        R.plurals.feature_parent_home_kid_anomalies,
                        summary.anomalies,
                        summary.anomalies,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun Balance(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Text(value, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun QuickLinks(links: ParentHomeLinks) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle(stringResource(R.string.feature_parent_home_links_title))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // Chores and Settings are in the bottom bar and Approvals is on the attention card (M9 §5.2).
            LinkButton(R.string.feature_parent_home_link_shop, links.onOpenShop)
            LinkButton(R.string.feature_parent_home_link_kid_mode, links.onSwitchToKidMode)
        }
    }
}

@Composable
private fun LinkButton(labelRes: Int, onClick: () -> Unit) {
    TextButton(onClick = onClick) { Text(stringResource(labelRes)) }
}

@Composable
private fun tierName(tier: KidUiTier): String = stringResource(
    when (tier) {
        KidUiTier.SEEDLING -> R.string.feature_parent_home_tier_seedling
        KidUiTier.SPROUT -> R.string.feature_parent_home_tier_sprout
        KidUiTier.SAPLING -> R.string.feature_parent_home_tier_sapling
    },
)

@Composable
private fun formatDate(date: CalendarDate): String {
    val locale = LocalConfiguration.current.locales[0]
    return remember(date, locale) {
        LocalDate.of(date.year, date.month, date.day)
            .format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale))
    }
}

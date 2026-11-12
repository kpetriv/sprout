package dev.kpetriv.sprout.feature.parenthome

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kpetriv.sprout.core.designsystem.components.InitialAvatar
import dev.kpetriv.sprout.core.designsystem.components.SproutButton
import dev.kpetriv.sprout.core.designsystem.components.SproutButtonStyle
import dev.kpetriv.sprout.core.designsystem.components.SproutCard
import dev.kpetriv.sprout.core.designsystem.components.SproutTopBar
import dev.kpetriv.sprout.core.designsystem.components.StatTile
import dev.kpetriv.sprout.core.designsystem.icon.SproutIcons
import dev.kpetriv.sprout.core.designsystem.theme.STACK_FONT_SCALE
import dev.kpetriv.sprout.core.designsystem.theme.SproutSpacing
import dev.kpetriv.sprout.core.domainledger.Currency
import dev.kpetriv.sprout.core.domainledger.ParentAuthorizationRequester
import dev.kpetriv.sprout.core.model.CalendarDate
import dev.kpetriv.sprout.core.model.DeviceId
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.KidUiTier
import dev.kpetriv.sprout.core.ui.ContentWidthDefaults
import dev.kpetriv.sprout.core.ui.MoneyFormatter
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

/**
 * The dashboard for any [ParentHomeUiState], the parent shell's Home tab (no back arrow: Chores, Chat and Settings are
 * in the bar, M9 §5.2). [onSignNow] starts signing the queued payments.
 */
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
        topBar = { SproutTopBar(title = stringResource(R.string.feature_parent_home_title), onBack = null) },
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
 * The dashboard of the "Botanical Linen" render. Compact: one column, the sync line, "Needs you" (one white card of
 * attention rows), the kids, the weekly summary and the Shop and kid-mode pills. Medium and wider (D-M7-2): two
 * columns, the sync line and "Needs you" on the left, the rest on the right, together capped at
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
    val spacing = Arrangement.spacedBy(SproutSpacing.md)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .contentWidth(if (twoColumns) TWO_COLUMN_MAX_WIDTH else ContentWidthDefaults.Max)
            .padding(horizontal = SproutSpacing.gutter, vertical = SproutSpacing.md),
        verticalArrangement = spacing,
    ) {
        if (twoColumns) {
            Row(horizontalArrangement = Arrangement.spacedBy(SproutSpacing.lg)) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = spacing) {
                    NeedsYou(state, onSignNow, onEvent, links)
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = spacing) {
                    KidsAndSummary(state, onEvent, links)
                }
            }
        } else {
            NeedsYou(state, onSignNow, onEvent, links)
            KidsAndSummary(state, onEvent, links, modifier = Modifier.padding(top = SproutSpacing.sm))
        }
    }
}

/**
 * The sync line, then "Needs you": the household's rows ([householdRows]), chores to approve, payments to sign,
 * payments that need attention and the ledger check, in one card.
 */
@Composable
private fun NeedsYou(
    state: ParentHomeUiState.Ready,
    onSignNow: () -> Unit,
    onEvent: (ParentHomeEvent) -> Unit,
    links: ParentHomeLinks,
) {
    state.household.sync?.let { SyncStatusLine(it) }
    SectionTitle(stringResource(R.string.feature_parent_home_needs_you))
    val rows = householdRows(state.household, links) + buildList<@Composable () -> Unit> {
        add { ApprovalsRow(state.pendingApprovals, links.onOpenApprovals) }
        add { PendingActionsRow(state.pendingActions, state.signing, onSignNow) }
        if (state.attention.isNotEmpty()) {
            add { PaymentsAttentionRow(state.attention) { onEvent(ParentHomeEvent.Dismiss(it)) } }
        }
        add { AnomaliesRow(state, links.onOpenKid) }
    }
    AttentionCard(rows)
}

@Composable
private fun KidsAndSummary(
    state: ParentHomeUiState.Ready,
    onEvent: (ParentHomeEvent) -> Unit,
    links: ParentHomeLinks,
    modifier: Modifier = Modifier,
) {
    SectionTitle(stringResource(R.string.feature_parent_home_kids_title), modifier)
    if (state.kids.isEmpty()) {
        Text(
            stringResource(R.string.feature_parent_home_kids_none),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    state.kids.forEachIndexed { index, kid ->
        KidCard(kid, tintIndex = index, onOpen = { links.onOpenKid(kid.kid.id) })
    }
    WeeklySummaryCard(
        summary = state.summary,
        week = state.summaryWeek,
        onWeekChange = { onEvent(ParentHomeEvent.SummaryWeekChanged(it)) },
        onDownloadModel = links.onOpenAiSettings,
        modifier = Modifier.padding(top = SproutSpacing.sm),
    )
    ModeButtons(links)
}

/** Two dashboard columns of at most about [ContentWidthDefaults.Max] each. */
private val TWO_COLUMN_MAX_WIDTH = 1_200.dp

@Composable
private fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleLarge,
        modifier = modifier.padding(top = SproutSpacing.sm).semantics { heading() },
    )
}

/** "Review" on a Linen pill, read as [description] so the rows' two Reviews can be told apart. */
@Composable
private fun ReviewButton(text: String, description: String, onClick: () -> Unit, modifier: Modifier) {
    SproutButton(
        text = text,
        onClick = onClick,
        style = SproutButtonStyle.Tertiary,
        modifier = modifier.semantics { contentDescription = description },
    )
}

@Composable
private fun ApprovalsRow(count: Int, onReview: () -> Unit) {
    AttentionRow(
        lead = AttentionLead.Count(count),
        title = stringResource(R.string.feature_parent_home_approvals_title),
        detail = if (count == 0) {
            stringResource(R.string.feature_parent_home_approvals_none)
        } else {
            pluralStringResource(R.plurals.feature_parent_home_approvals_count, count, count)
        },
        action = if (count > 0) {
            { modifier ->
                ReviewButton(
                    text = stringResource(R.string.feature_parent_home_approvals_review),
                    description = stringResource(R.string.feature_parent_home_approvals_review_described),
                    onClick = onReview,
                    modifier = modifier,
                )
            }
        } else {
            null
        },
    )
}

@Composable
private fun PendingActionsRow(pending: PendingActionsUi, signing: SigningProgress?, onSignNow: () -> Unit) {
    AttentionRow(
        lead = AttentionLead.Count(pending.total),
        title = stringResource(R.string.feature_parent_home_signing_title),
        detail = pendingText(pending),
        action = if (pending.total > 0) {
            { modifier ->
                SproutButton(
                    text = stringResource(R.string.feature_parent_home_sign_now),
                    onClick = onSignNow,
                    enabled = signing == null,
                    modifier = modifier,
                )
            }
        } else {
            null
        },
        extra = signing?.let { progress ->
            {
                LinearProgressIndicator(
                    progress = { if (progress.total == 0) 0f else progress.signed.toFloat() / progress.total },
                    modifier = Modifier.fillMaxWidth().padding(top = SproutSpacing.xs),
                    trackColor = MaterialTheme.colorScheme.surfaceContainer,
                )
                Text(
                    pluralStringResource(
                        R.plurals.feature_parent_home_signing_progress,
                        progress.total,
                        progress.signed,
                        progress.total,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        },
    )
}

/** Payments that won't be paid automatically, each with its own "Dismiss". */
@Composable
private fun PaymentsAttentionRow(items: List<AttentionItemUi>, onDismiss: (String) -> Unit) {
    val formatter = rememberMoneyFormatter()
    AttentionRow(
        lead = AttentionLead.Count(items.size),
        title = stringResource(R.string.feature_parent_home_attention_title),
        detail = stringResource(R.string.feature_parent_home_attention_explainer),
        extra = {
            items.forEach { item ->
                // One traversal group per payment, so each "Dismiss" is read right after the payment it dismisses.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(SproutSpacing.sm),
                    modifier = Modifier.padding(top = SproutSpacing.sm).semantics { isTraversalGroup = true },
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
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    val dismissDescription = stringResource(
                        when (item.kind) {
                            PaymentKind.ALLOWANCE -> R.string.feature_parent_home_attention_dismiss_allowance
                            PaymentKind.INTEREST -> R.string.feature_parent_home_attention_dismiss_interest
                        },
                        item.kidName,
                        item.period,
                    )
                    SproutButton(
                        text = stringResource(R.string.feature_parent_home_attention_dismiss),
                        onClick = { onDismiss(item.key) },
                        style = SproutButtonStyle.Tertiary,
                        modifier = Modifier.semantics { contentDescription = dismissDescription },
                    )
                }
            }
        },
    )
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
private fun AnomaliesRow(state: ParentHomeUiState.Ready, onOpenKid: (KidId) -> Unit) {
    val total = state.anomaliesTotal
    val kid = state.firstKidWithAnomalies
    AttentionRow(
        lead = AttentionLead.Count(total, alert = true),
        title = stringResource(R.string.feature_parent_home_anomalies_title),
        detail = if (total == 0) {
            stringResource(R.string.feature_parent_home_anomalies_none)
        } else {
            pluralStringResource(R.plurals.feature_parent_home_anomalies_count, total, total)
        },
        action = kid?.let {
            { modifier ->
                ReviewButton(
                    text = stringResource(R.string.feature_parent_home_anomalies_review),
                    description = stringResource(R.string.feature_parent_home_anomalies_review_described),
                    onClick = { onOpenKid(it.id) },
                    modifier = modifier,
                )
            }
        },
    )
}

/**
 * A kid as one white card (one button to their wallet): the avatar ([tintIndex] by their place in the household),
 * "Leo, 9" over their tier, the money and screen-time tiles, the next allowance and any issues to review.
 */
@Composable
private fun KidCard(summary: KidSummaryUi, tintIndex: Int, onOpen: () -> Unit) {
    val formatter = rememberMoneyFormatter()
    val kid = summary.kid
    SproutCard(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(SproutSpacing.md)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(SproutSpacing.md),
            ) {
                InitialAvatar(
                    initial = kid.firstName.take(1).uppercase(),
                    tintIndex = tintIndex,
                    modifier = Modifier.clearAndSetSemantics {},
                )
                Column(modifier = Modifier.weight(1f)) {
                    val age = summary.age
                    Text(
                        if (age != null) {
                            stringResource(R.string.feature_parent_home_kid_name_age, kid.firstName, age)
                        } else {
                            kid.firstName
                        },
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        tierName(kid.uiTier),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    SproutIcons.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            BalanceTiles(summary, formatter)
            Column(verticalArrangement = Arrangement.spacedBy(SproutSpacing.xs)) {
                Text(
                    summary.nextAllowance?.let {
                        stringResource(R.string.feature_parent_home_kid_next_allowance, formatDate(it))
                    } ?: stringResource(R.string.feature_parent_home_kid_no_allowance),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (summary.anomalies > 0) {
                    Text(
                        pluralStringResource(
                            R.plurals.feature_parent_home_kid_anomalies,
                            summary.anomalies,
                            summary.anomalies,
                        ),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

/** The money and screen-time [StatTile]s side by side (stacked at large font sizes). */
@Composable
private fun BalanceTiles(summary: KidSummaryUi, formatter: MoneyFormatter) {
    val tiles = listOfNotNull(
        summary.money?.let {
            Triple(
                stringResource(R.string.feature_parent_home_kid_money),
                formatter.format(it.amount, it.currency),
                SproutIcons.Payments,
            )
        },
        summary.minutes?.let {
            Triple(
                stringResource(R.string.feature_parent_home_kid_minutes),
                formatter.format(it, Currency.Minutes),
                SproutIcons.Schedule,
            )
        },
    )
    if (tiles.isEmpty()) return
    if (LocalDensity.current.fontScale >= STACK_FONT_SCALE) {
        Column(verticalArrangement = Arrangement.spacedBy(SproutSpacing.sm)) {
            tiles.forEach { (label, value, icon) ->
                StatTile(label = label, value = value, icon = icon, modifier = Modifier.fillMaxWidth())
            }
        }
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(SproutSpacing.sm)) {
            tiles.forEach { (label, value, icon) ->
                StatTile(label = label, value = value, icon = icon, modifier = Modifier.weight(1f))
            }
        }
    }
}

/** "Shop (parent view)" and "Switch to kid mode" as two Linen pills in one row (stacked at large font sizes). */
@Composable
private fun ModeButtons(links: ParentHomeLinks) {
    val shop = stringResource(R.string.feature_parent_home_link_shop)
    val kidMode = stringResource(R.string.feature_parent_home_link_kid_mode)
    val style = SproutButtonStyle.Tertiary
    if (LocalDensity.current.fontScale >= STACK_FONT_SCALE) {
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(SproutSpacing.sm)) {
            SproutButton(shop, links.onOpenShop, Modifier.fillMaxWidth(), style = style)
            SproutButton(kidMode, links.onSwitchToKidMode, Modifier.fillMaxWidth(), style = style)
        }
    } else {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(SproutSpacing.sm)) {
            SproutButton(shop, links.onOpenShop, Modifier.weight(1f), style = style)
            SproutButton(kidMode, links.onSwitchToKidMode, Modifier.weight(1f), style = style)
        }
    }
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

package dev.kpetriv.sprout.feature.chores

import android.os.Bundle
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kpetriv.sprout.core.designsystem.components.SproutTopBar
import dev.kpetriv.sprout.core.designsystem.theme.ProvideKidTier
import dev.kpetriv.sprout.core.designsystem.theme.SproutSpacing
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.KidUiTier
import dev.kpetriv.sprout.core.ui.ReadAloud

/**
 * Navigation entry for [kidId]'s chores today, rendered for [tier]. "Add photo" opens the camera app through
 * [ActivityResultContracts.TakePicture][androidx.activity.result.contract.ActivityResultContracts.TakePicture] and
 * [ChorePhotoFileProvider].
 *
 * @param onBack the top bar's back arrow; null (the default) on the kid shell's Chores tab, which is a tab root on
 *   every tier (M9 §5.2).
 * @param readAloud speaks Seedling text; with [ReadAloud.None] the read-aloud buttons are hidden.
 */
@Composable
fun KidTodayRoute(
    kidId: KidId,
    tier: KidUiTier,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    readAloud: ReadAloud = ReadAloud.None,
) {
    val args = Bundle().apply {
        putString(KidTodayViewModel.KID_ID_KEY, kidId.value)
        putString(KidTodayViewModel.TIER_KEY, tier.name)
    }
    val viewModel = choresViewModel<KidTodayViewModel>(key = "chores:today:${kidId.value}:${tier.name}", args = args)
    val state by viewModel.state.collectAsStateWithLifecycle()
    val takePhoto = rememberChorePhotoCapture(
        onCaptured = { viewModel.onEvent(KidTodayEvent.PhotoCaptured(it)) },
        onFailed = { viewModel.onEvent(KidTodayEvent.PhotoFailed) },
    )
    KidTodayScreen(
        state = state,
        onEvent = viewModel::onEvent,
        onBack = onBack,
        onAddPhoto = takePhoto,
        readAloud = readAloud,
        modifier = modifier,
    )
}

/**
 * Today's chores per tier, the whole screen (bar included) inside [ProvideKidTier] once the tier is known: Seedling
 * picture cards ([SeedlingTodayContent]); Sprout and Sapling lists with due dates and rewards ([ListTodayContent]), a
 * submission sheet with note and photo, and for Sapling "Propose a chore". Colours come from the Morning garden
 * components and the tier's colour level (M9 §2.6).
 *
 * @param onBack the top bar's back arrow; null on the kid shell's Chores tab (a tab root, M9 §5.2).
 */
@Composable
fun KidTodayScreen(
    state: KidTodayUiState,
    onEvent: (KidTodayEvent) -> Unit,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    onAddPhoto: () -> Unit = {},
    readAloud: ReadAloud = ReadAloud.None,
) {
    val ready = state as? KidTodayUiState.Ready
    WithTier(ready?.tier) { KidTodayScaffold(state, onEvent, modifier, onBack, readAloud) }
    if (ready != null) KidTodaySheets(ready, onEvent, onAddPhoto)
}

@Composable
private fun WithTier(tier: KidUiTier?, content: @Composable () -> Unit) {
    if (tier == null) content() else ProvideKidTier(tier, content)
}

@Composable
private fun KidTodayScaffold(
    state: KidTodayUiState,
    onEvent: (KidTodayEvent) -> Unit,
    modifier: Modifier,
    onBack: (() -> Unit)?,
    readAloud: ReadAloud,
) {
    val snackbar = remember { SnackbarHostState() }
    val ready = state as? KidTodayUiState.Ready
    Scaffold(
        modifier = modifier,
        topBar = {
            val title = when {
                ready == null -> stringResource(R.string.feature_chores_title)

                ready.tier == KidUiTier.SEEDLING -> stringResource(
                    R.string.feature_chores_seedling_greeting,
                    ready.kidName,
                )

                else -> stringResource(R.string.feature_chores_today_title, ready.kidName)
            }
            SproutTopBar(title = title, onBack = onBack)
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            when (state) {
                KidTodayUiState.Loading -> LoadingContent()

                is KidTodayUiState.Error -> ErrorContent(
                    text = stringResource(R.string.feature_chores_error_load_failed),
                    onRetry = { onEvent(KidTodayEvent.Retry) },
                )

                is KidTodayUiState.Ready -> when (state.tier) {
                    KidUiTier.SEEDLING -> SeedlingTodayContent(state, onEvent, readAloud)
                    KidUiTier.SPROUT, KidUiTier.SAPLING -> ListTodayContent(state, onEvent)
                }
            }
        }
    }
    if (ready != null) {
        MessageEffect(snackbar, ready.message, ready.message?.let { kidMessage(it) }) {
            onEvent(KidTodayEvent.MessageShown)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KidTodaySheets(state: KidTodayUiState.Ready, onEvent: (KidTodayEvent) -> Unit, onAddPhoto: () -> Unit) {
    // Kid sheets have no drag handle: Material's is a 48 dp target in the sheet's own window, below the 56 and 72 dp
    // tier minimum (M7 §8). The whole sheet still drags, the scrim closes it, and the form has its own buttons.
    ProvideKidTier(state.tier) {
        val sheet = state.sheet
        if (sheet != null) {
            ModalBottomSheet(
                onDismissRequest = { onEvent(KidTodayEvent.DismissSubmit) },
                dragHandle = null,
                containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
            ) {
                SubmitForm(
                    sheet = sheet,
                    onEvent = onEvent,
                    onAddPhoto = onAddPhoto,
                    modifier = Modifier.padding(SproutSpacing.lg),
                )
            }
        }
        val propose = state.propose
        if (propose != null && state.canPropose) {
            ModalBottomSheet(
                onDismissRequest = { onEvent(KidTodayEvent.DismissPropose) },
                dragHandle = null,
                containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
            ) {
                ProposeForm(
                    form = propose,
                    currency = state.currency,
                    onEvent = onEvent,
                    modifier = Modifier.padding(SproutSpacing.lg),
                )
            }
        }
    }
}

@Composable
private fun kidMessage(message: KidTodayMessage): String = stringResource(
    when (message) {
        KidTodayMessage.Submitted -> R.string.feature_chores_submitted_message
        KidTodayMessage.SubmitFailed -> R.string.feature_chores_submit_failed
        KidTodayMessage.PhotoFailed -> R.string.feature_chores_photo_failed
        KidTodayMessage.Proposed -> R.string.feature_chores_proposed_message
        KidTodayMessage.ProposeFailed -> R.string.feature_chores_propose_failed
    },
)

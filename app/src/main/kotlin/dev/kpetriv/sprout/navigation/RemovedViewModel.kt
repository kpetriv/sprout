package dev.kpetriv.sprout.navigation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kpetriv.sprout.R
import dev.kpetriv.sprout.sync.AppRestarter
import dev.kpetriv.sprout.sync.Removal
import dev.kpetriv.sprout.sync.RemovalHandler
import dev.kpetriv.sprout.sync.RemovalReason
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** What the removed screen can ask for. */
sealed interface RemovedEvent {
    /** "Continue" once the data is erased: forget the removal and restart into onboarding. */
    data object Continue : RemovedEvent

    /** "Restart" after a wipe that could not erase everything: the next start erases again. */
    data object Restart : RemovedEvent
}

/** Where the wipe is. */
enum class RemovedProgress {
    WIPING,
    WIPED,

    /** Something could not be erased; the removal stays and the next start tries again (audit L4). */
    FAILED,
}

/**
 * The blocking removed screen (M3 §6). The wipe itself is [RemovalHandler]'s, started the moment the removal was
 * reported (from sync, a worker, the session check, the gate or sign-out); this only shows its progress and, once the
 * data is gone, "Continue", which restarts the app into onboarding; after a wipe that failed, "Restart" restarts the
 * app, whose start resumes the wipe.
 */
@HiltViewModel
class RemovedViewModel @Inject constructor(private val removals: RemovalHandler, private val restarter: AppRestarter) :
    ViewModel() {
    val state: StateFlow<RemovedProgress> = combine(removals.state, removals.failed, ::progressOf)
        .stateIn(viewModelScope, SharingStarted.Eagerly, progressOf(removals.state.value, removals.failed.value))

    fun onEvent(event: RemovedEvent) {
        when (event) {
            RemovedEvent.Continue -> if (state.value == RemovedProgress.WIPED) {
                removals.acknowledge()
                restarter.restart()
            }

            RemovedEvent.Restart -> if (state.value == RemovedProgress.FAILED) restarter.restart()
        }
    }

    private fun progressOf(removal: Removal?, failed: Boolean): RemovedProgress = when {
        removal?.wiped == true -> RemovedProgress.WIPED
        failed -> RemovedProgress.FAILED
        else -> RemovedProgress.WIPING
    }
}

@Composable
internal fun RemovedRoute(reason: RemovalReason, modifier: Modifier = Modifier) {
    val viewModel: RemovedViewModel = hiltViewModel()
    val progress by viewModel.state.collectAsStateWithLifecycle()
    RemovedScreen(
        reason = reason,
        progress = progress,
        onEvent = viewModel::onEvent,
        modifier = modifier,
    )
}

@Composable
internal fun RemovedScreen(
    reason: RemovalReason,
    progress: RemovedProgress,
    onEvent: (RemovedEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.padding(24.dp).semantics { liveRegion = LiveRegionMode.Polite },
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                stringResource(
                    if (reason == RemovalReason.SIGNED_OUT) {
                        R.string.removed_signed_out_title
                    } else {
                        R.string.removed_title
                    },
                ),
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
            )
            Text(
                stringResource(
                    when (reason) {
                        RemovalReason.HOUSEHOLD_GONE -> R.string.removed_household_gone
                        RemovalReason.DEVICE_REVOKED -> R.string.removed_device_revoked
                        RemovalReason.SIGNED_OUT -> R.string.removed_signed_out
                    },
                ),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
            )
            when (progress) {
                RemovedProgress.WIPED -> {
                    Text(stringResource(R.string.removed_wiped), textAlign = TextAlign.Center)
                    Button(onClick = { onEvent(RemovedEvent.Continue) }) {
                        Text(stringResource(R.string.removed_continue))
                    }
                }

                RemovedProgress.FAILED -> {
                    Text(
                        stringResource(R.string.removed_failed),
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                    )
                    Button(onClick = { onEvent(RemovedEvent.Restart) }) {
                        Text(stringResource(R.string.removed_restart))
                    }
                }

                RemovedProgress.WIPING -> {
                    CircularProgressIndicator()
                    Text(stringResource(R.string.removed_wiping), textAlign = TextAlign.Center)
                }
            }
        }
    }
}

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
import dev.kpetriv.sprout.sync.RemovalHandler
import dev.kpetriv.sprout.sync.RemovalReason
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** What the removed screen can ask for. */
sealed interface RemovedEvent {
    /** "Continue" once the data is erased: forget the removal and restart into onboarding. */
    data object Continue : RemovedEvent
}

/**
 * The blocking removed screen (M3 §6). The wipe itself is [RemovalHandler]'s, started the moment the removal was
 * reported (from sync, a worker, the session check, the gate or sign-out); this only shows its progress and, once the
 * data is gone, "Continue", which restarts the app into onboarding.
 */
@HiltViewModel
class RemovedViewModel @Inject constructor(private val removals: RemovalHandler, private val restarter: AppRestarter) :
    ViewModel() {
    /** True once the data is gone. */
    val state: StateFlow<Boolean> = removals.state.map { it?.wiped == true }
        .stateIn(viewModelScope, SharingStarted.Eagerly, removals.state.value?.wiped == true)

    fun onEvent(event: RemovedEvent) {
        when (event) {
            RemovedEvent.Continue -> if (state.value) {
                removals.acknowledge()
                restarter.restart()
            }
        }
    }
}

@Composable
internal fun RemovedRoute(reason: RemovalReason, modifier: Modifier = Modifier) {
    val viewModel: RemovedViewModel = hiltViewModel()
    val erased by viewModel.state.collectAsStateWithLifecycle()
    RemovedScreen(
        reason = reason,
        erased = erased,
        onContinue = { viewModel.onEvent(RemovedEvent.Continue) },
        modifier = modifier,
    )
}

@Composable
internal fun RemovedScreen(
    reason: RemovalReason,
    erased: Boolean,
    onContinue: () -> Unit,
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
            if (erased) {
                Text(stringResource(R.string.removed_wiped), textAlign = TextAlign.Center)
                Button(onClick = onContinue) { Text(stringResource(R.string.removed_continue)) }
            } else {
                CircularProgressIndicator()
                Text(stringResource(R.string.removed_wiping), textAlign = TextAlign.Center)
            }
        }
    }
}

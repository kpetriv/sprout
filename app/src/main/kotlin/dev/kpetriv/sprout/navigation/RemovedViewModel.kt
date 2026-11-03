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
import dev.kpetriv.sprout.sync.LocalDataEraser
import dev.kpetriv.sprout.sync.RemovalReason
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The blocking "This phone was removed from the household" screen (M3 §6). The household's data is erased as soon as
 * the screen opens (a revoked phone must not keep it until someone taps); "Continue" restarts the app, which lands on
 * onboarding.
 */
@HiltViewModel
class RemovedViewModel @Inject constructor(private val eraser: LocalDataEraser, private val restarter: AppRestarter) :
    ViewModel() {
    private val erased = MutableStateFlow(false)

    /** True once the data is gone. */
    val state: StateFlow<Boolean> = erased.asStateFlow()

    init {
        viewModelScope.launch {
            try {
                eraser.erase()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") failure: Exception) {
                // Erasing is best effort per file; the restart opens onboarding either way. Nothing to log.
            }
            erased.value = true
        }
    }

    fun onContinue() {
        if (erased.value) restarter.restart()
    }
}

@Composable
internal fun RemovedRoute(reason: RemovalReason, modifier: Modifier = Modifier) {
    val viewModel: RemovedViewModel = hiltViewModel()
    val erased by viewModel.state.collectAsStateWithLifecycle()
    RemovedScreen(reason = reason, erased = erased, onContinue = viewModel::onContinue, modifier = modifier)
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
                stringResource(R.string.removed_title),
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
            )
            Text(
                stringResource(
                    when (reason) {
                        RemovalReason.HOUSEHOLD_GONE -> R.string.removed_household_gone
                        RemovalReason.DEVICE_REVOKED -> R.string.removed_device_revoked
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

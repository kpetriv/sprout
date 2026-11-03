package dev.kpetriv.sprout.feature.lock

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Shows [content] only while the parent session is unlocked: [PinEntryRoute] while locked, [PinSetupRoute] on
 * first run. Wrap every parent-mode screen (or the whole parent nav graph) in it. The window is `FLAG_SECURE`
 * for as long as the gate is shown, content included, so parent screens stay out of screenshots and recents.
 *
 * This is the app-level gate only; money events additionally need `ParentAuthorizationRequester`.
 */
@Composable
fun RequireParent(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val viewModel: ParentGateViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    RequireParentContent(
        session = state.session,
        modifier = modifier,
        locked = { PinEntryRoute() },
        setup = { PinSetupRoute() },
        content = content,
    )
}

/** The stateless part of [RequireParent]. */
@Composable
internal fun RequireParentContent(
    session: ParentSessionState,
    modifier: Modifier = Modifier,
    locked: @Composable () -> Unit,
    setup: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    SecureWindow()
    Box(modifier = modifier) {
        when (session) {
            ParentSessionState.Initializing -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }

            ParentSessionState.SetupRequired -> setup()

            ParentSessionState.Locked -> locked()

            is ParentSessionState.Unlocked -> content()
        }
    }
}

package dev.kpetriv.sprout.feature.lock

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kpetriv.sprout.core.crypto.ParentAuthAvailability
import dev.kpetriv.sprout.core.designsystem.theme.SproutTheme

/** [ParentSignaturePolicyBanner] for this device, re-checked whenever the screen resumes. */
@Composable
fun ParentSignaturePolicyBanner(modifier: Modifier = Modifier) {
    val viewModel: ParentGateViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    LifecycleResumeEffect(viewModel) {
        viewModel.onEvent(ParentGateEvent.RefreshAvailability)
        onPauseOrDispose {}
    }
    ParentSignaturePolicyBanner(availability = state.parentAuthAvailability, modifier = modifier)
}

/**
 * Warns that money events from this device go into the ledger without a parent signature (D-M2-1), because no
 * strong biometric is usable. Shows nothing when [availability] is
 * [ParentAuthAvailability.STRONG_BIOMETRIC_AVAILABLE].
 */
@Composable
fun ParentSignaturePolicyBanner(availability: ParentAuthAvailability, modifier: Modifier = Modifier) {
    if (availability == ParentAuthAvailability.STRONG_BIOMETRIC_AVAILABLE) return
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = stringResource(R.string.feature_lock_signature_banner_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            Text(text = stringResource(availability.detailRes()), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

private fun ParentAuthAvailability.detailRes(): Int = when (this) {
    ParentAuthAvailability.NOT_ENROLLED -> R.string.feature_lock_signature_banner_not_enrolled

    ParentAuthAvailability.NO_HARDWARE -> R.string.feature_lock_signature_banner_no_hardware

    ParentAuthAvailability.STRONG_BIOMETRIC_AVAILABLE,
    ParentAuthAvailability.UNAVAILABLE_OTHER,
    -> R.string.feature_lock_signature_banner_unavailable
}

@Preview
@Composable
private fun ParentSignaturePolicyBannerPreview() {
    SproutTheme {
        ParentSignaturePolicyBanner(availability = ParentAuthAvailability.NOT_ENROLLED)
    }
}

package dev.kpetriv.sprout.feature.wallet

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.kpetriv.sprout.core.ui.SproutPlaceholder

/** Entry point of the wallet feature. Placeholder until its milestone lands. */
@Composable
fun WalletScreen(modifier: Modifier = Modifier) {
    SproutPlaceholder(
        title = stringResource(R.string.feature_wallet_title),
        modifier = modifier,
    )
}

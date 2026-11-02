package dev.kpetriv.sprout.feature.chat

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.kpetriv.sprout.core.ui.SproutPlaceholder

/** Entry point of the chat feature. Placeholder until its milestone lands. */
@Composable
fun ChatScreen(modifier: Modifier = Modifier) {
    SproutPlaceholder(
        title = stringResource(R.string.feature_chat_title),
        modifier = modifier,
    )
}

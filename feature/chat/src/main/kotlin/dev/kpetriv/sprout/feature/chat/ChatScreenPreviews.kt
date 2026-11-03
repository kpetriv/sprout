package dev.kpetriv.sprout.feature.chat

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import dev.kpetriv.sprout.core.designsystem.theme.SproutTheme
import dev.kpetriv.sprout.core.model.KidUiTier

@Preview(name = "Parent inbox", showBackground = true)
@Composable
private fun ThreadsPreview() = SproutTheme {
    ChatThreadListScreen(ChatPreviewData.threads(), onEvent = {}, onOpenThread = {}, onBack = {})
}

@Preview(name = "Parent thread", showBackground = true, heightDp = 900)
@Composable
private fun ParentThreadPreview() = SproutTheme { ChatScreen(state = ChatPreviewData.parentThread(), onEvent = {}) }

@Preview(name = "Seedling", showBackground = true, heightDp = 900)
@Composable
private fun SeedlingPreview() = SproutTheme {
    ChatScreen(state = ChatPreviewData.kidMessages(KidUiTier.SEEDLING), onEvent = {})
}

@Preview(name = "Sprout nudge", showBackground = true, heightDp = 900)
@Composable
private fun SproutNudgePreview() = SproutTheme {
    ChatScreen(state = ChatPreviewData.kidNudge(KidUiTier.SPROUT), onEvent = {})
}

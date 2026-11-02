package dev.kpetriv.sprout.core.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import dev.kpetriv.sprout.core.designsystem.theme.SproutTheme

/** A centred title, used by screens that have not been built yet. */
@Composable
fun SproutPlaceholder(title: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text = title, style = MaterialTheme.typography.headlineMedium)
    }
}

@Preview(showBackground = true)
@Composable
private fun SproutPlaceholderPreview() {
    SproutTheme { SproutPlaceholder(title = "Sprout") }
}

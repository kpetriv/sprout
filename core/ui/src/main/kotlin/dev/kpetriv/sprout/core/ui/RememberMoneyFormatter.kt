package dev.kpetriv.sprout.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources

/** A [MoneyFormatter] for the current locale with localized unit words; rebuilt when the locale changes. */
@Composable
fun rememberMoneyFormatter(): MoneyFormatter {
    val locale = LocalConfiguration.current.locales[0]
    val resources = LocalResources.current
    return remember(locale, resources) { MoneyFormatter(locale, MoneyLabels.fromResources(resources)) }
}

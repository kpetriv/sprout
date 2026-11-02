package dev.kpetriv.sprout.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import dev.kpetriv.sprout.feature.wallet.WalletMode
import dev.kpetriv.sprout.feature.wallet.WalletRoute
import dev.kpetriv.sprout.home.HomeKey
import dev.kpetriv.sprout.home.HomeRoute

/**
 * The app's navigation host (Navigation 3): a saveable back stack of [NavKey]s.
 *
 * Entry decorators, in order: saveable state (each entry keeps its `rememberSaveable` state while on the stack),
 * then a ViewModel store per entry, so every entry owns its ViewModels and they are cleared when it is popped.
 *
 * Parent entries render directly under SproutTheme. Kid-mode entries (from M2) wrap their content in
 * `ProvideKidTier(kid.tier)`, so only kid screens can read the tier.
 */
@Composable
fun SproutNavDisplay(modifier: Modifier = Modifier) {
    val backStack = rememberNavBackStack(HomeKey)
    NavDisplay(
        backStack = backStack,
        modifier = modifier,
        onBack = { backStack.removeLastOrNull() },
        entryDecorators = listOf(
            rememberSaveableStateHolderNavEntryDecorator(),
            rememberViewModelStoreNavEntryDecorator(),
        ),
        entryProvider = entryProvider {
            entry<HomeKey> {
                HomeRoute(onKidClick = { kidId -> backStack.add(WalletKey(kidId)) })
            }
            entry<WalletKey> { key ->
                WalletRoute(
                    kidId = key.kidId,
                    // TODO(M2): pass WalletMode.KID in kid mode once feature/lock gates the parent role.
                    mode = WalletMode.PARENT,
                    onBack = { backStack.removeLastOrNull() },
                )
            }
        },
    )
}

package dev.kpetriv.sprout.feature.wallet

import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.hilt.lifecycle.viewmodel.compose.rememberHiltViewModelFactory
import androidx.lifecycle.DEFAULT_ARGS_KEY
import androidx.lifecycle.HasDefaultViewModelProviderFactory
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.MutableCreationExtras
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * A Hilt ViewModel scoped to the current `ViewModelStoreOwner` (the nav entry) under [key], with [args] as its
 * `SavedStateHandle` arguments. Used by [WalletRoute] and [ShopRoute].
 */
@Composable
internal inline fun <reified VM : ViewModel> featureViewModel(key: String, args: Bundle): VM {
    val owner = checkNotNull(LocalViewModelStoreOwner.current) { "A wallet route needs a ViewModelStoreOwner" }
    val defaults = (owner as? HasDefaultViewModelProviderFactory)?.defaultViewModelCreationExtras
        ?: CreationExtras.Empty
    val extras = MutableCreationExtras(defaults).apply { set(DEFAULT_ARGS_KEY, args) }
    return viewModel(
        viewModelStoreOwner = owner,
        key = key,
        // Hilt factory over the owner's default factory, as hiltViewModel() does; extras carry the arguments.
        factory = rememberHiltViewModelFactory(),
        extras = extras,
    )
}

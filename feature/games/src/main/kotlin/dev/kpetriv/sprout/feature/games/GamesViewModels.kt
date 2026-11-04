package dev.kpetriv.sprout.feature.games

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
import kotlinx.coroutines.CancellationException

/**
 * A Hilt ViewModel of type [VM] scoped to the current `ViewModelStoreOwner` (the nav entry) under [key], with
 * [args] in its `SavedStateHandle`.
 */
@Composable
internal inline fun <reified VM : ViewModel> gamesViewModel(key: String, args: Bundle = Bundle()): VM {
    val owner = checkNotNull(LocalViewModelStoreOwner.current) { "Games routes need a ViewModelStoreOwner" }
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

/** Runs [block]; any failure other than cancellation becomes a failed [Result] (the caller shows a retry). */
@Suppress("TooGenericExceptionCaught") // Any failure must surface as a retry, not crash the app.
internal suspend fun <T> attempt(block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Result.failure(e)
}

internal const val STOP_TIMEOUT_MILLIS: Long = 5_000L

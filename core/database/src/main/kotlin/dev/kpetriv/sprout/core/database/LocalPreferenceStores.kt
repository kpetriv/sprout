package dev.kpetriv.sprout.core.database

import kotlinx.coroutines.CancellationException
import javax.inject.Inject

/**
 * A small preference store kept outside the database that holds household data, such as the kids' Times tables setup
 * (keyed by kid id, M9-games §6.3). Its file is in [LocalDataFiles.preferenceFiles]; the wipe, the trial purge and
 * the in-place clear of an account switch call [clear] first, through the store's own writer, so a write already
 * queued is ordered before the clear and cannot bring the data back after the files are deleted.
 */
fun interface LocalPreferenceStore {
    /** Removes every value. */
    suspend fun clear()
}

/**
 * Every [LocalPreferenceStore] of the app: a Hilt `Set` multibinding the app contributes to (`@IntoSet`), empty
 * unless it does.
 */
class LocalPreferenceStores @Inject constructor(private val stores: Set<@JvmSuppressWildcards LocalPreferenceStore>) {
    /**
     * Clears every store, best effort: one that fails does not stop the others, and the caller deletes the files
     * anyway. True when every store cleared.
     */
    suspend fun clearAll(): Boolean = stores.map { store -> attempt { store.clear() } }.all { it }

    @Suppress("TooGenericExceptionCaught", "SwallowedException") // Dropped unlogged: the files are deleted after.
    private suspend fun attempt(step: suspend () -> Unit): Boolean = try {
        step()
        true
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: Exception) {
        false
    }

    companion object {
        /** No stores: for callers and tests with nothing to clear. */
        val None: LocalPreferenceStores = LocalPreferenceStores(emptySet())
    }
}

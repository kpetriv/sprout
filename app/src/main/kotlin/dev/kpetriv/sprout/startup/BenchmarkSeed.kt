package dev.kpetriv.sprout.startup

import dev.kpetriv.sprout.BuildConfig
import dev.kpetriv.sprout.core.crypto.ParentAuthAvailability
import dev.kpetriv.sprout.core.crypto.ParentAuthUnavailableException
import dev.kpetriv.sprout.core.database.HouseholdBootstrap
import dev.kpetriv.sprout.core.database.KidRepository
import dev.kpetriv.sprout.core.database.usecase.RecordManualAdjustmentUseCase
import dev.kpetriv.sprout.core.domainledger.Money
import dev.kpetriv.sprout.core.model.KidUiTier
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/**
 * The startup seeding of the benchmark build types (`nonMinifiedRelease` generates the Baseline Profile,
 * `benchmarkRelease` is measured; D-M7-3, D-M7-41). They are release code under R8 with `BuildConfig.BENCHMARK` set,
 * and run offline:
 * - the demo trial household is seeded as in debug builds ([HouseholdBootstrap]), so the journeys in `benchmark/`
 *   have kids, chores and a shop; the parent PIN is chosen by the journey on the first start;
 * - right after that first seeding, the Sapling kid (Zoe) gets [HISTORY] small pocket-money credits, so her wallet
 *   history is a list long enough to scroll (the frame timing benchmark). They are ordinary unsigned
 *   `ManualAdjustment`s: no parent key exists yet, so nobody is prompted ([RecordManualAdjustmentUseCase]);
 * - Firebase is never initialised ([usesFirebase]), so there is no App Check provider and no network. The trial
 *   household never syncs anyway, every Firebase entry point initialises lazily, and the functions URL is a
 *   build-time placeholder that passes `FirebaseModule.requireFunctionsBaseUrl` unused.
 *
 * Debug and release builds never construct it: [enabled] is false there and [AppInitializer] keeps its debug-only
 * demo seeding.
 */
class BenchmarkSeed @Inject constructor(
    private val bootstrap: HouseholdBootstrap,
    private val kids: KidRepository,
    private val adjustments: RecordManualAdjustmentUseCase,
) {
    /** Seeds the demo household and, the first time only, the wallet history. */
    suspend fun seed() {
        if (!bootstrap.ensureLocalHousehold()) return
        val sapling = kids.observeKids().first().firstOrNull { it.uiTier == KidUiTier.SAPLING } ?: return
        repeat(HISTORY) { index ->
            adjustments(sapling.id, Money(CREDIT + index % CREDIT_SPREAD), REASON) {
                // Never reached without a registered parent key; refuse rather than prompt if it ever is.
                throw ParentAuthUnavailableException(ParentAuthAvailability.UNAVAILABLE_OTHER)
            }
        }
    }

    companion object {
        /** True only in the benchmark build types. */
        val enabled: Boolean get() = BuildConfig.BENCHMARK

        /** Whether startup initialises Firebase and installs App Check: everywhere except the benchmark builds. */
        val usesFirebase: Boolean get() = !enabled

        /** Wallet history rows for the Sapling kid: several screens' worth, well under the tier's list limit. */
        private const val HISTORY = 60
        private const val CREDIT = 25L
        private const val CREDIT_SPREAD = 4
        private const val REASON = "Pocket money"
    }
}

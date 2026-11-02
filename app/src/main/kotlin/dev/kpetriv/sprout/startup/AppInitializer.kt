package dev.kpetriv.sprout.startup

import android.database.SQLException
import dagger.Lazy
import dev.kpetriv.sprout.BuildConfig
import dev.kpetriv.sprout.core.database.HouseholdBootstrap
import dev.kpetriv.sprout.core.database.RoomLedgerRepository
import dev.kpetriv.sprout.di.ApplicationScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.security.GeneralSecurityException
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One-time, process-start work, run off the main thread on the [ApplicationScope] (IO):
 * 1. debug builds only: seed the demo household ([HouseholdBootstrap], TEMPORARY until onboarding in M3; release
 *    builds never create demo kids);
 * 2. ledger maintenance: quarantine stored events whose hash or signature no longer verifies
 *    ([RoomLedgerRepository.quarantineUnverifiable]).
 *
 * Dependencies are injected lazily so even building their graph (database, Keystore-backed signer) happens on the IO
 * thread, never in `Application.onCreate`. Failures other than cancellation are caught and published as
 * [StartupState.Failed] (never logged: nothing here may carry personal data); the home screen shows them with a
 * retry button that calls [retry].
 *
 * @param seedDemoHousehold whether to run the demo seeding; `BuildConfig.DEBUG` in the app.
 */
@Singleton
class AppInitializer internal constructor(
    private val scope: CoroutineScope,
    private val householdBootstrap: Lazy<HouseholdBootstrap>,
    private val ledger: Lazy<RoomLedgerRepository>,
    private val seedDemoHousehold: Boolean,
) : StartupStatus {
    @Inject
    constructor(
        @ApplicationScope scope: CoroutineScope,
        householdBootstrap: Lazy<HouseholdBootstrap>,
        ledger: Lazy<RoomLedgerRepository>,
    ) : this(scope, householdBootstrap, ledger, BuildConfig.DEBUG)

    private val started = AtomicBoolean(false)
    private val running = AtomicBoolean(false)
    private val mutableState = MutableStateFlow<StartupState>(StartupState.Running)

    override val state: StateFlow<StartupState> = mutableState.asStateFlow()

    /** Starts the work once per process; later calls do nothing. Returns immediately. */
    fun start() {
        if (started.compareAndSet(false, true)) launchWork()
    }

    override fun retry() {
        if (started.get() && mutableState.value is StartupState.Failed) launchWork()
    }

    private fun launchWork() {
        if (!running.compareAndSet(false, true)) return
        mutableState.value = StartupState.Running
        scope.launch {
            val outcome = try {
                runWork()
            } finally {
                running.set(false)
            }
            mutableState.value = outcome
        }
    }

    @Suppress("TooGenericExceptionCaught") // Any failure must reach the UI as a state instead of crashing the process.
    private suspend fun runWork(): StartupState = try {
        if (seedDemoHousehold) householdBootstrap.get().ensureLocalHousehold()
        ledger.get().quarantineUnverifiable()
        StartupState.Ready
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        StartupState.Failed(failureKind(e))
    }

    private fun failureKind(error: Throwable): StartupFailure {
        val chain = generateSequence(error) { it.cause }.take(MAX_CAUSE_DEPTH).toList()
        return when {
            chain.any { it is GeneralSecurityException } -> StartupFailure.KEYS

            // SQLiteException (platform and SQLCipher) extends SQLException.
            chain.any { it is SQLException } -> StartupFailure.DATABASE

            else -> StartupFailure.UNKNOWN
        }
    }

    private companion object {
        const val MAX_CAUSE_DEPTH = 8
    }
}

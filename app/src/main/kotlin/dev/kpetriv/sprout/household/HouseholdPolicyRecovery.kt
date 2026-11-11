package dev.kpetriv.sprout.household

import dev.kpetriv.sprout.core.database.HouseholdPolicyRepository
import dev.kpetriv.sprout.core.database.Session
import dev.kpetriv.sprout.core.database.SessionStore
import dev.kpetriv.sprout.core.database.entity.RegistrationStatus
import dev.kpetriv.sprout.core.domainledger.Currency
import dev.kpetriv.sprout.core.model.HouseholdId
import dev.kpetriv.sprout.core.model.Role
import dev.kpetriv.sprout.core.sync.HouseholdRoot
import dev.kpetriv.sprout.core.sync.SyncReport
import dev.kpetriv.sprout.core.sync.SyncTransport
import dev.kpetriv.sprout.sync.SyncController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Whether a parent key can be registered as far as the household policy goes ([HouseholdPolicyRecovery]). */
sealed interface PolicyReadiness {
    /** This phone has the policy row (or is the trial household). */
    data object Present : PolicyReadiness

    /** The household has no policy anywhere: registration recovers it with the server's [currency] (D-REL-14). */
    data class Recoverable(val currency: Currency.Fiat) : PolicyReadiness

    /** The policy is somewhere this phone has not applied yet (or it cannot tell): wait for it to sync. */
    data object NotSynced : PolicyReadiness
}

/**
 * Recovery of a real household's missing `household_policy` (D-REL-14, review M, audit M1). `createHousehold` stores
 * no policy on the server; only the creating phone's first write does, so a creating phone lost before its outbox
 * pushed leaves a household whose policy no phone can ever create (enrolment refused for good, signatures off).
 *
 * [readiness] decides, for this phone's ACTIVE parent session of a real household without a policy row:
 * 1. one sync ([SyncController.syncNow]) must complete without an error, and still bring no policy row;
 * 2. no pulled `household_policy` may be held in `document_inbox` ([HouseholdPolicyRepository.policyHeld]);
 * 3. one read of the server ([SyncTransport.householdRoot]) must find no stored policy document, and a supported
 *    currency on the household root, written there by `createHousehold` (never this phone's locale).
 * Then it is [PolicyReadiness.Recoverable], and [takeRecovered] hands that currency, once, to the one write that uses
 * it: the key registration, which creates the policy with it and registers the key in the same transaction
 * ([HouseholdPolicyRepository.registerParentKey]), or [restoreWithoutKey] (D-REL-22), which creates it with signatures
 * off as a creating phone without a strong biometric does. Anything else is [PolicyReadiness.NotSynced]; nothing is
 * written by [readiness] itself.
 *
 * [readiness] forgets any earlier answer first (audit N1), so a failed, timed-out or cancelled check never leaves a
 * currency behind, and it gives up after [timeout] (audit L3), answering [PolicyReadiness.NotSynced]: it runs before a
 * fingerprint prompt or a PIN dialog, with the key provisioner's lock held.
 */
@Singleton
class HouseholdPolicyRecovery internal constructor(
    private val sessions: SessionStore,
    private val policies: HouseholdPolicyRepository,
    private val syncNow: suspend () -> SyncReport?,
    private val householdRoot: suspend () -> HouseholdRoot,
    private val timeout: Duration = READINESS_TIMEOUT,
) {
    @Inject
    constructor(
        sessions: SessionStore,
        policies: HouseholdPolicyRepository,
        sync: SyncController,
        transport: SyncTransport,
    ) : this(sessions, policies, sync::syncNow, transport::householdRoot)

    @Volatile
    private var recovered: Pair<HouseholdId, Currency.Fiat>? = null

    suspend fun readiness(): PolicyReadiness {
        synchronized(this) { recovered = null }
        return withTimeoutOrNull(timeout) { check() } ?: PolicyReadiness.NotSynced
    }

    @Suppress("ReturnCount") // One return per condition, in the order of the class comment.
    private suspend fun check(): PolicyReadiness {
        val session = sessions.get() ?: return PolicyReadiness.NotSynced
        if (session.isLocalTrial || policies.get() != null) return PolicyReadiness.Present
        if (!session.isActiveParent()) return PolicyReadiness.NotSynced
        val report = syncNow()
        if (report == null || report.error != null) return PolicyReadiness.NotSynced
        if (policies.get() != null) return PolicyReadiness.Present
        if (policies.policyHeld()) return PolicyReadiness.NotSynced
        val root = attemptOrNull { householdRoot() } ?: return PolicyReadiness.NotSynced
        if (root.policyStored) return PolicyReadiness.NotSynced
        val currency = root.fiatCurrency?.let { code -> runCatching { Currency.Fiat(code) }.getOrNull() }
            ?: return PolicyReadiness.NotSynced
        // Not kept when the timeout cancels the check here: withTimeoutOrNull discards the answer.
        currentCoroutineContext().ensureActive()
        synchronized(this) { recovered = session.householdId to currency }
        return PolicyReadiness.Recoverable(currency)
    }

    /** The currency [readiness] last found recoverable for [householdId], handed out once; null otherwise. */
    fun takeRecovered(householdId: HouseholdId): Currency.Fiat? = synchronized(this) {
        val found = recovered?.takeIf { it.first == householdId }?.second
        recovered = null
        found
    }

    /**
     * "Restore household settings" (D-REL-22, audit M1), after [readiness] answered [PolicyReadiness.Recoverable]
     * and the parent confirmed with the PIN: creates the policy with that currency and signatures off
     * ([HouseholdPolicyRepository.ensure]), exactly what a creating phone without a strong biometric writes, so a
     * household whose other parent phones have no strong biometric is not stuck without a policy. No parent key is
     * needed; fingerprint signing can be turned on later on a phone that has one. Rechecks this phone's ACTIVE parent
     * session and that no pulled policy is held. True when the policy exists afterwards.
     */
    @Suppress("ReturnCount") // One return per recheck.
    suspend fun restoreWithoutKey(): Boolean {
        val session = sessions.get() ?: return false
        if (session.isLocalTrial || !session.isActiveParent()) return false
        val currency = takeRecovered(session.householdId) ?: return policies.get() != null
        if (policies.get() == null && policies.policyHeld()) return false
        policies.ensure(currency)
        return true
    }

    private fun Session.isActiveParent(): Boolean = role == Role.PARENT && deviceStatus == RegistrationStatus.ACTIVE

    @Suppress("TooGenericExceptionCaught", "SwallowedException") // A failed read is "cannot tell": never recovered.
    private suspend fun <T> attemptOrNull(block: suspend () -> T): T? = try {
        block()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: Exception) {
        null
    }
}

/**
 * How long [HouseholdPolicyRecovery.readiness] may take (one sync and two reads) before it answers "not synced"
 * (audit L3).
 */
internal val READINESS_TIMEOUT: Duration = 20.seconds

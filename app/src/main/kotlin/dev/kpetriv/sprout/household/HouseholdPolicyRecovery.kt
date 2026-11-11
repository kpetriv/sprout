package dev.kpetriv.sprout.household

import dev.kpetriv.sprout.core.database.HouseholdPolicyRepository
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
import javax.inject.Inject
import javax.inject.Singleton

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
 * Then it is [PolicyReadiness.Recoverable], and [takeRecovered] hands that currency, once, to the registration, which
 * creates the policy with it and registers the key in the same transaction
 * ([HouseholdPolicyRepository.registerParentKey]). Anything else is [PolicyReadiness.NotSynced]; nothing is written
 * here.
 */
@Singleton
class HouseholdPolicyRecovery internal constructor(
    private val sessions: SessionStore,
    private val policies: HouseholdPolicyRepository,
    private val syncNow: suspend () -> SyncReport?,
    private val householdRoot: suspend () -> HouseholdRoot,
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

    @Suppress("ReturnCount") // One return per condition, in the order above.
    suspend fun readiness(): PolicyReadiness {
        val session = sessions.get() ?: return PolicyReadiness.NotSynced
        if (session.isLocalTrial || policies.get() != null) return PolicyReadiness.Present
        if (session.role != Role.PARENT || session.deviceStatus != RegistrationStatus.ACTIVE) {
            return PolicyReadiness.NotSynced
        }
        val report = syncNow()
        if (report == null || report.error != null) return PolicyReadiness.NotSynced
        if (policies.get() != null) return PolicyReadiness.Present
        if (policies.policyHeld()) return PolicyReadiness.NotSynced
        val root = attemptOrNull { householdRoot() } ?: return PolicyReadiness.NotSynced
        if (root.policyStored) return PolicyReadiness.NotSynced
        val currency = root.fiatCurrency?.let { code -> runCatching { Currency.Fiat(code) }.getOrNull() }
            ?: return PolicyReadiness.NotSynced
        recovered = session.householdId to currency
        return PolicyReadiness.Recoverable(currency)
    }

    /** The currency [readiness] last found recoverable for [householdId], handed out once; null otherwise. */
    fun takeRecovered(householdId: HouseholdId): Currency.Fiat? = synchronized(this) {
        val found = recovered?.takeIf { it.first == householdId }?.second
        recovered = null
        found
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException") // A failed read is "cannot tell": never recovered.
    private suspend fun <T> attemptOrNull(block: suspend () -> T): T? = try {
        block()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: Exception) {
        null
    }
}

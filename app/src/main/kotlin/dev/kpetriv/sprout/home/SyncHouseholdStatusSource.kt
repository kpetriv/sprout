package dev.kpetriv.sprout.home

import dev.kpetriv.sprout.core.database.HouseholdPolicy
import dev.kpetriv.sprout.core.database.HouseholdPolicyRepository
import dev.kpetriv.sprout.core.database.KidRepository
import dev.kpetriv.sprout.core.database.RoomLedgerRepository
import dev.kpetriv.sprout.core.database.RoomOutboxSource
import dev.kpetriv.sprout.core.database.SessionStore
import dev.kpetriv.sprout.core.database.SproutDatabase
import dev.kpetriv.sprout.core.database.entity.RegistrationStatus
import dev.kpetriv.sprout.core.model.DeviceId
import dev.kpetriv.sprout.core.model.Role
import dev.kpetriv.sprout.feature.lock.ParentKeyChangeProgress
import dev.kpetriv.sprout.feature.parenthome.DeviceLabel
import dev.kpetriv.sprout.feature.parenthome.HouseholdStatus
import dev.kpetriv.sprout.feature.parenthome.HouseholdStatusSource
import dev.kpetriv.sprout.feature.parenthome.PendingDevice
import dev.kpetriv.sprout.feature.parenthome.RejectedEvent
import dev.kpetriv.sprout.feature.parenthome.SyncLine
import dev.kpetriv.sprout.sync.SyncController
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.transformLatest
import javax.inject.Inject

/**
 * The dashboard's household cards (M3 §6) over the session (trial or not), [SyncController.status], the event that
 * blocks the outbox ([RoomOutboxSource.observeBlocked]), the quarantined
 * events (`observeQuarantined`, ids and reasons only), the registry mirror (labels and PENDING parent phones) and
 * whether this phone's parent key still lacks its server registration
 * ([HouseholdPolicyRepository.observeServerRegistrationPending], D-M3-39). Sync never retries that registration (the
 * server needs a fresh attestation challenge and the parent's fingerprint); the card stays until the parent retries.
 * "Fingerprint signing is off" shows while the household policy has no parent key for this parent phone (D-M3-46).
 * Neither signing card shows while a parent key change runs ([ParentKeyChangeProgress], D-REL-12): right after the PIN
 * setup the enrolment's fingerprint prompt and its server registration run over the dashboard, and the policy passes
 * through "no key" and "key not registered yet" on the way; only an enrolment that ended leaves a state worth a card.
 * The end of a key change is held back by [KEY_CHANGE_SETTLE_MS] so Room's re-query of its last write lands first
 * (D-REL-16). "Household settings have not synced yet" shows for a parent phone of a real household that has no policy
 * row although a sync completed (D-REL-14).
 */
@Suppress("LongParameterList") // One source per card.
class SyncHouseholdStatusSource @Inject constructor(
    private val sessions: SessionStore,
    private val sync: SyncController,
    private val ledger: RoomLedgerRepository,
    private val outbox: RoomOutboxSource,
    private val database: SproutDatabase,
    private val kids: KidRepository,
    private val policies: HouseholdPolicyRepository,
    private val keyChanges: ParentKeyChangeProgress,
) : HouseholdStatusSource {
    override fun observe(): Flow<HouseholdStatus> = combine(
        combine(sessions.observe(), sync.status, outbox.observeBlocked(), ::Triple),
        ledger.observeQuarantined(),
        database.deviceDao().observeAll(),
        kids.observeKids(),
        combine(
            policies.observeServerRegistrationPending(),
            policies.observe(),
            keyChanges.observeInProgress().holdingFallingEdge(KEY_CHANGE_SETTLE_MS),
            ::Triple,
        ),
    ) { (session, status, blocked), quarantined, devices, kidList, (keyPending, policy, enrolling) ->
        val parent = session?.role == Role.PARENT && !enrolling
        val trial = session?.isLocalTrial ?: false
        val byId = devices.associateBy { it.deviceId }
        val names = kidList.associate { it.id to it.firstName }
        fun label(deviceId: DeviceId): DeviceLabel {
            val device = byId[deviceId]
            return DeviceLabel(
                role = device?.role,
                kidName = device?.kidId?.let(names::get),
                fingerprint = deviceId.value.take(FINGERPRINT_CHARS).chunked(GROUP).joinToString(" "),
            )
        }
        HouseholdStatus(
            trial = trial,
            sync = if (trial ||
                session == null
            ) {
                null
            } else {
                SyncLine(status.lastSyncedAt, blocked?.code, status.lastFailed)
            },
            rejected = quarantined.sortedByDescending { it.receivedAt }
                .map { RejectedEvent(it.reason, label(it.authorDeviceId), it.receivedAt) },
            pendingDevices = devices
                .filter {
                    it.status == RegistrationStatus.PENDING && it.role == Role.PARENT &&
                        it.deviceId != session?.deviceId
                }
                .map { PendingDevice(it.deviceId, label(it.deviceId).fingerprint) },
        ).withSigningCards(
            parent = parent,
            realParent = parent && !trial,
            keyPending = keyPending,
            policy = policy,
            synced = status.lastSyncedAt != null && !status.lastFailed,
        )
    }

    /** The signing and policy cards; [parent] is false while a key change runs. */
    private fun HouseholdStatus.withSigningCards(
        parent: Boolean,
        realParent: Boolean,
        keyPending: Boolean,
        policy: HouseholdPolicy?,
        synced: Boolean,
    ) = copy(
        signingKeyNotRegistered = keyPending && parent,
        // A real household's parent phone whose policy exists without its parent key (D-M3-46).
        signingOff = realParent && policy != null && !policy.hasParentKey,
        // A real household's parent phone with no policy row after a sync that completed (D-REL-14): signatures are
        // off (the ledger default) and enrolment waits for the policy or recovers it.
        policyMissing = realParent && policy == null && synced,
    )

    private companion object {
        const val FINGERPRINT_CHARS = 16
        const val GROUP = 4

        /**
         * How long the signing cards stay hidden after a key change ended (D-REL-16): the change's last write (the
         * `PARENT_KEY_REGISTERED` audit row) reaches the policy flows only when Room re-runs their queries, after the
         * progress has already dropped, so without it "Signing key not registered" flashes for a frame or two.
         */
        const val KEY_CHANGE_SETTLE_MS = 500L
    }
}

/**
 * This flow with every change to false held back by [settleMs] (a change back to true within it cancels the false);
 * the first value and every true pass at once.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun Flow<Boolean>.holdingFallingEdge(settleMs: Long): Flow<Boolean> = flow {
    var first = true
    emitAll(
        distinctUntilChanged().transformLatest { value ->
            if (!value && !first) delay(settleMs)
            first = false
            emit(value)
        },
    )
}

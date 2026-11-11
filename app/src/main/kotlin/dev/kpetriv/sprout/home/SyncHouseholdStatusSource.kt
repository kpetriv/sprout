package dev.kpetriv.sprout.home

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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
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
            keyChanges.observeInProgress(),
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
            signingKeyNotRegistered = keyPending && parent,
            // A real household's parent phone whose policy exists without its parent key (D-M3-46).
            signingOff = !trial && parent && policy != null && !policy.hasParentKey,
        )
    }

    private companion object {
        const val FINGERPRINT_CHARS = 16
        const val GROUP = 4
    }
}

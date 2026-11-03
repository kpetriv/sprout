package dev.kpetriv.sprout.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kpetriv.sprout.core.database.entity.RegistrationStatus
import dev.kpetriv.sprout.core.domainledger.AttestationLevel
import dev.kpetriv.sprout.core.model.DeviceId
import dev.kpetriv.sprout.core.model.Role
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** How far a device's key is attested (D-M3-5): StrongBox, TEE, or not at all (emulators, software keys). */
enum class AttestationLabel {
    STRONGBOX,
    TEE,
    UNVERIFIED,
    ;

    companion object {
        fun of(level: AttestationLevel): AttestationLabel = when (level) {
            AttestationLevel.STRONGBOX -> STRONGBOX
            AttestationLevel.TEE -> TEE
            AttestationLevel.SOFTWARE, AttestationLevel.NONE -> UNVERIFIED
        }
    }
}

/** One row of the devices list. */
data class DeviceRowUi(
    val deviceId: DeviceId,
    val fingerprint: String,
    val role: Role,
    val kidName: String?,
    val attestation: AttestationLabel,
    val status: RegistrationStatus,
    val isThisDevice: Boolean,
) {
    val canApprove: Boolean get() = status == RegistrationStatus.PENDING && role == Role.PARENT
    val canRevoke: Boolean get() = status != RegistrationStatus.REVOKED && !isThisDevice

    override fun toString(): String = "DeviceRowUi(deviceId=$deviceId, role=$role, status=$status)"
}

/** What the devices section says once an action finished. */
enum class DevicesMessage {
    APPROVED,
    REVOKED,
    FAILED,
    NETWORK,
    NOT_ALLOWED,
}

data class DevicesUiState(
    val isLoading: Boolean = true,
    val loadFailed: Boolean = false,
    val devices: List<DeviceRowUi> = emptyList(),
    /** The approval sheet: compare this fingerprint with the new phone's, then approve. */
    val reviewing: DeviceRowUi? = null,
    /** The revoke confirmation. */
    val revoking: DeviceRowUi? = null,
    val busy: Boolean = false,
    val message: DevicesMessage? = null,
)

sealed interface DevicesEvent {
    /** Open the approval sheet for a PENDING device (from its row or the dashboard's card). */
    data class Review(val deviceId: DeviceId) : DevicesEvent

    data object Approve : DevicesEvent

    data object DismissReview : DevicesEvent

    data class AskRevoke(val deviceId: DeviceId) : DevicesEvent

    data object ConfirmRevoke : DevicesEvent

    data object DismissRevoke : DevicesEvent

    data object MessageShown : DevicesEvent
}

/**
 * Settings → Devices: every registered device with role, linked kid, attestation label and status. "Approve" on a
 * PENDING parent device shows its fingerprint for the parent to compare with the new phone, then approves it (the
 * app signs the approval with this device's key, T8). "Revoke" asks first; this device cannot revoke itself.
 */
@HiltViewModel
class DevicesViewModel @Inject constructor(private val admin: DeviceAdmin) : ViewModel() {
    private val local = MutableStateFlow(DevicesUiState())

    /** A review asked for before the list loaded (the dashboard's card); opened once the device shows up. */
    private val pendingReview = MutableStateFlow<DeviceId?>(null)

    val state: StateFlow<DevicesUiState> = combine(rows(), local, pendingReview) { rows, localState, pending ->
        val devices = (rows as? Load.Ready)?.value.orEmpty()
        val reviewing = localState.reviewing ?: pending?.let { id -> devices.firstOrNull { it.deviceId == id } }
        localState.copy(
            isLoading = rows is Load.Loading,
            loadFailed = rows is Load.Failed,
            devices = devices,
            reviewing = reviewing?.takeIf { it.canApprove },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), DevicesUiState())

    fun onEvent(event: DevicesEvent) {
        when (event) {
            is DevicesEvent.Review -> review(event.deviceId)

            DevicesEvent.Approve -> approve()

            DevicesEvent.DismissReview -> {
                pendingReview.value = null
                local.update { it.copy(reviewing = null) }
            }

            is DevicesEvent.AskRevoke -> {
                val row = state.value.devices.firstOrNull { it.deviceId == event.deviceId && it.canRevoke }
                local.update { it.copy(revoking = row) }
            }

            DevicesEvent.ConfirmRevoke -> revoke()

            DevicesEvent.DismissRevoke -> local.update { it.copy(revoking = null) }

            DevicesEvent.MessageShown -> local.update { it.copy(message = null) }
        }
    }

    private fun review(deviceId: DeviceId) {
        val row = state.value.devices.firstOrNull { it.deviceId == deviceId }
        if (row == null) pendingReview.value = deviceId else local.update { it.copy(reviewing = row) }
    }

    private fun approve() {
        val row = state.value.reviewing ?: return
        act { admin.approve(row.deviceId) to DevicesMessage.APPROVED }
    }

    private fun revoke() {
        val row = local.value.revoking ?: return
        act { admin.revoke(row.deviceId) to DevicesMessage.REVOKED }
    }

    private fun act(call: suspend () -> Pair<Result<Unit>, DevicesMessage>) {
        if (local.value.busy) return
        local.update { it.copy(busy = true) }
        viewModelScope.launch {
            val message = try {
                val (result, success) = call()
                result.fold({ success }, { messageOf(it) })
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") failure: Exception) {
                DevicesMessage.FAILED
            }
            pendingReview.value = null
            local.update { it.copy(busy = false, reviewing = null, revoking = null, message = message) }
        }
    }

    private fun messageOf(failure: Throwable): DevicesMessage = when ((failure as? AdminException)?.failure) {
        AdminFailure.NETWORK -> DevicesMessage.NETWORK
        AdminFailure.NOT_ALLOWED -> DevicesMessage.NOT_ALLOWED
        else -> DevicesMessage.FAILED
    }

    private fun rows() = admin.observeDevices()
        .map<List<HouseholdDevice>, Load<List<DeviceRowUi>>> { devices ->
            Load.Ready(
                devices.sortedWith(compareBy({ it.status.order() }, { it.registeredAt })).map { device ->
                    DeviceRowUi(
                        deviceId = device.deviceId,
                        fingerprint = displayFingerprint(device.deviceId),
                        role = device.role,
                        kidName = device.kidName,
                        attestation = AttestationLabel.of(device.attestation),
                        status = device.status,
                        isThisDevice = device.isThisDevice,
                    )
                },
            )
        }
        .onStart { emit(Load.Loading) }
        .catch { emit(Load.Failed) }

    /** PENDING first (they need the parent), then ACTIVE, then REVOKED. */
    private fun RegistrationStatus.order(): Int = when (this) {
        RegistrationStatus.PENDING -> 0
        RegistrationStatus.ACTIVE -> 1
        RegistrationStatus.REVOKED -> 2
    }
}

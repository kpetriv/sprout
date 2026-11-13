package dev.kpetriv.sprout.core.sync

import dev.kpetriv.sprout.core.model.DeviceId
import dev.kpetriv.sprout.core.model.Role
import dev.kpetriv.sprout.core.model.UserId

/**
 * One device's account-recovery callables against a [FakeBackend], signed in as [uid] (a PARENT account): the device
 * proof is the device id itself, as [FakeSyncTransport]'s caller is. [calls] counts every call.
 */
class FakeRecoveryCallables(private val backend: FakeBackend, val deviceId: DeviceId, val uid: UserId) :
    RecoveryCallables {
    private val caller = FakeBackend.Caller(deviceId, uid, Role.PARENT)

    /** Every call, in order: `setRecoveryPin`, `recoveryStatus`, `recoverParentDevice:PIN` or `...:WAIT`. */
    val calls = mutableListOf<String>()

    override suspend fun setRecoveryPin(
        salt: ByteArray,
        kdf: String,
        verifier: ByteArray,
        newPin: Boolean,
    ): Result<StoredRecoveryPin> {
        calls += "setRecoveryPin:${if (newPin) "new" else "same"}"
        return backend.setRecoveryPin(caller, salt, kdf, verifier, newPin)
    }

    override suspend fun recoveryStatus(): Result<RecoveryStatus> {
        calls += "recoveryStatus"
        return backend.recoveryStatus(caller)
    }

    override suspend fun recoverParentDevice(request: RecoveryRequest): Result<RecoveredParentDevice> {
        calls += "recoverParentDevice:${request.method}"
        return backend.recoverParentDevice(caller, request)
    }
}

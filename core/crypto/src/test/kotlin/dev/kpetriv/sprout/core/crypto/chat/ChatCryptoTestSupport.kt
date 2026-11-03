package dev.kpetriv.sprout.core.crypto.chat

import dev.kpetriv.sprout.core.crypto.InMemoryDeviceKeyStore
import dev.kpetriv.sprout.core.crypto.X25519KeyAgreement
import dev.kpetriv.sprout.core.crypto.X25519KeyPair
import dev.kpetriv.sprout.core.domainledger.chat.ThreadMember
import dev.kpetriv.sprout.core.model.DeviceId
import dev.kpetriv.sprout.core.model.HouseholdId
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.Role
import dev.kpetriv.sprout.core.model.ThreadId
import dev.kpetriv.sprout.core.time.FakeClock

internal val household = HouseholdId("house-1")
internal val thread = ThreadId.of(KidId("kid-1"))

/** A device with a real X25519 key pair (from [agreement]) and an in-memory P-256 device key. */
internal class CryptoDevice(id: String, val role: Role, agreement: X25519KeyAgreement) {
    val deviceId = DeviceId(id)
    val keys: X25519KeyPair = agreement.generateKeyPair()
    val deviceKeys = InMemoryDeviceKeyStore(FakeClock())

    fun member(): ThreadMember = ThreadMember(deviceId, role, keys.publicKey.bytes)
}

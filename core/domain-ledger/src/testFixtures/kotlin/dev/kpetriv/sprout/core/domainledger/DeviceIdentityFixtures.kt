package dev.kpetriv.sprout.core.domainledger

import dev.kpetriv.sprout.core.model.DeviceId
import dev.kpetriv.sprout.core.model.HouseholdId
import dev.kpetriv.sprout.core.model.Role
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec

/**
 * A software EC P-256 key standing in for a Keystore device key or parent authentication key. Signs with
 * `SHA256withECDSA` (DER signatures), and exposes its public key as X.509 SPKI DER, as the registry stores it.
 * Usable as a device [signer] ([DeviceWriter.deviceKey], [signWith]) and as a [ParentAuthorization].
 */
class SoftwareKey : ParentAuthorization {
    private val keyPair: KeyPair = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"))
    }.generateKeyPair()

    /** X.509 SPKI DER. */
    val publicKey: ByteArray = keyPair.public.encoded

    /** Lower-case hex SHA-256 of [publicKey], as [RegisteredParentKey.fingerprint]. */
    val fingerprint: String = JvmSha256Hasher.sha256(publicKey).toHex()

    val signer: Signer = ::sign

    override fun sign(payload: ByteArray): ByteArray = Signature.getInstance(ALGORITHM).run {
        initSign(keyPair.private)
        update(payload)
        sign()
    }

    internal companion object {
        const val ALGORITHM = "SHA256withECDSA"
    }
}

/** [SignatureCheck] over `java.security`: SPKI DER P-256 keys, DER ECDSA signatures. False on any malformed input. */
object SoftwareSignatureCheck : SignatureCheck {
    override fun verify(publicKey: ByteArray, payload: ByteArray, signature: ByteArray): Boolean = try {
        val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(publicKey))
        Signature.getInstance(SoftwareKey.ALGORITHM).run {
            initVerify(key)
            update(payload)
            verify(signature)
        }
    } catch (expected: GeneralSecurityException) {
        false
    } catch (expected: IllegalArgumentException) {
        false
    }
}

/** A [DeviceRegistry] held in memory. Not thread-safe. */
class InMemoryDeviceRegistry : DeviceRegistry {
    private val devices = LinkedHashMap<DeviceId, RegisteredDevice>()
    private val keys = ArrayList<RegisteredParentKey>()

    override suspend fun device(deviceId: DeviceId): RegisteredDevice? = devices[deviceId]

    override suspend fun parentKeys(): List<RegisteredParentKey> = keys.toList()

    /** Registers (or replaces) [device]. */
    fun register(device: RegisteredDevice): RegisteredDevice = device.also { devices[it.deviceId] = it }

    /** Registers [author]'s device with [key], owned by [author]'s uid and role. */
    fun register(
        author: EventAuthor,
        key: SoftwareKey,
        status: DeviceStatus = DeviceStatus.Active,
        role: Role = author.role,
    ): RegisteredDevice = register(
        RegisteredDevice(
            deviceId = author.deviceId,
            owner = DeviceOwner(author.uid, role, kidId = Fixtures.kid.takeIf { role == Role.KID }),
            publicKey = key.publicKey,
            status = status,
            attestation = AttestationLevel.TEE,
        ),
    )

    /** Registers [key] as a parent key of [author], valid from [validFromLamport]. */
    fun addParentKey(
        author: EventAuthor,
        key: SoftwareKey,
        validFromLamport: Long = 0,
        revokedAtLamport: Long? = null,
    ): RegisteredParentKey = RegisteredParentKey(
        fingerprint = key.fingerprint,
        publicKey = key.publicKey,
        uid = author.uid,
        deviceId = author.deviceId,
        validFromLamport = validFromLamport,
        revokedAtLamport = revokedAtLamport,
    ).also { keys += it }

    /** The registered role of [deviceId], or null: a `roleOf` for [Ledger.project] and [appliedCopyOf]. */
    fun roleOf(deviceId: DeviceId): Role? = devices[deviceId]?.role
}

/**
 * Binds the raw X25519 [encryptionKey] to [deviceId] with this device key (M4 §1.1), as a registry row would carry it.
 */
fun SoftwareKey.bindEncryptionKey(
    householdId: HouseholdId,
    deviceId: DeviceId,
    encryptionKey: ByteArray,
): BoundEncryptionKey {
    val signature = sign(EncryptionKeyBindingPayload.of(householdId, deviceId, encryptionKey))
    return BoundEncryptionKey(householdId, encryptionKey, signature)
}

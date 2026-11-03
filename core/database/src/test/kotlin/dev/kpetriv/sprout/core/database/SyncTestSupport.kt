package dev.kpetriv.sprout.core.database

import dev.kpetriv.sprout.core.database.entity.OutboxKind
import dev.kpetriv.sprout.core.domainledger.AttestationLevel
import dev.kpetriv.sprout.core.domainledger.DeviceOwner
import dev.kpetriv.sprout.core.domainledger.DeviceStatus
import dev.kpetriv.sprout.core.domainledger.Envelope
import dev.kpetriv.sprout.core.domainledger.EventId
import dev.kpetriv.sprout.core.domainledger.EventIdGenerator
import dev.kpetriv.sprout.core.domainledger.LamportSource
import dev.kpetriv.sprout.core.domainledger.LedgerEvent
import dev.kpetriv.sprout.core.domainledger.LedgerEventFactory
import dev.kpetriv.sprout.core.domainledger.LedgerJson
import dev.kpetriv.sprout.core.domainledger.RegisteredDevice
import dev.kpetriv.sprout.core.domainledger.RegisteredParentKey
import dev.kpetriv.sprout.core.domainledger.UuidV4EventIdGenerator
import dev.kpetriv.sprout.core.domainledger.signedPayload
import dev.kpetriv.sprout.core.domainledger.withSignature
import dev.kpetriv.sprout.core.model.AccountId
import dev.kpetriv.sprout.core.model.HouseholdId
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.Role
import dev.kpetriv.sprout.core.sync.DocumentEnvelope
import dev.kpetriv.sprout.core.sync.DocumentJsonCodec
import kotlinx.serialization.json.JsonObject
import org.robolectric.RuntimeEnvironment

/** The household the sync tests link their devices to. */
internal val HOME = HouseholdId("home-1")

/** The kid the kid device is linked to. */
internal val KID_X = KidId("kid-x")

internal const val PARENT_UID = "parent-uid"
internal const val KID_UID = "kid-uid"

/** A parent device signed in to [HOME]. */
internal fun parentDevice(): TestHousehold = TestHousehold(
    RuntimeEnvironment.getApplication(),
    sessionFor = TestHousehold.parentSession(HOME, PARENT_UID),
)

/** A kid device of [HOME] linked to [KID_X]. */
internal fun kidDevice(): TestHousehold = TestHousehold(
    RuntimeEnvironment.getApplication(),
    sessionFor = TestHousehold.kidSession(HOME, KID_UID, KID_X),
)

/** [this] device as the registry lists it: ACTIVE, with its session's uid, role and kid. */
internal fun TestHousehold.registered(status: DeviceStatus = DeviceStatus.Active): RegisteredDevice = RegisteredDevice(
    deviceId = deviceId,
    owner = DeviceOwner(session.uid, session.role, session.kidId),
    publicKey = keyStore.publicKey().encoded,
    status = status,
    attestation = AttestationLevel.NONE,
)

/** Registers every device of [devices] (ACTIVE) in each one's registry mirror. */
internal suspend fun registerEachOther(vararg devices: TestHousehold) {
    val registered = devices.map { it.registered() }
    devices.forEach { it.applier.applyRegistry(registered, emptyList()) }
}

/** The signed envelopes queued in this device's outbox, oldest first. */
internal suspend fun TestHousehold.queuedDocuments(): List<DocumentEnvelope> = database.outboxDao().all()
    .filter { it.kind == OutboxKind.DOCUMENT }
    .map { requireNotNull(DocumentJsonCodec.decodeOrNull(it.payload)) }

/** The ledger events queued in this device's outbox, oldest first. */
internal suspend fun TestHousehold.queuedEvents(): List<JsonObject> = database.outboxDao().all()
    .filter { it.kind == OutboxKind.LEDGER_EVENT }
    .map { LedgerJson.json.parseToJsonElement(it.payload) as JsonObject }

/**
 * [from]'s parent keys as the server registry lists them once `registerParentKey` succeeded (D-M3-34): keys are never
 * pushed as documents, so a test stands in for the server here.
 */
internal suspend fun TestHousehold.serverRegisteredKeys(): List<RegisteredParentKey> =
    database.parentKeyDao().all().map {
        RegisteredParentKey(it.fingerprint, it.publicKey, it.uid, it.deviceId, it.validFromLamport, it.revokedAtLamport)
    }

/**
 * Applies what a pull would bring from [from]: its registered parent keys (the server registry), then everything it
 * has queued (documents, then events).
 */
internal suspend fun TestHousehold.pullFrom(from: TestHousehold) {
    applier.applyRegistry(emptyList(), from.serverRegisteredKeys())
    applier.applyDocuments(from.queuedDocuments())
    applier.applyEvents(from.queuedEvents())
}

/**
 * An event signed with this device's key but never stored here: the forged or crafted input a receiver must cope
 * with. It links to this device's stored head unless [prevHash] is given.
 */
@Suppress("LongParameterList") // Every envelope field a test may want to forge.
internal suspend fun TestHousehold.craft(
    kidId: KidId,
    accountId: AccountId,
    role: Role = author.role,
    lamport: Long? = null,
    eventId: EventId? = null,
    prevHash: String? = null,
    body: (Envelope) -> LedgerEvent,
): JsonObject {
    val value = lamport ?: (ledger.localMaxLamport() + 1)
    val ids = eventId?.let { id -> EventIdGenerator { id } } ?: UuidV4EventIdGenerator()
    val factory = LedgerEventFactory(clock, LamportSource { value }, ids)
    val head = prevHash ?: ledger.headHash(deviceId)
    val unsigned = factory.create(author.copy(role = role), kidId, accountId, head, body)
    return LedgerJson.encodeToJsonObject(unsigned.withSignature(signer.sign(unsigned.signedPayload())))
}

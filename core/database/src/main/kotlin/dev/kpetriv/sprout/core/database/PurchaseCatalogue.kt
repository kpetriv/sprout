package dev.kpetriv.sprout.core.database

import dev.kpetriv.sprout.core.database.entity.RewardEntity
import dev.kpetriv.sprout.core.database.entity.RewardVersionEntity
import dev.kpetriv.sprout.core.domainhousehold.Reward
import dev.kpetriv.sprout.core.domainledger.DeviceRegistry
import dev.kpetriv.sprout.core.domainledger.RewardPurchased
import dev.kpetriv.sprout.core.domainledger.SignatureCheck
import dev.kpetriv.sprout.core.model.DeviceId
import dev.kpetriv.sprout.core.model.HouseholdId
import dev.kpetriv.sprout.core.model.ID_PATTERN
import dev.kpetriv.sprout.core.model.RewardId
import dev.kpetriv.sprout.core.model.Role
import dev.kpetriv.sprout.core.sync.DocTypes
import dev.kpetriv.sprout.core.sync.DocumentEnvelope
import java.nio.charset.CharacterCodingException

/**
 * Checks a kid-authored [RewardPurchased] against the reward catalogue. [matches] runs inside a transaction. Appending
 * devices only; receivers flag rather than refuse ([RewardTableCatalogue.matchesAnyVersion], D-REL-65).
 */
internal fun interface PurchaseCatalogue {
    suspend fun matches(event: RewardPurchased): Boolean
}

/**
 * The production [PurchaseCatalogue]: the purchase names a reward in `rewards` that is live (not a tombstone), active
 * and available to the purchasing kid, and its amount, currency and granted minutes are exactly the reward's cost,
 * currency and `minutesGranted`. The ledger itself cannot know the catalogue, so this is where a kid device is stopped
 * from buying screen time at its own price.
 *
 * The reward row must also be **parent-authored** (ADR-0005, T27, D-M3-3), one of:
 * - written on this device while its session is a PARENT session (the row's author device is the session's device):
 *   the parent's own catalogue, and kid mode on a parent device;
 * - a pulled document whose author device the registry lists as a PARENT device accepting the row's lamport, and
 *   whose device signature verifies over the envelope rebuilt from the row ([DocumentPayloads] encodes the row's
 *   state exactly as the author did, so a row changed after it was applied no longer verifies).
 *
 * A reward a KID session wrote on this device, an unsigned row, or one signed by an unknown or kid device is refused.
 */
internal class RewardTableCatalogue(
    private val database: SproutDatabase,
    private val check: SignatureCheck,
    private val registry: DeviceRegistry = RoomDeviceRegistry(database),
) : PurchaseCatalogue {
    override suspend fun matches(event: RewardPurchased): Boolean {
        val row = event.rewardId.takeIf(ID_PATTERN::matches)
            ?.let { database.rewardDao().get(RewardId(it)) }
            ?.takeUnless { it.stamp.deleted }
            ?: return false
        return sells(row, row.toDomain(), event)
    }

    /**
     * The receiver-side check of a pulled kid purchase (D-REL-65): some reward version this device recorded
     * (`reward_versions`) sells it ([versionSells]). Lamports are not causal, so a kid who bought offline at a price
     * that a parent changed meanwhile is still matched by the version it saw, if this device saw it too. Inside a
     * transaction.
     */
    suspend fun matchesAnyVersion(event: RewardPurchased): Boolean {
        val id = event.rewardId.takeIf(ID_PATTERN::matches)?.let(::RewardId) ?: return false
        return database.rewardVersionDao().before(id, event.lamport).any { versionSells(it, event) }
    }

    /**
     * [version] sells [event] by the same rule as [matches]: same reward id, a lower lamport than the purchase, live,
     * available to the kid, same cost, currency and minutes, parent-authored. Fails closed: a tombstone, a marker, or
     * a row that no longer decodes into a valid reward, no longer rebuilds into a valid envelope or no longer verifies
     * never sells, and never throws ([IllegalArgumentException], [CharacterCodingException]) into the caller's
     * ingest or document transaction.
     */
    suspend fun versionSells(version: RewardVersionEntity, event: RewardPurchased): Boolean = try {
        val row = version.toRewardRow()
        val reward = row?.toDomain()
        row != null && reward != null && version.rewardId.value == event.rewardId &&
            version.lamport < event.lamport && sells(row, reward, event)
    } catch (expected: IllegalArgumentException) {
        false
    } catch (expected: CharacterCodingException) {
        // A title edited into an unpaired surrogate cannot be canonically encoded.
        false
    }

    /** [row] ([reward]) sells [event]: available to its kid, at its amount, currency and minutes, parent-authored. */
    private suspend fun sells(row: RewardEntity, reward: Reward, event: RewardPurchased): Boolean =
        reward.isAvailableTo(event.envelope.kidId) &&
            reward.cost == event.amount &&
            reward.currency == event.currency &&
            reward.minutesGranted?.toLong() == event.minutesGranted?.minorUnits &&
            isParentAuthored(row, reward)

    private suspend fun isParentAuthored(row: RewardEntity, reward: Reward): Boolean {
        val author = row.stamp.authorDeviceId
        val session = database.sessionDao().get()
        return when {
            author == null || session == null -> false
            session.role == Role.PARENT && session.deviceId == author -> true
            else -> signedByParentDevice(row, reward, author, session.householdId)
        }
    }

    /** [row] was applied from a document signed by [author], a registered PARENT device active at its lamport. */
    private suspend fun signedByParentDevice(
        row: RewardEntity,
        reward: Reward,
        author: DeviceId,
        householdId: HouseholdId,
    ): Boolean {
        val device = registry.device(author)
        val signature = row.stamp.signature
        val uid = row.stamp.authorUid
        return device != null && device.role == Role.PARENT && device.acceptsLamport(row.lamport) &&
            signature != null && uid != null &&
            DocumentEnvelope(
                householdId = householdId,
                docType = DocTypes.REWARD,
                docId = reward.id.value,
                authorUid = uid,
                authorDeviceId = author,
                lamport = row.lamport,
                deleted = false,
                payload = DocumentPayloads.encode(Reward.serializer(), reward),
                signature = signature,
            ).verifyWith(check, device.publicKey)
    }
}

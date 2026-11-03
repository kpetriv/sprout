package dev.kpetriv.sprout.core.domainledger.chat

import dev.kpetriv.sprout.core.model.DeviceId
import dev.kpetriv.sprout.core.model.Role

/** What a PARENT device should do about one thread's key ([ThreadKeyPolicy.decide]). */
sealed interface ThreadKeyDecision {
    /** The latest epoch's wrap set is right (or another device has to act). */
    data object NoAction : ThreadKeyDecision

    /**
     * Members were added: publish a new document for the **same** [epoch] with the key this device already holds,
     * wrapped for all of [members] (a superset of recipients; LWW-safe because the key is the same). [forDevices] are
     * the new recipients. Devices in the old wrap set that this device does not know are not carried over (their
     * wraps were by another sender and cannot be re-created without their key); they keep the key they already
     * unwrapped, and a device that lost it waits for a parent whose registry knows it.
     */
    data class ReWrapCurrent(val epoch: Long, val members: List<ThreadMember>, val forDevices: Set<DeviceId>) :
        ThreadKeyDecision

    /** Generate a fresh key for [newEpoch] and wrap it only for [members] (a removal, or the first key). */
    data class Rotate(val newEpoch: Long, val members: List<ThreadMember>) : ThreadKeyDecision
}

/**
 * The epoch rules of M4 §1.2, pure. Inputs are one device's view: its registry mirror ([ThreadMembership]) and the
 * latest `thread_key` document it has for the thread (the one with the highest epoch).
 */
object ThreadKeyPolicy {
    /**
     * Decides for [self], a device that is about to publish thread keys. Only a PARENT member with a verified
     * encryption key ever acts; anything else gets [ThreadKeyDecision.NoAction].
     *
     * 1. No document yet: [ThreadKeyDecision.Rotate] to epoch 0 (the first PARENT device with a key creates it).
     * 2. Removal: the latest wrap set contains an [ThreadMembership.excluded] device (REVOKED, another kid's device,
     *    or the archived kid's device): rotate to `latest.epoch + 1` for the current members only.
     * 3. Addition: some members have no wrap in the latest epoch. If [selfHoldsLatestKey], re-wrap the current epoch
     *    for all members; if not, wait for another PARENT member in the latest wrap set to do it, and when there is
     *    none (nobody could re-wrap), rotate instead.
     * 4. Otherwise nothing to do.
     *
     * @param latest the latest epoch's document for [membership]'s thread, or null when there is none.
     * @param selfHoldsLatestKey [self] has the key of `latest.epoch` (it unwrapped it, or created it).
     */
    fun decide(
        membership: ThreadMembership,
        self: DeviceId,
        latest: ThreadKeyDocument?,
        selfHoldsLatestKey: Boolean,
    ): ThreadKeyDecision = when {
        membership.member(self)?.role != Role.PARENT -> ThreadKeyDecision.NoAction
        latest == null -> ThreadKeyDecision.Rotate(newEpoch = 0, members = membership.members)
        else -> decideFrom(membership, self, latest, selfHoldsLatestKey)
    }

    /** Rules 2 to 4, for a [self] that is a PARENT member. */
    private fun decideFrom(
        membership: ThreadMembership,
        self: DeviceId,
        latest: ThreadKeyDocument,
        selfHoldsLatestKey: Boolean,
    ): ThreadKeyDecision {
        require(latest.threadId == membership.threadId) { "the document belongs to another thread" }
        val members = membership.members
        val added = members.map { it.deviceId }.filterTo(LinkedHashSet()) { it !in latest.recipients }
        val anotherParentCanReWrap = members.any {
            it.role == Role.PARENT && it.deviceId != self && it.deviceId in latest.recipients
        }
        return when {
            latest.recipients.any { it in membership.excluded } -> rotate(latest, members)
            added.isEmpty() -> ThreadKeyDecision.NoAction
            selfHoldsLatestKey -> ThreadKeyDecision.ReWrapCurrent(latest.epoch, members, added)
            anotherParentCanReWrap -> ThreadKeyDecision.NoAction
            else -> rotate(latest, members)
        }
    }

    /**
     * The epoch a device may encrypt new messages with (M4 §1.2), or null when it must show "waiting for a parent
     * device" and not send: the latest epoch, provided this device holds its key ([heldEpochs]) and its wrap set
     * contains no [excluded] device (a removal that no parent has rotated yet). An older epoch is never used for
     * sending, because devices added since then cannot read it.
     */
    fun sendingEpoch(latest: ThreadKeyDocument?, heldEpochs: Set<Long>, excluded: Set<DeviceId>): Long? =
        latest?.takeIf { doc -> doc.epoch in heldEpochs && doc.recipients.none { it in excluded } }?.epoch

    private fun rotate(latest: ThreadKeyDocument, members: List<ThreadMember>): ThreadKeyDecision {
        check(latest.epoch < Long.MAX_VALUE) { "epoch overflow" }
        return ThreadKeyDecision.Rotate(latest.epoch + 1, members)
    }
}

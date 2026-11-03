package dev.kpetriv.sprout.core.domainledger.chat

import dev.kpetriv.sprout.core.model.DeviceId
import dev.kpetriv.sprout.core.model.Role

/** What a PARENT device should do about one thread's key ([ThreadKeyPolicy.decide]). */
sealed interface ThreadKeyDecision {
    /** The latest epoch's wrap sets are right (or another device has to act). */
    data object NoAction : ThreadKeyDecision

    /**
     * Members were added: publish this device's document for the **same** [epoch] wrapping the key of [fromCreator]'s
     * document (this device's own key when it has one, otherwise another creator's key it holds) for all of
     * [members], a superset of that document's recipients. [forDevices] are the new recipients. A device that a later
     * epoch dropped is never wrapped again (audit L2): it was rotated away from for a reason this device's registry
     * mirror may not show yet.
     */
    data class ReWrapCurrent(
        val epoch: Long,
        val members: List<ThreadMember>,
        val forDevices: Set<DeviceId>,
        val fromCreator: DeviceId,
    ) : ThreadKeyDecision

    /** Generate a fresh key for [newEpoch] and wrap it only for [members] (a removal, or the first key). */
    data class Rotate(val newEpoch: Long, val members: List<ThreadMember>) : ThreadKeyDecision
}

/**
 * The epoch rules of M4 §1.2, pure. Inputs are one device's view: its registry mirror ([ThreadMembership]) and the
 * `thread_key` documents it has for the thread. Documents are per creator (D-M4-3 as amended): the **latest epoch**
 * may have several documents, one per PARENT device that created or re-wrapped a key for it, and every check below
 * looks at the union of their wrap sets.
 */
object ThreadKeyPolicy {
    /**
     * Largest step between two epochs that is believed (audit L4): a document whose epoch is more than this above the
     * highest epoch reachable from 0 in such steps is ignored, so one forged or broken document cannot push every
     * thread towards `Long.MAX_VALUE`.
     */
    const val MAX_EPOCH_STEP: Long = 1_000

    /**
     * The epochs of [epochs] reachable from 0 in steps of at most [MAX_EPOCH_STEP] (ascending): the ones this device
     * acts on. Anything above a larger gap is ignored.
     */
    fun plausibleEpochs(epochs: Collection<Long>): List<Long> {
        val sorted = epochs.filter { it >= 0 }.distinct().sorted()
        val accepted = ArrayList<Long>(sorted.size)
        var highest = -1L
        for (epoch in sorted) {
            if (epoch - highest > MAX_EPOCH_STEP) break
            accepted += epoch
            highest = epoch
        }
        return accepted
    }

    /** The highest [plausibleEpochs] epoch of [documents], or null when there is none. */
    fun latestEpoch(documents: Collection<ThreadKeyDocument>): Long? =
        plausibleEpochs(documents.map { it.epoch }).lastOrNull()

    /**
     * Decides for [self], a device that is about to publish thread keys. Only a PARENT member with a verified
     * encryption key ever acts; anything else gets [ThreadKeyDecision.NoAction].
     *
     * 1. No (plausible) document yet: [ThreadKeyDecision.Rotate] to epoch 0.
     * 2. Removal: the union of the latest epoch's wrap sets contains an [ThreadMembership.excluded] device: rotate to
     *    `latest + 1` for the current members only.
     * 3. Addition, when [self] holds a latest-epoch key ([heldCreators] non-empty): members missing from the wrap set
     *    of the document whose key [self] would send with (its own when held, else the held one of the lowest creator
     *    id) and not dropped by the latest epoch are re-wrapped ([ThreadKeyDecision.ReWrapCurrent]).
     * 4. Addition, when [self] holds no latest-epoch key: members missing from every latest wrap set wait for another
     *    PARENT member in those wrap sets; when there is none (nobody could re-wrap), rotate instead.
     * 5. Otherwise nothing to do.
     *
     * @param documents every document of [membership]'s thread this device has (any epoch, any creator).
     * @param heldCreators the creators of latest-epoch documents whose key [self] holds.
     */
    fun decide(
        membership: ThreadMembership,
        self: DeviceId,
        documents: List<ThreadKeyDocument>,
        heldCreators: Set<DeviceId>,
    ): ThreadKeyDecision {
        require(documents.all { it.threadId == membership.threadId }) { "a document belongs to another thread" }
        val latest = latestEpoch(documents)
        return when {
            membership.member(self)?.role != Role.PARENT -> ThreadKeyDecision.NoAction
            latest == null -> ThreadKeyDecision.Rotate(newEpoch = 0, members = membership.members)
            else -> decideFrom(membership, self, LatestEpoch.of(documents, latest), heldCreators)
        }
    }

    /**
     * The epoch a device may encrypt new messages with (M4 §1.2), or null when it must show "waiting for a parent
     * device" and not send: the latest epoch of [documents], provided this device holds a usable key of it
     * ([holdsUsableKey]: its own confirmed key or another creator's) and no latest wrap set contains an [excluded]
     * device (a removal no parent has rotated yet). An older epoch is never used for sending, because devices added
     * since then cannot read it.
     */
    fun sendingEpoch(
        documents: Collection<ThreadKeyDocument>,
        holdsUsableKey: Boolean,
        excluded: Set<DeviceId>,
    ): Long? {
        val latest = latestEpoch(documents) ?: return null
        val recipients = documents.filter { it.epoch == latest }.flatMapTo(HashSet()) { it.recipients }
        return latest.takeIf { holdsUsableKey && recipients.none { it in excluded } }
    }

    /** Rules 2 to 5, for a [self] that is a PARENT member. */
    private fun decideFrom(
        membership: ThreadMembership,
        self: DeviceId,
        latest: LatestEpoch,
        heldCreators: Set<DeviceId>,
    ): ThreadKeyDecision {
        val members = membership.members
        val eligible = members.filter { it.deviceId !in latest.dropped }
        val held = heldCreators.filter { it in latest.byCreator }
        val base = if (self in held) self else held.minByOrNull { it.value }
        return when {
            latest.recipients.any { it in membership.excluded } -> rotate(latest.epoch, members)

            base != null -> {
                val wrapped = latest.byCreator.getValue(base).recipients
                val added = eligible.map { it.deviceId }.filterTo(LinkedHashSet()) { it !in wrapped }
                if (added.isEmpty()) {
                    ThreadKeyDecision.NoAction
                } else {
                    ThreadKeyDecision.ReWrapCurrent(latest.epoch, eligible, added, base)
                }
            }

            eligible.none { it.deviceId !in latest.recipients } -> ThreadKeyDecision.NoAction

            members.any { it.role == Role.PARENT && it.deviceId != self && it.deviceId in latest.recipients } ->
                ThreadKeyDecision.NoAction

            else -> rotate(latest.epoch, members)
        }
    }

    private fun rotate(epoch: Long, members: List<ThreadMember>): ThreadKeyDecision {
        check(epoch < Long.MAX_VALUE) { "epoch overflow" }
        return ThreadKeyDecision.Rotate(epoch + 1, members)
    }

    /**
     * The latest epoch's documents by creator, the union of their recipients, and the devices an earlier epoch wrapped
     * for that the latest one does not ([dropped]).
     */
    private class LatestEpoch(
        val epoch: Long,
        val byCreator: Map<DeviceId, ThreadKeyDocument>,
        val recipients: Set<DeviceId>,
        val dropped: Set<DeviceId>,
    ) {
        companion object {
            fun of(documents: List<ThreadKeyDocument>, epoch: Long): LatestEpoch {
                val latest = documents.filter { it.epoch == epoch }
                val recipients = latest.flatMapTo(HashSet()) { it.recipients }
                val earlier = documents.filter { it.epoch < epoch }.flatMapTo(HashSet()) { it.recipients }
                return LatestEpoch(
                    epoch = epoch,
                    byCreator = latest.associateBy { it.createdByDeviceId },
                    recipients = recipients,
                    dropped = earlier - recipients,
                )
            }
        }
    }
}

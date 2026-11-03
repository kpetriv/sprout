package dev.kpetriv.sprout.core.domainledger

import dev.kpetriv.sprout.core.model.DeviceId

/** The receiver's newest stored event of one author's chain: its [hash] and [lamport]. */
data class StreamHead(val hash: String, val lamport: Long)

/**
 * Outcome of [IngestOrdering.plan].
 *
 * @property apply events that extend their author's stored chain, in [LedgerOrdering] (authors interleaved).
 * @property held events that do not (yet) link: a gap before them, or a replay of something older than the head.
 *   They are kept (the data layer's `ledger_inbox`) and passed to [IngestOrdering.plan] again on the next ingest,
 *   together with whatever newly pulled events may fill the gap.
 * @property forked events on a branch of a forked chain ([QuarantineReason.FORKED_CHAIN]), in [LedgerOrdering]:
 *   never applied. The data layer quarantines them.
 * @property forkedStored hashes of events this device **already stored** that turned out to be a branch of a fork
 *   (the stored successors [IngestOrdering.plan] was told about). The data layer quarantines those too, together with
 *   every stored event of that author that descends from them, and rebuilds its projections (they are derived, so
 *   nothing else needs undoing). Only then does every device end with the same events applied, whichever branch it
 *   happened to see first.
 */
data class IngestPlan(
    val apply: List<LedgerEvent>,
    val held: List<LedgerEvent>,
    val forked: List<LedgerEvent> = emptyList(),
    val forkedStored: Set<String> = emptySet(),
)

/**
 * Orders a batch of verified remote events ([VerifiedRemote.Ok]) for ingest (M3 §2). Pure and deterministic: the
 * same batch and stored state give the same plan on every device, whatever order the batch arrived in.
 */
object IngestOrdering {
    /**
     * Plans one ingest.
     *
     * **Forks first.** An author equivocates when two or more *different* events (different hashes) of its chain
     * link to the same `prevHash` (null: genesis). That is detected among the batch's events and against what is
     * already stored ([storedSuccessors]: the hashes of every stored event of that author, quarantined ones
     * included, whose `prevHash` is the given one). Every branch at such a point is [IngestPlan.forked], and so is
     * every event of the batch that descends from a forked branch (transitively, by `prevHash`). Neither branch is
     * ever applied: picking one would depend on which arrived first. Stored branches are reported in
     * [IngestPlan.forkedStored] for the data layer to quarantine; when that happens the stored head is on (or
     * descends from) a forked branch, so batch events that would extend it are forked as well. Because the rule only
     * asks "do two different events share a predecessor", a fork split across two pulls ends the same as one seen in
     * a single batch once the data layer has quarantined the stored branch.
     *
     * **Then chains.** The remaining events are grouped by author and walked, sorted by `(lamport, eventId)`, from
     * the stored head [headOf] returns (null: nothing stored for that author yet, so the chain starts at
     * [HashChain.GENESIS] and the first event must have a null `prevHash`). An event is applied, and becomes the
     * running head, when its `prevHash` is the running head's hash and its lamport is strictly above the running
     * head's; anything else for that author is held. A second identical copy ([isSameEventAs]) of an event in the
     * batch is dropped.
     *
     * Held events are not an error: the data layer stores them and retries them on the next ingest (a later pull
     * may bring the missing link). Callers should drop events already stored (same eventId and hash) before
     * planning; otherwise they are held, since they sit below the stored head (a stored successor with the same hash
     * is not a fork).
     *
     * Content hashes are not recomputed here: [RemoteEventVerifier] already checked each stored hash against the
     * document's own tree (which may carry fields a decoded [LedgerEvent] drops), so this only follows `prevHash`
     * links. For the same reason [HashChain.verifyChain], which rehashes decoded events, is not used.
     */
    fun plan(
        batch: List<LedgerEvent>,
        headOf: (DeviceId) -> StreamHead?,
        storedSuccessors: (author: DeviceId, prevHash: String?) -> Set<String> = { _, _ -> emptySet() },
    ): IngestPlan {
        val apply = ArrayList<LedgerEvent>()
        val held = ArrayList<LedgerEvent>()
        val forked = ArrayList<LedgerEvent>()
        val forkedStored = HashSet<String>()
        for ((author, events) in batch.groupBy { it.envelope.authorDeviceId }) {
            val copies = distinctCopies(events).sortedWith(STREAM_ORDER)
            val storedBefore = forkedStored.size
            val forkedHashes = forkedHashes(author, copies, storedSuccessors, forkedStored)
            // A stored branch is forked: the stored head is on it or descends from it, so whatever extends the head
            // descends from a forked branch too.
            val headIsForked = forkedStored.size > storedBefore
            walk(copies, headOf(author)) { event, links ->
                when {
                    event.envelope.hash in forkedHashes -> forked += event
                    links && headIsForked -> forked += event
                    links -> apply += event
                    else -> held += event
                }
                links && event.envelope.hash !in forkedHashes
            }
        }
        return IngestPlan(
            apply = apply.sortedWith(LedgerOrdering),
            held = held.sortedWith(LedgerOrdering),
            forked = forked.sortedWith(LedgerOrdering),
            forkedStored = forkedStored,
        )
    }

    /**
     * Walks one author's [copies] (in stream order) from [start]. [visit] gets each event and whether it links to
     * the running head (`prevHash` is the head's hash and the lamport is strictly above it), and returns whether the
     * event becomes the new running head.
     */
    private inline fun walk(
        copies: List<LedgerEvent>,
        start: StreamHead?,
        visit: (event: LedgerEvent, links: Boolean) -> Boolean,
    ) {
        var head = start
        for (event in copies) {
            val envelope = event.envelope
            val links = envelope.prevHash == head?.hash && (head == null || envelope.lamport > head.lamport)
            if (visit(event, links)) head = StreamHead(envelope.hash, envelope.lamport)
        }
    }

    /**
     * Hashes of one author's events (batch and stored) on a forked branch, closed under "descends from": the branches
     * at every fork point plus every batch event whose `prevHash` is one of them. Stored branch hashes are also added
     * to [forkedStored].
     */
    private fun forkedHashes(
        author: DeviceId,
        copies: List<LedgerEvent>,
        storedSuccessors: (DeviceId, String?) -> Set<String>,
        forkedStored: MutableSet<String>,
    ): Set<String> {
        val forked = HashSet<String>()
        for ((prevHash, children) in copies.groupBy { it.envelope.prevHash }) {
            val batchHashes = children.mapTo(HashSet()) { it.envelope.hash }
            val stored = storedSuccessors(author, prevHash)
            if ((batchHashes + stored).size > 1) {
                forked += batchHashes
                forked += stored
                forkedStored += stored
            }
        }
        // Descendants of a forked branch within the batch are refused with it. Copies are sorted by lamport, so one
        // pass normally reaches the fixed point; repeat in case a forged lamport does not increase along the chain.
        var grew = forked.isNotEmpty()
        while (grew) {
            grew = false
            for (event in copies) {
                val prevHash = event.envelope.prevHash
                if (prevHash != null && prevHash in forked && forked.add(event.envelope.hash)) grew = true
            }
        }
        return forked
    }

    private val STREAM_ORDER: Comparator<LedgerEvent> =
        compareBy<LedgerEvent>({ it.envelope.lamport }, { it.envelope.eventId.value })
            .thenBy { it.envelope.hash }
            .thenBy { it.envelope.parentSignature.orEmpty() }

    private fun distinctCopies(events: List<LedgerEvent>): List<LedgerEvent> {
        val kept = ArrayList<LedgerEvent>(events.size)
        for (event in events) if (kept.none { it.isSameEventAs(event) }) kept += event
        return kept
    }
}

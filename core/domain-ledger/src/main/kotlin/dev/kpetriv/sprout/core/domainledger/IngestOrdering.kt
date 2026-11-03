package dev.kpetriv.sprout.core.domainledger

import dev.kpetriv.sprout.core.model.DeviceId

/** The receiver's newest stored event of one author's chain: its [hash] and [lamport]. */
data class StreamHead(val hash: String, val lamport: Long)

/**
 * Outcome of [IngestOrdering.plan].
 *
 * @property apply events that extend their author's stored chain, in [LedgerOrdering] (authors interleaved).
 * @property held events that do not (yet) link: a gap before them, a fork that lost, or a replay of something older
 *   than the head. They are kept (the data layer's `ledger_inbox`) and passed to [IngestOrdering.plan] again on the
 *   next ingest, together with whatever newly pulled events may fill the gap.
 */
data class IngestPlan(val apply: List<LedgerEvent>, val held: List<LedgerEvent>)

/**
 * Orders a batch of verified remote events ([VerifiedRemote.Ok]) for ingest (M3 §2). Pure and deterministic: the
 * same batch and heads give the same plan on every device, whatever order the batch arrived in.
 */
object IngestOrdering {
    /**
     * Groups [batch] by author device and walks each author's events, sorted by `(lamport, eventId)`, from the
     * stored head [headOf] returns (null: nothing stored for that author yet, so the chain starts at
     * [HashChain.GENESIS] and the first event must have a null `prevHash`). An event is applied, and becomes the
     * running head, when its `prevHash` is the running head's hash and its lamport is strictly above the running
     * head's; anything else for that author is held. Of two events forking from one head, the first by
     * `(lamport, eventId)` is applied and the other held. A second identical copy ([isSameEventAs]) of an event in
     * the batch is dropped.
     *
     * Held events are not an error: the data layer stores them and retries them on the next ingest (a later pull
     * may bring the missing link). Callers should drop events already stored (same eventId and content) before
     * planning; otherwise they are held, since they sit below the stored head.
     *
     * Content hashes are not recomputed here: [RemoteEventVerifier] already checked each stored hash against the
     * document's own tree (which may carry fields a decoded [LedgerEvent] drops), so this only follows `prevHash`
     * links. For the same reason [HashChain.verifyChain], which rehashes decoded events, is not used.
     */
    fun plan(batch: List<LedgerEvent>, headOf: (DeviceId) -> StreamHead?): IngestPlan {
        val apply = ArrayList<LedgerEvent>()
        val held = ArrayList<LedgerEvent>()
        for ((author, events) in batch.groupBy { it.envelope.authorDeviceId }) {
            var head = headOf(author)
            for (event in distinctCopies(events).sortedWith(STREAM_ORDER)) {
                val envelope = event.envelope
                val links = envelope.prevHash == head?.hash && (head == null || envelope.lamport > head.lamport)
                if (links) {
                    apply += event
                    head = StreamHead(envelope.hash, envelope.lamport)
                } else {
                    held += event
                }
            }
        }
        return IngestPlan(apply.sortedWith(LedgerOrdering), held.sortedWith(LedgerOrdering))
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

package dev.kpetriv.sprout.core.syncfirebase

import kotlinx.coroutines.flow.Flow
import java.time.Instant

/**
 * The slice of Firestore that [FirebaseSyncTransport] uses, with no SDK types in it, so the transport's batching,
 * cursors, error mapping and document shapes are tested on the JVM against a fake (M3 §5). [SdkFirestoreFacade] is
 * the real implementation.
 *
 * Paths are slash-separated document or collection paths (`households/h1/events/e1`). Document data are plain values
 * ([PlainValues]): `Map<String, Any?>`, `List`, `String`, `Long`, `Double`, `Boolean`, null and [ServerTime]; on
 * writes, [ServerTimestamp] stands for `FieldValue.serverTimestamp()`. Every failure is a [FirestoreFailure].
 */
interface FirestoreFacade {
    /** Applies [writes] (full-document sets) atomically in one batch. */
    suspend fun commit(writes: List<SetWrite>)

    /** The document at [path] read from the server, or null if it does not exist. */
    suspend fun get(path: String): FacadeDocument?

    /** Every document of the collection at [path], read from the server. For small collections only. */
    suspend fun getAll(path: String): List<FacadeDocument>

    /**
     * Up to [limit] documents of the collection at [path] ordered by ([SERVER_RECEIVED_AT], document id), starting
     * after [after] (from the start when null), read from the server. Documents without the field are not returned.
     */
    suspend fun queryByServerTime(path: String, after: QueryPosition?, limit: Int): List<FacadeDocument>

    /**
     * Emits whenever the server reports a change in the collection at [path] that did not come from this client's
     * pending writes (including once for the first snapshot). With [latestOnly] only the most recently stamped
     * document ([SERVER_RECEIVED_AT] descending, limit 1) is watched, so a large collection is not read in full.
     */
    fun listen(path: String, latestOnly: Boolean): Flow<Unit>

    companion object {
        /** The server time a client write was received, stamped with `FieldValue.serverTimestamp()`. */
        const val SERVER_RECEIVED_AT: String = "serverReceivedAt"
    }
}

/** One full-document set of [data] at [path]. */
data class SetWrite(val path: String, val data: Map<String, Any?>)

/** Placeholder for `FieldValue.serverTimestamp()` in [SetWrite.data]. */
data object ServerTimestamp

/** A document read back: its id (last path segment), its data, and its [FirestoreFacade.SERVER_RECEIVED_AT]. */
data class FacadeDocument(val id: String, val data: Map<String, Any?>) {
    val serverReceivedAt: ServerTime? get() = data[FirestoreFacade.SERVER_RECEIVED_AT] as? ServerTime
}

/** A Firestore timestamp (seconds and nanoseconds since the epoch, UTC). */
data class ServerTime(val seconds: Long, val nanos: Int) : Comparable<ServerTime> {
    init {
        require(nanos in 0 until NANOS_PER_SECOND) { "nanos out of range" }
    }

    override fun compareTo(other: ServerTime): Int = compareValuesBy(this, other, { it.seconds }, { it.nanos })

    /** ISO-8601 instant, e.g. `2026-11-03T10:15:30.123456Z`. */
    fun toIsoString(): String = Instant.ofEpochSecond(seconds, nanos.toLong()).toString()

    companion object {
        private const val NANOS_PER_SECOND = 1_000_000_000

        /** Parses [toIsoString]'s form; null when [text] is not an ISO-8601 instant. */
        fun parseOrNull(text: String): ServerTime? = try {
            val instant = Instant.parse(text)
            ServerTime(instant.epochSecond, instant.nano)
        } catch (expected: java.time.format.DateTimeParseException) {
            null
        }
    }
}

/** Where a [FirestoreFacade.queryByServerTime] page resumes: just after this document. */
data class QueryPosition(val serverReceivedAt: ServerTime, val documentId: String)

/** A failed Firestore call, with its status code only (never the SDK's message). */
class FirestoreFailure(val code: FirestoreCode) : Exception(code.name)

/** Firestore status codes (`FirebaseFirestoreException.Code`), plus [TIMEOUT] for a call that did not finish. */
enum class FirestoreCode {
    CANCELLED,
    UNKNOWN,
    INVALID_ARGUMENT,
    DEADLINE_EXCEEDED,
    NOT_FOUND,
    ALREADY_EXISTS,
    PERMISSION_DENIED,
    RESOURCE_EXHAUSTED,
    FAILED_PRECONDITION,
    ABORTED,
    OUT_OF_RANGE,
    UNIMPLEMENTED,
    INTERNAL,
    UNAVAILABLE,
    DATA_LOSS,
    UNAUTHENTICATED,
    TIMEOUT,
    ;

    companion object {
        fun fromName(name: String): FirestoreCode = entries.firstOrNull { it.name == name } ?: UNKNOWN
    }
}

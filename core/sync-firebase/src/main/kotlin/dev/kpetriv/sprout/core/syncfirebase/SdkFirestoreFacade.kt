package dev.kpetriv.sprout.core.syncfirebase

import android.content.Context
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.Source
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [FirestoreFacade] over the Firestore SDK. Reads use [Source.SERVER], so an offline device fails fast with
 * `UNAVAILABLE` instead of answering from the (memory-only) cache. Writes are queued by the SDK while offline; the
 * transport bounds how long it waits for them.
 */
@Singleton
class SdkFirestoreFacade @Inject constructor(
    private val bootstrap: FirebaseBootstrap,
    @param:ApplicationContext private val context: Context,
) : FirestoreFacade {
    private val firestore: FirebaseFirestore by lazy {
        FirebaseFirestore.getInstance(bootstrap.ensureInitialised(context))
    }

    override suspend fun commit(writes: List<SetWrite>) = mapped {
        val batch = firestore.batch()
        writes.forEach { write -> batch.set(firestore.document(write.path), toSdk(write.data)) }
        batch.commit().await()
        Unit
    }

    override suspend fun get(path: String): FacadeDocument? = mapped {
        firestore.document(path).get(Source.SERVER).await().takeIf { it.exists() }?.let(::toFacade)
    }

    override suspend fun getAll(path: String): List<FacadeDocument> = mapped {
        firestore.collection(path).get(Source.SERVER).await().documents.map(::toFacade)
    }

    override suspend fun queryByServerTime(path: String, after: QueryPosition?, limit: Int): List<FacadeDocument> =
        mapped {
            var query = firestore.collection(path)
                .orderBy(FirestoreFacade.SERVER_RECEIVED_AT)
                .orderBy(FieldPath.documentId())
            if (after != null) {
                query =
                    query.startAfter(
                        Timestamp(after.serverReceivedAt.seconds, after.serverReceivedAt.nanos),
                        after.documentId,
                    )
            }
            query.limit(limit.toLong()).get(Source.SERVER).await().documents.map(::toFacade)
        }

    override fun listen(path: String, latestOnly: Boolean): Flow<Unit> = callbackFlow {
        val collection = firestore.collection(path)
        val query: Query = if (latestOnly) {
            collection.orderBy(FirestoreFacade.SERVER_RECEIVED_AT, Query.Direction.DESCENDING).limit(1)
        } else {
            collection
        }
        val registration = query.addSnapshotListener(MetadataChanges.EXCLUDE) { snapshot, error ->
            when {
                error != null -> close(FirestoreFailure(FirestoreCode.fromName(error.code.name)))
                snapshot != null && !snapshot.metadata.hasPendingWrites() -> trySend(Unit)
            }
        }
        awaitClose { registration.remove() }
    }

    @Suppress("SwallowedException") // The SDK message can quote document data; only the status code is kept.
    private suspend fun <T> mapped(block: suspend () -> T): T = try {
        block()
    } catch (failure: FirebaseFirestoreException) {
        throw FirestoreFailure(FirestoreCode.fromName(failure.code.name))
    }

    private fun toFacade(snapshot: DocumentSnapshot): FacadeDocument =
        FacadeDocument(snapshot.id, fromSdkMap(snapshot.data.orEmpty()))

    private companion object {
        fun toSdk(data: Map<String, Any?>): Map<String, Any?> = data.mapValues { (_, value) -> toSdkValue(value) }

        fun toSdkValue(value: Any?): Any? = when (value) {
            ServerTimestamp -> FieldValue.serverTimestamp()
            is ServerTime -> Timestamp(value.seconds, value.nanos)
            is Map<*, *> -> value.entries.associate { (key, item) -> key.toString() to toSdkValue(item) }
            is List<*> -> value.map(::toSdkValue)
            else -> value
        }

        fun fromSdkMap(data: Map<String, Any?>): Map<String, Any?> = data.mapValues { (_, value) -> fromSdk(value) }

        fun fromSdk(value: Any?): Any? = when (value) {
            null, is String, is Boolean, is Long, is Double -> value

            is Number -> value.toLong()

            is Timestamp -> ServerTime(value.seconds, value.nanoseconds)

            is Map<*, *> -> value.entries.associate { (key, item) -> key.toString() to fromSdk(item) }

            is List<*> -> value.map(::fromSdk)

            // References, blobs, geo points and vectors never appear in Sprout documents.
            else -> null
        }
    }
}

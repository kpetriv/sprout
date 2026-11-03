package dev.kpetriv.sprout.core.syncfirebase

import android.content.Context
import android.os.Looper
import dev.kpetriv.sprout.core.model.HouseholdId
import dev.kpetriv.sprout.core.sync.DocTypes
import dev.kpetriv.sprout.core.sync.RemoteChange
import dev.kpetriv.sprout.core.sync.SyncCursor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.net.HttpURLConnection
import java.net.URI
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pushes and pulls through the real Firestore and Auth SDKs against a running Firebase Emulator Suite (M3 §5), with
 * the household's rules (`firestore/firestore.rules`) enforced. Opt-in:
 *
 * ```
 * npx --prefix firestore firebase emulators:start --project demo-sprout --only auth,firestore   # JDK 21
 * ./gradlew :core:sync-firebase:testDebugUnitTest -Psprout.firebaseEmulator=true \
 *     --tests '*FirebaseEmulatorTransportTest'
 * ```
 *
 * Seeds (household, membership, device registration: Cloud Functions' job) are written through the emulator's REST
 * API with the `Bearer owner` token, which bypasses the rules.
 */
@RunWith(RobolectricTestRunner::class)
class FirebaseEmulatorTransportTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var context: Context

    @Before
    fun setUp() {
        assumeTrue(
            "set -Psprout.firebaseEmulator=true with the emulators running",
            System.getProperty("sprout.firebaseEmulator") == "true",
        )
        context = RuntimeEnvironment.getApplication()
        http("DELETE", "$FIRESTORE/emulator/v1/projects/$PROJECT/databases/(default)/documents")
        http("DELETE", "$AUTH/emulator/v1/projects/$PROJECT/accounts")
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun pushPullAndListenAgainstTheEmulator() {
        val config = FirebaseConfig(emulatorHost = HOST, debugAppCheck = true)
        val bootstrap = FirebaseBootstrap(config)
        val session = FirebaseAuthSession(bootstrap, config, context)
        blocking { session.signInAnonymously().getOrThrow() }
        val uid = (blocking { session.current() } as AuthState.SignedIn).uid.value
        seedHousehold(uid)

        val transport = FirebaseSyncTransport(
            firestore = SdkFirestoreFacade(bootstrap, context),
            household = { HouseholdId(HID) },
            foreground = MutableStateFlow(true),
            pageSize = 2,
            batchSize = FirebaseSyncTransport.MAX_BATCH_WRITES,
            callTimeoutMs = 20_000,
        )
        assertTrue(blocking { transport.isAvailable() })

        val pushed = pushEverything(transport, uid)
        assertPullsBack(transport, pushed)
        assertChangesSeeRemoteWrite(transport)
    }

    /** Pushes three events and two documents (twice: idempotent), a conflicting event copy and a parent key. */
    private fun pushEverything(transport: FirebaseSyncTransport, uid: String): List<JsonObject> {
        val events = (1..3).map {
            TestData.event("evt-$it", lamport = it.toLong(), hid = HID, uid = uid, device = DEVICE)
        }
        val documents = listOf(
            TestData.document(docType = DocTypes.CHORE, docId = "chore-1", hid = HouseholdId(HID), uid = uid),
            TestData.document(docType = DocTypes.REWARD, docId = "reward-1", hid = HouseholdId(HID), uid = uid),
        )
        val pushed = blocking { transport.push(events, documents) }
        assertEquals(5, pushed.accepted.size, "rejected: ${pushed.rejected}")

        // Pushing again is idempotent: the rules refuse the rewrites, the transport reads the server copies.
        val again = blocking { transport.push(events, documents) }
        assertEquals(5, again.accepted.size, "rejected: ${again.rejected}")

        // A second copy of evt-1 with another hash is kept as evidence; a parent key goes to parentKeys.
        val conflicting = TestData.event("evt-1", hash = TestData.hash(99), hid = HID, uid = uid, device = DEVICE)
        val parentKey = TestData.document(
            docType = DocTypes.PARENT_KEY,
            docId = FINGERPRINT,
            hid = HouseholdId(HID),
            uid = uid,
            payload = buildJsonObject {
                put("publicKey", "AQID")
                put("validFromLamport", 4)
            },
        )
        val extra = blocking { transport.push(listOf(conflicting), listOf(parentKey)) }
        assertEquals(2, extra.accepted.size, "rejected: ${extra.rejected}")
        return events + conflicting
    }

    /** Pulls two per page from the start and checks everything came back unchanged. */
    private fun assertPullsBack(transport: FirebaseSyncTransport, events: List<JsonObject>) {
        val pulledEvents = mutableListOf<JsonObject>()
        val pulledDocuments = mutableListOf<String>()
        val pulledKeys = mutableListOf<String>()
        var cursor = SyncCursor.START
        var pages = 0
        do {
            val page = blocking { transport.pull(cursor) }
            pulledEvents += page.events
            pulledDocuments += page.documents.map { it.docId }
            pulledKeys += page.parentKeys.map { it.fingerprint }
            if (pages == 0) assertEquals(listOf(DEVICE), page.devices.map { it.deviceId.value })
            cursor = page.next
            pages++
        } while (page.hasMore && pages < MAX_PAGES)
        assertEquals(events, pulledEvents, "events come back as the tree that was hashed")
        assertEquals(setOf("chore-1", "reward-1", FINGERPRINT), pulledDocuments.toSet())
        assertEquals(listOf(FINGERPRINT), pulledKeys)
        assertTrue(pages >= 2, "four events at two per page need a second page")
    }

    /** A write from elsewhere (another device, here the REST API) reaches changes(). */
    private fun assertChangesSeeRemoteWrite(transport: FirebaseSyncTransport) {
        val seen = CopyOnWriteArrayList<RemoteChange>()
        val collecting = scope.launch { transport.changes().collect { seen += it } }
        waitUntil { seen.any { it.collection == "events" } } // first snapshot
        seen.clear()
        seed(
            "households/$HID/events?documentId=evt-remote",
            mapOf(
                "eventId" to str("evt-remote"),
                "serverReceivedAt" to mapOf("timestampValue" to Instant.now().plusSeconds(3_600).toString()),
            ),
        )
        waitUntil { RemoteChange("events") in seen }
        collecting.cancel()
    }

    private fun seedHousehold(uid: String) {
        seed("households?documentId=$HID", mapOf("createdBy" to str(uid), "fiatCurrency" to str("EUR")))
        seed("households/$HID/members?documentId=$uid", mapOf("role" to str("PARENT")))
        seed(
            "households/$HID/devices?documentId=$DEVICE",
            mapOf(
                "uid" to str(uid),
                "role" to str("PARENT"),
                "publicKey" to str("AQID"),
                "attestation" to str("NONE"),
                "status" to str("ACTIVE"),
                "lamport" to mapOf("integerValue" to "0"),
            ),
        )
    }

    private fun str(value: String) = mapOf("stringValue" to value)

    /** Creates a document through the emulator's REST API as the owner (rules bypassed). */
    private fun seed(pathAndQuery: String, fields: Map<String, Map<String, String>>) {
        val body = fields.entries.joinToString(",", prefix = "{\"fields\":{", postfix = "}}") { (name, value) ->
            val (type, raw) = value.entries.single()
            "\"$name\":{\"$type\":\"$raw\"}"
        }
        http("POST", "$FIRESTORE/v1/projects/$PROJECT/databases/(default)/documents/$pathAndQuery", body)
    }

    private fun http(method: String, url: String, body: String? = null) {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.setRequestProperty("Authorization", "Bearer owner")
        if (body != null) {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(body.encodeToByteArray()) }
        }
        val status = connection.responseCode
        connection.disconnect()
        check(status in 200..299) { "$method $url -> $status" }
    }

    /** Runs [block] off the main thread while pumping Robolectric's main looper (Firebase posts callbacks to it). */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun <T> blocking(block: suspend () -> T): T {
        val deferred: Deferred<T> = scope.async { withTimeout(TIMEOUT_MS) { block() } }
        while (!deferred.isCompleted) pump()
        return deferred.getCompleted()
    }

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "condition not met in time" }
            pump()
        }
    }

    private fun pump() {
        shadowOf(Looper.getMainLooper()).idle()
        Thread.sleep(POLL_MS)
    }

    private companion object {
        const val HOST = "127.0.0.1"
        const val PROJECT = FirebaseBootstrap.DEMO_PROJECT_ID
        const val FIRESTORE = "http://$HOST:${FirebaseBootstrap.FIRESTORE_EMULATOR_PORT}"
        const val AUTH = "http://$HOST:${FirebaseBootstrap.AUTH_EMULATOR_PORT}"
        const val HID = "h1"
        val DEVICE = "a".repeat(64)
        val FINGERPRINT = "c".repeat(64)
        const val TIMEOUT_MS = 30_000L
        const val POLL_MS = 10L
        const val MAX_PAGES = 10
    }
}

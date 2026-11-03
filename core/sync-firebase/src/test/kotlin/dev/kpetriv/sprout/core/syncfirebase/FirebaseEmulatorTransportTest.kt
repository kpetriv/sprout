package dev.kpetriv.sprout.core.syncfirebase

import android.content.Context
import android.os.Looper
import dev.kpetriv.sprout.core.domainledger.EncryptionKeyBindingPayload
import dev.kpetriv.sprout.core.domainledger.chat.BlobJson
import dev.kpetriv.sprout.core.domainledger.chat.ChatLimits
import dev.kpetriv.sprout.core.domainledger.chat.MessageEnvelope
import dev.kpetriv.sprout.core.domainledger.chat.MessageJson
import dev.kpetriv.sprout.core.domainledger.chat.MessageKind
import dev.kpetriv.sprout.core.model.BlobId
import dev.kpetriv.sprout.core.model.DeviceId
import dev.kpetriv.sprout.core.model.HouseholdId
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.MessageId
import dev.kpetriv.sprout.core.model.Role
import dev.kpetriv.sprout.core.model.ThreadId
import dev.kpetriv.sprout.core.model.UserId
import dev.kpetriv.sprout.core.sync.BlobChunkJson
import dev.kpetriv.sprout.core.sync.DocTypes
import dev.kpetriv.sprout.core.sync.RemoteChange
import dev.kpetriv.sprout.core.sync.SyncCursor
import dev.kpetriv.sprout.core.sync.code
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
import kotlinx.serialization.json.JsonPrimitive
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
import kotlin.io.encoding.Base64
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
 * Seeds (household, membership, device registration: the server's job) are written through the emulator's REST
 * API with the `Bearer owner` token, which bypasses the rules.
 *
 * [callablesThroughTheServer] also needs the Sprout server against the same emulators (`npm --prefix server run dev`)
 * and its URL in `SPROUT_SERVER_URL` (for example `http://127.0.0.1:8787`); without that variable it is skipped.
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
        // Each test bootstraps its own default app (the configs differ); Firebase keeps apps in a static map.
        com.google.firebase.FirebaseApp.clearInstancesForTest()
        clearing { http("DELETE", "$FIRESTORE/emulator/v1/projects/$PROJECT/databases/(default)/documents") }
        clearing { http("DELETE", "$AUTH/emulator/v1/projects/$PROJECT/accounts") }
    }

    /** The emulator sometimes answers a clear right after the previous test's listeners closed with a 500: retry. */
    private fun clearing(block: () -> Unit) {
        repeat(CLEAR_ATTEMPTS - 1) {
            if (runCatching(block).isSuccess) return
            Thread.sleep(CLEAR_RETRY_MS)
        }
        block()
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun pushPullAndListenAgainstTheEmulator() {
        val config = FirebaseConfig(emulatorHost = HOST, debugAppCheck = true)
        val bootstrap = FirebaseBootstrap(config, PortedEmulators)
        val session = FirebaseAuthSession(bootstrap, config, context)
        blocking { session.signInAnonymously().getOrThrow() }
        val uid = (blocking { session.current() } as AuthState.SignedIn).uid.value
        seedHousehold(uid)
        // The claims the backend sets on joining (the transport treats a missing hid claim as "gone").
        http(
            "POST",
            "$AUTH/identitytoolkit.googleapis.com/v1/projects/$PROJECT/accounts:update",
            "{\"localId\":\"$uid\",\"customAttributes\":\"{\\\"hid\\\":\\\"$HID\\\",\\\"role\\\":\\\"PARENT\\\"}\"}",
        )
        assertEquals(HouseholdId(HID), blocking { session.refreshClaims().getOrThrow() }.hid)

        val facade = SdkFirestoreFacade(bootstrap, context)
        val transport = FirebaseSyncTransport(
            firestore = facade,
            // As the app's session scope: the account and device are known, so a refused batch (Firestore limits the
            // documents a batch's rules may read) is retried item by item instead of being treated as unconfirmed.
            household = object : HouseholdScope {
                override suspend fun currentHouseholdId() = HouseholdId(HID)

                override suspend fun currentUserId() = UserId(uid)

                override suspend fun currentDeviceId() = DeviceId(DEVICE)

                override suspend fun currentRole() = Role.PARENT
            },
            foreground = MutableStateFlow(true),
            authProbe = session,
            pageSize = 2,
            batchSize = FirebaseSyncTransport.MAX_BATCH_WRITES,
            callTimeoutMs = 20_000,
        )
        assertTrue(blocking { transport.isAvailable() })

        val pushed = pushEverything(transport, uid)
        val chat = pushChat(transport, facade, uid)
        assertPullsBack(transport, pushed, chat)
        assertChangesSeeRemoteWrite(transport)
    }

    /**
     * Pushes a photo message and its two blob chunks through the rules (bytes fields, create-only, size limits) and
     * reads the blob back by id. Returns the message as pushed.
     */
    private fun pushChat(transport: FirebaseSyncTransport, facade: FirestoreFacade, uid: String): JsonObject {
        val hid = HouseholdId(HID)
        val header = { chunk: Int, size: Int ->
            dev.kpetriv.sprout.core.domainledger.chat.BlobChunkHeader(
                householdId = hid,
                blobId = BlobId("blob-1"),
                threadId = ThreadId("kid-a"),
                kidId = KidId("kid-a"),
                authorUid = UserId(uid),
                authorDeviceId = DeviceId(DEVICE),
                lamport = 5,
                chunk = chunk,
                chunks = 2,
                sha256 = "ab".repeat(32),
                epoch = 0,
                signature = "MEUCIQ-blob-sig",
            ).let { BlobChunkJson(BlobJson.toJsonObject(it), ByteArray(size) { i -> i.toByte() }) }
        }
        val chunks = listOf(header(0, ChatLimits.MAX_BLOB_CHUNK_BYTES), header(1, 10))
        val message = MessageJson.toJsonObject(
            MessageEnvelope(
                householdId = hid,
                threadId = ThreadId("kid-a"),
                messageId = MessageId("msg-1"),
                authorUid = UserId(uid),
                authorDeviceId = DeviceId(DEVICE),
                authorRole = Role.PARENT,
                seq = 1,
                lamport = 6,
                createdAt = kotlin.time.Instant.fromEpochMilliseconds(1_762_000_000_123),
                epoch = 0,
                kind = MessageKind.PHOTO,
                ciphertext = Base64.Default.encode(ByteArray(ChatLimits.MAX_MESSAGE_CIPHERTEXT_BYTES)),
                blobId = BlobId("blob-1"),
                signature = "MEUCIQ-msg-sig",
            ),
        )
        val pushed = blocking { transport.push(emptyList(), emptyList(), listOf(message), chunks) }
        assertEquals(3, pushed.accepted.size, "rejected: ${pushed.rejected}")
        // Each chunk raised the household's daily blob counter by one, in the same batch (audit H2).
        val day = java.time.LocalDate.now(java.time.ZoneOffset.UTC)
            .format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE)
        val quota = blocking { facade.get("households/$HID/quotas/$day") }
        assertEquals(2L, quota?.data?.get("chunks"))
        // Create-only: the same items again are read back and accepted; a changed copy is refused.
        val again = blocking { transport.push(emptyList(), emptyList(), listOf(message), chunks) }
        assertEquals(3, again.accepted.size, "rejected: ${again.rejected}")
        val oversize = JsonObject(
            message + mapOf(
                "messageId" to JsonPrimitive("msg-2"),
                "ciphertext" to JsonPrimitive(
                    Base64.Default.encode(ByteArray(ChatLimits.MAX_MESSAGE_CIPHERTEXT_BYTES + 1)),
                ),
            ),
        )
        val refused = blocking { transport.push(emptyList(), emptyList(), listOf(oversize), emptyList()) }
        assertEquals(listOf("permission-denied"), refused.rejected.map { it.second.code })

        assertEquals(chunks, blocking { transport.fetchBlob(BlobId("blob-1")) }.getOrThrow())
        assertEquals(emptyList(), blocking { transport.fetchBlob(BlobId("nothing")) }.getOrThrow())
        return message
    }

    /**
     * The callables through the real Functions SDK and the token-less transport against the server (D-M3-29): a
     * device proof bound to the ID token the SDK sends ([FirebaseAuthSession.currentIdTokenHash]) is accepted, one
     * over another hash is refused, and `deviceStatus` answers signed in, signed out and after the household is gone.
     */
    @Test
    fun callablesThroughTheServer() {
        val serverUrl = System.getenv("SPROUT_SERVER_URL")
        assumeTrue("set SPROUT_SERVER_URL with the server running", !serverUrl.isNullOrBlank())
        val config = FirebaseConfig(emulatorHost = HOST, debugAppCheck = true, functionsBaseUrl = serverUrl)
        val bootstrap = FirebaseBootstrap(config, PortedEmulators)
        val session = FirebaseAuthSession(bootstrap, config, context)
        val functions = HouseholdFunctions(FirebaseCallableInvoker(bootstrap, config, context))
        val keys = java.security.KeyPairGenerator.getInstance("EC")
            .apply { initialize(java.security.spec.ECGenParameterSpec("secp256r1")) }
            .generateKeyPair()
        val sign = { payload: ByteArray ->
            java.security.Signature.getInstance("SHA256withECDSA").run {
                initSign(keys.private)
                update(payload)
                sign()
            }
        }
        blocking { session.signInWithEmailPassword("parent@example.test", "emulator-only-password").getOrThrow() }
        val created = blocking {
            functions.createHousehold("EUR", DeviceKeyRegistration(keys.public.encoded, null)).getOrThrow()
        }
        assertEquals(created.hid, blocking { session.refreshClaims().getOrThrow() }.hid)

        // A proof over the hash of the token the SDK sends is accepted; one over any other hash is refused.
        val tokenHash = blocking { session.currentIdTokenHash() }
        val exportSignature = sign(DeviceProof.exportHouseholdPayload(created.hid, tokenHash))
        val export = blocking { functions.exportHousehold(created.deviceId, exportSignature) }
        assertTrue(export.isSuccess, "export: ${export.exceptionOrNull()}")
        val stale = blocking {
            functions.exportHousehold(
                created.deviceId,
                sign(DeviceProof.exportHouseholdPayload(created.hid, ByteArray(DeviceProof.ID_TOKEN_HASH_BYTES))),
            )
        }
        assertEquals(FunctionsError.PermissionDenied, (stale.exceptionOrNull() as FunctionsException).error)

        // deviceStatus: signed in, signed out, with a bad signature, for an unknown device.
        val statusSignature = sign(DeviceProof.deviceStatusPayload(created.hid, created.deviceId))
        val status = { blocking { functions.deviceStatus(created.hid, created.deviceId, statusSignature) } }
        assertEquals(DeviceStatusAnswer(DeviceStatusValue.ACTIVE, householdGone = false), status().getOrThrow())
        com.google.firebase.auth.FirebaseAuth.getInstance(bootstrap.ensureInitialised(context)).signOut()
        assertEquals(DeviceStatusAnswer(DeviceStatusValue.ACTIVE, householdGone = false), status().getOrThrow())
        val forged = blocking { functions.deviceStatus(created.hid, created.deviceId, sign(byteArrayOf(1))) }
        assertEquals(FunctionsError.PermissionDenied, (forged.exceptionOrNull() as FunctionsException).error)
        val unknown = blocking {
            functions.deviceStatus(created.hid, DeviceId("d".repeat(64)), sign(byteArrayOf(1))).getOrThrow()
        }
        assertEquals(DeviceStatusAnswer(DeviceStatusValue.UNKNOWN, householdGone = false), unknown)

        blocking { session.signInWithEmailPassword("parent@example.test", "emulator-only-password").getOrThrow() }
        assertRegistersEncryptionKey(session, functions, created, sign)

        // Deleted (a fresh sign-in, as the server requires): the device learns the household is gone.
        blocking { session.signInWithEmailPassword("parent@example.test", "emulator-only-password").getOrThrow() }
        val deleteHash = blocking { session.currentIdTokenHash() }
        val deleted = blocking {
            functions.deleteHousehold(
                created.hid,
                created.deviceId,
                sign(DeviceProof.deleteHouseholdPayload(created.hid, deleteHash)),
            )
        }
        assertTrue(deleted.isSuccess, "delete: ${deleted.exceptionOrNull()}")
        assertEquals(DeviceStatusAnswer(DeviceStatusValue.UNKNOWN, householdGone = true), status().getOrThrow())
    }

    /** registerEncryptionKey: the binding and a proof bound to the token the SDK sends; idempotent. */
    private fun assertRegistersEncryptionKey(
        session: FirebaseAuthSession,
        functions: HouseholdFunctions,
        created: CreatedHousehold,
        sign: (ByteArray) -> ByteArray,
    ) {
        val encryptionKey = ByteArray(EncryptionKeyBindingPayload.KEY_SIZE) { 3 }
        val binding = sign(EncryptionKeyBindingPayload.of(created.hid, created.deviceId, encryptionKey))
        val registerKey = {
            val hash = blocking { session.currentIdTokenHash() }
            val proof = sign(DeviceProof.registerEncryptionKeyPayload(created.hid, hash, encryptionKey))
            blocking { functions.registerEncryptionKey(encryptionKey, binding, created.deviceId, proof) }
        }
        assertEquals(RegisteredEncryptionKey(created.deviceId, created = true), registerKey().getOrThrow())
        assertEquals(RegisteredEncryptionKey(created.deviceId, created = false), registerKey().getOrThrow())
    }

    /**
     * Pushes three events and two documents (twice: idempotent) and a conflicting event copy. A parent key is never
     * pushed (the rules deny it, D-M3-34): it is seeded as the server would write it.
     */
    private fun pushEverything(transport: FirebaseSyncTransport, uid: String): List<JsonObject> {
        val events = (1..3).map {
            TestData.event("evt-$it", lamport = it.toLong(), hid = HID, uid = uid, device = DEVICE)
        }
        // Payloads within the rules' closed key sets for their collection.
        val documents = listOf(
            TestData.document(
                docType = DocTypes.CHORE,
                docId = "chore-1",
                hid = HouseholdId(HID),
                uid = uid,
                payload = buildJsonObject {
                    put("id", "chore-1")
                    put("title", "Feed the cat")
                },
            ),
            TestData.document(
                docType = DocTypes.REWARD,
                docId = "reward-1",
                hid = HouseholdId(HID),
                uid = uid,
                payload = buildJsonObject {
                    put("id", "reward-1")
                    put("title", "Treat")
                    put("cost", 100)
                },
            ),
        )
        val pushed = blocking { transport.push(events, documents) }
        assertEquals(5, pushed.accepted.size, "rejected: ${pushed.rejected}")

        // Pushing again is idempotent: the rules refuse the rewrites, the transport reads the server copies.
        val again = blocking { transport.push(events, documents) }
        assertEquals(5, again.accepted.size, "rejected: ${again.rejected}")

        // A second copy of evt-1 with another hash is kept as evidence (from a parent member).
        val conflicting = TestData.event("evt-1", hash = TestData.hash(99), hid = HID, uid = uid, device = DEVICE)
        val extra = blocking { transport.push(listOf(conflicting), emptyList()) }
        assertEquals(1, extra.accepted.size, "rejected: ${extra.rejected}")

        // A parent_key document is refused unsent; the server-written key is seeded instead.
        val parentKey = TestData.parentKeyDocument(FINGERPRINT).copy(householdId = HouseholdId(HID))
        val refused = blocking { transport.push(emptyList(), listOf(parentKey)) }
        assertEquals(listOf(FirebaseSyncTransport.UNKNOWN_DOC_TYPE), refused.rejected.map { it.second.code })
        seed(
            "households/$HID/parentKeys?documentId=$FINGERPRINT",
            mapOf(
                "publicKey" to str("AQID"),
                "uid" to str(uid),
                "deviceId" to str(DEVICE),
                "validFromLamport" to mapOf("integerValue" to "4"),
                "attestation" to str("NONE"),
            ),
        )
        return events + conflicting
    }

    /** Pulls two per page from the start and checks everything came back unchanged. */
    private fun assertPullsBack(transport: FirebaseSyncTransport, events: List<JsonObject>, message: JsonObject) {
        val pulledEvents = mutableListOf<JsonObject>()
        val pulledMessages = mutableListOf<JsonObject>()
        val pulledDocuments = mutableListOf<String>()
        val pulledKeys = mutableListOf<String>()
        var cursor = SyncCursor.START
        var pages = 0
        do {
            val page = blocking { transport.pull(cursor) }
            pulledEvents += page.events
            pulledMessages += page.messages
            pulledDocuments += page.documents.map { it.docId }
            pulledKeys += page.parentKeys.map { it.fingerprint }
            if (pages == 0) assertEquals(listOf(DEVICE), page.devices.map { it.deviceId.value })
            cursor = page.next
            pages++
        } while (page.hasMore && pages < MAX_PAGES)
        assertEquals(events, pulledEvents, "events come back as the tree that was hashed")
        assertEquals(listOf(message), pulledMessages, "messages come back as the tree that was signed")
        assertEquals(setOf("chore-1", "reward-1"), pulledDocuments.toSet())
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
        // Blob chunks must name a kid the household has (the rules' exists(kids/<kidId>)).
        seed("households/$HID/kids?documentId=kid-a", mapOf("docId" to str("kid-a")))
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
        val error = if (status in 200..299) "" else connection.errorStream?.use { it.readBytes().decodeToString() }
        connection.disconnect()
        check(status in 200..299) { "$method $url -> $status $error" }
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

    /**
     * The debug [Emulators] wiring on the ports in `SPROUT_EMULATOR_FIRESTORE_PORT` / `SPROUT_EMULATOR_AUTH_PORT`
     * (default: the standard ports), so the suite can run against a second Emulator Suite next to one already using
     * the standard ports.
     */
    private object PortedEmulators : EmulatorSupport by Emulators {
        override fun connect(app: com.google.firebase.FirebaseApp, config: FirebaseConfig) {
            val host = config.emulatorHost ?: return
            com.google.firebase.auth.FirebaseAuth.getInstance(app).useEmulator(host, AUTH_PORT)
            com.google.firebase.firestore.FirebaseFirestore.getInstance(app).useEmulator(host, FIRESTORE_PORT)
        }
    }

    private companion object {
        const val HOST = "127.0.0.1"
        const val PROJECT = FirebaseBootstrap.DEMO_PROJECT_ID
        val FIRESTORE_PORT: Int =
            System.getenv("SPROUT_EMULATOR_FIRESTORE_PORT")?.toIntOrNull() ?: FirebaseBootstrap.FIRESTORE_EMULATOR_PORT
        val AUTH_PORT: Int =
            System.getenv("SPROUT_EMULATOR_AUTH_PORT")?.toIntOrNull() ?: FirebaseBootstrap.AUTH_EMULATOR_PORT
        val FIRESTORE = "http://$HOST:$FIRESTORE_PORT"
        val AUTH = "http://$HOST:$AUTH_PORT"
        const val HID = "h1"

        /** The SHA-256 of the device key AQID (bytes 1, 2, 3), as the server derives a device id. */
        val DEVICE = TestData.DEVICE
        val FINGERPRINT = "c".repeat(64)
        const val TIMEOUT_MS = 30_000L
        const val POLL_MS = 10L
        const val MAX_PAGES = 10
        const val CLEAR_ATTEMPTS = 5
        const val CLEAR_RETRY_MS = 500L
    }
}

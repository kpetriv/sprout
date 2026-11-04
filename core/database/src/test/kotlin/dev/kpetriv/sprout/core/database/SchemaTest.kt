package dev.kpetriv.sprout.core.database

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Checks the exported Room schema (`core/database/schemas`), which Gradle runs tests next to. */
class SchemaTest {
    private fun schemaFile(version: Int) = File("schemas/${SproutDatabase::class.java.name}/$version.json")

    private val schemaFile = schemaFile(SproutDatabase.VERSION)

    private fun schema(file: File = schemaFile): JsonObject = Json.parseToJsonElement(file.readText()).jsonObject

    private fun entities(file: File = schemaFile): List<JsonObject> =
        schema(file).getValue("database").jsonObject.getValue("entities").jsonArray.map { it.jsonObject }

    private fun table(name: String) = entities().single { it.getValue("tableName").jsonPrimitive.content == name }

    /** Each index of [table] as (columns, unique). */
    private fun indices(table: String): Set<Pair<List<String>, Boolean>> =
        table(table).getValue("indices").jsonArray.map { index ->
            val json = index.jsonObject
            json.getValue("columnNames").jsonArray.map { it.jsonPrimitive.content } to
                json.getValue("unique").jsonPrimitive.content.toBoolean()
        }.toSet()

    private fun columns(table: String): Set<String> =
        table(table).getValue("fields").jsonArray.map { it.jsonObject.getValue("columnName").jsonPrimitive.content }
            .toSet()

    @Test
    fun schemaForTheCurrentVersionIsExported() {
        assertTrue(schemaFile.isFile, "missing exported schema $schemaFile")
        val database = schema().getValue("database").jsonObject
        assertEquals(SproutDatabase.VERSION, database.getValue("version").jsonPrimitive.content.toInt())
        assertEquals(
            setOf(
                "ledger_events",
                "accounts",
                "kids",
                "balances",
                "anomalies",
                "outbox",
                "audit_log",
                "device_state",
                "chores",
                "chore_submissions",
                "rewards",
                "allowance_schedules",
                "savings_goals",
                "interest_policy",
                "pending_parent_actions",
                "photos",
                "household_policy",
                "devices",
                "parent_keys",
                "ledger_inbox",
                "document_inbox",
                "sync_cursor",
                "session",
                "messages",
                "message_seq",
                "thread_keys",
                "blobs",
                "read_marks",
                "time_bank_clocks",
            ),
            entities().map { it.getValue("tableName").jsonPrimitive.content }.toSet(),
        )
    }

    @Test
    fun noColumnOfAnyVersionIsFloatingPoint() {
        for (version in 1..SproutDatabase.VERSION) {
            val file = schemaFile(version)
            assertTrue(file.isFile, "missing exported schema $file")
            val floating = entities(file).flatMap { entity ->
                val table = entity.getValue("tableName").jsonPrimitive.content
                entity.getValue("fields").jsonArray.map { it.jsonObject }
                    .filter { it.getValue("affinity").jsonPrimitive.content.uppercase() in FLOATING_AFFINITIES }
                    .map { "$table.${it.getValue("columnName").jsonPrimitive.content}" }
            }
            assertEquals(emptyList(), floating, "schema version $version")
        }
    }

    @Test
    fun householdTablesHaveTheirUniqueKeys() {
        assertTrue(listOf("choreId", "dueOn") to true in indices("chore_submissions"))
        assertTrue(listOf("key") to true in indices("pending_parent_actions"))
        assertEquals(
            setOf("fileName", "sha256", "kidId", "submissionId", "createdAtEpochMs", "blobId"),
            columns("photos"),
        )
        assertEquals(
            setOf(
                "id",
                "requireParentSignature",
                "parentSignatureFromLamport",
                "parentPublicKey",
                "fiatCurrency",
                "createdAtEpochMs",
                "lamport",
                "authorUid",
                "authorDeviceId",
                "signature",
                "deleted",
                "chatRetentionDays",
                "dailyGameCapMinor",
            ),
            columns("household_policy"),
        )
        val stamp = setOf("authorUid", "authorDeviceId", "signature", "deleted")
        for (table in listOf("chores", "chore_submissions", "rewards", "allowance_schedules", "savings_goals")) {
            assertTrue(stamp.all { it in columns(table) }, table)
        }
        assertTrue((stamp + "lamport").all { it in columns("interest_policy") })
        assertTrue((stamp + "lamport").all { it in columns("kids") && it in columns("accounts") })
        assertTrue(setOf("quarantineReason", "ingestedAtEpochMs").all { it in columns("ledger_events") })
        assertTrue("appliedCopyJson" in columns("anomalies"))
        assertTrue("parentSignature" in columns("ledger_events"))
        assertTrue(setOf("docType", "docId", "lamport", "data").all { it in columns("outbox") })
    }

    @Test
    fun chatTablesHaveTheirKeysAndIndices() {
        assertTrue(listOf("threadId", "lamport") to false in indices("messages"))
        assertTrue(listOf("authorDeviceId", "threadId", "seq") to false in indices("messages"))
        assertTrue(setOf("plaintext", "status", "reason", "receivedAtEpochMs").all { it in columns("messages") })
        assertEquals(
            setOf("threadId", "authorDeviceId", "nextSeq", "lastLamport", "lastDisplayedAtEpochMs", "skippedRanges"),
            columns("message_seq"),
        )
        assertTrue(setOf("displayedAtEpochMs", "late").all { it in columns("messages") })
        assertTrue(setOf("confirmed", "olderKeys").all { it in columns("thread_keys") })
        assertTrue("encryptionKeyConflict" in columns("devices"))
        val threadKeyColumns = columns("thread_keys")
        val keyColumns = setOf("key", "wrapSetJson", "sourceDocId", "createdByDeviceId")
        assertTrue(keyColumns.all { it in threadKeyColumns })
        assertTrue(setOf("fileName", "sha256", "size", "lastUsedEpochMs").all { it in columns("blobs") })
        val deviceColumns = columns("devices")
        val keyBinding = setOf("encryptionKey", "encryptionKeySignature", "encryptionKeyHouseholdId")
        assertTrue(keyBinding.all { it in deviceColumns })
    }

    @Test
    fun ledgerEventIndicesExist() {
        val ledger = entities().single { it.getValue("tableName").jsonPrimitive.content == "ledger_events" }
        val indexed = ledger.getValue("indices").jsonArray.map { index ->
            index.jsonObject.getValue("columnNames").jsonArray.map { it.jsonPrimitive.content }
        }.toSet()
        assertEquals(setOf(listOf("kidId", "lamport"), listOf("authorDeviceId", "lamport"), listOf("hash")), indexed)
    }

    private companion object {
        val FLOATING_AFFINITIES = setOf("REAL", "DOUBLE", "FLOAT")
    }
}

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
    private val schemaFile = File("schemas/${SproutDatabase::class.java.name}/${SproutDatabase.VERSION}.json")

    private fun schema(): JsonObject = Json.parseToJsonElement(schemaFile.readText()).jsonObject

    private fun entities(): List<JsonObject> =
        schema().getValue("database").jsonObject.getValue("entities").jsonArray.map { it.jsonObject }

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
            ),
            entities().map { it.getValue("tableName").jsonPrimitive.content }.toSet(),
        )
    }

    @Test
    fun noColumnIsFloatingPoint() {
        val floating = entities().flatMap { entity ->
            val table = entity.getValue("tableName").jsonPrimitive.content
            entity.getValue("fields").jsonArray.map { it.jsonObject }
                .filter { it.getValue("affinity").jsonPrimitive.content.uppercase() in FLOATING_AFFINITIES }
                .map { "$table.${it.getValue("columnName").jsonPrimitive.content}" }
        }
        assertEquals(emptyList(), floating)
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

package dev.kpetriv.sprout.core.database.entity

import kotlinx.serialization.json.Json

/**
 * JSON for document columns and outbox payloads: polymorphic `"type"` discriminator, defaults written, unknown keys
 * ignored (so a payload written by a newer schema still reads).
 */
internal object DocumentJson {
    val json: Json = Json {
        classDiscriminator = "type"
        encodeDefaults = true
        ignoreUnknownKeys = true
    }
}

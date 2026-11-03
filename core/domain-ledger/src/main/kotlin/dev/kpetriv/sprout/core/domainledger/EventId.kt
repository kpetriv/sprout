package dev.kpetriv.sprout.core.domainledger

import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

/**
 * A ledger event's identity and idempotency key: a UUID in canonical lower-case 8-4-4-4-12 form.
 * Applying an event whose id was already applied is a no-op.
 */
@Serializable
@JvmInline
value class EventId(val value: String) : Comparable<EventId> {
    init {
        require(UUID_PATTERN.matches(value)) { "EventId must be a lower-case canonical UUID" }
    }

    override fun compareTo(other: EventId): Int = value.compareTo(other.value)

    override fun toString(): String = value

    private companion object {
        val UUID_PATTERN = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    }
}

/** Source of fresh [EventId]s. */
fun interface EventIdGenerator {
    fun next(): EventId
}

/** Random (version 4, RFC 9562) UUIDs from a cryptographically secure source. */
class UuidV4EventIdGenerator(private val random: RandomBytes = JvmSecureRandomBytes) : EventIdGenerator {
    override fun next(): EventId {
        val bytes = random.nextBytes(UUID_BYTES)
        bytes[VERSION_INDEX] = ((bytes[VERSION_INDEX].toInt() and LOW_NIBBLE) or VERSION_4).toByte()
        bytes[VARIANT_INDEX] = ((bytes[VARIANT_INDEX].toInt() and VARIANT_MASK) or VARIANT_RFC).toByte()
        val hex = bytes.toHex()
        var start = 0
        val groups = GROUP_LENGTHS.map { length -> hex.substring(start, start + length).also { start += length } }
        return EventId(groups.joinToString("-"))
    }

    private companion object {
        const val UUID_BYTES = 16
        const val VERSION_INDEX = 6
        const val VARIANT_INDEX = 8
        const val LOW_NIBBLE = 0x0F
        const val VERSION_4 = 0x40
        const val VARIANT_MASK = 0x3F
        const val VARIANT_RFC = 0x80

        /** Hex digits per dash-separated group: 8-4-4-4-12. */
        val GROUP_LENGTHS = listOf(8, 4, 4, 4, 12)
    }
}

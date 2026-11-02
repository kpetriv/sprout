package dev.kpetriv.sprout.core.sync

/**
 * Moves outbox changes to the backend and remote changes back. The sync engine only talks to this
 * interface; the Firebase implementation lives in core/sync-firebase and tests use a fake.
 */
interface SyncTransport {
    /** Whether the transport can currently reach its backend. */
    suspend fun isAvailable(): Boolean
}

package dev.kpetriv.sprout.core.syncfirebase

import dev.kpetriv.sprout.core.sync.SyncTransport

/** Firestore-backed [SyncTransport]. Reports unavailable until the Firebase SDKs are wired in M3. */
internal class FirebaseSyncTransport : SyncTransport {
    override suspend fun isAvailable(): Boolean = false
}

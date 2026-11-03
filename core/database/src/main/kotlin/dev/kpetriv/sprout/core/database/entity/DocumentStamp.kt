package dev.kpetriv.sprout.core.database.entity

import androidx.room.ColumnInfo
import dev.kpetriv.sprout.core.model.DeviceId
import dev.kpetriv.sprout.core.model.UserId

/**
 * The sync columns every synced document row carries (schema version 3, M3 §3), embedded without a prefix: who wrote
 * the row's current state, the device signature of its `DocumentEnvelope` and whether it is a tombstone.
 *
 * For a local write the signature is this device's over the envelope it queued; for a remote document it is the
 * author's, and the row was only written after `RemoteDocumentVerifier` checked it against the registry. Rows written
 * before version 3 have no stamp (all null).
 *
 * @property signature base64 ECDSA signature over the document envelope's signed payload.
 * @property deleted a tombstone: the row stays (so a late update cannot resurrect it) but every read hides it.
 */
data class DocumentStamp(
    val authorUid: UserId? = null,
    val authorDeviceId: DeviceId? = null,
    val signature: String? = null,
    @ColumnInfo(defaultValue = "0") val deleted: Boolean = false,
)

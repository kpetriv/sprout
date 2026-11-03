package dev.kpetriv.sprout.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.PhotoRef
import dev.kpetriv.sprout.core.model.SubmissionId

/**
 * A chore photo stored on this device (D-M2-3: local only in M2). The bytes are not here: they live encrypted in
 * `noBackupFilesDir/photos/<fileName>` (see `EncryptedPhotoStore`). Device-local, never synced.
 *
 * @property sha256 lower-case hex SHA-256 of the plaintext, checked on every load.
 * @property submissionId the submission the photo is attached to, once submitted.
 */
@Entity(tableName = "photos", indices = [Index(value = ["kidId"]), Index(value = ["submissionId"])])
data class PhotoEntity(
    @PrimaryKey val fileName: String,
    val sha256: String,
    val kidId: KidId,
    val submissionId: SubmissionId?,
    val createdAtEpochMs: Long,
) {
    fun toRef(): PhotoRef = PhotoRef(fileName, sha256)
}

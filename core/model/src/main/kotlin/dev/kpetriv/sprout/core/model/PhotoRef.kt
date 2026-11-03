package dev.kpetriv.sprout.core.model

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

private val SHA256_HEX = Regex("[0-9a-f]{64}")

/** Longest [PhotoRef.blobId] (the Firestore rules bound it to 64 characters; a blob id is a UUID). */
const val MAX_PHOTO_BLOB_ID_LENGTH: Int = 64

/**
 * A photo attached to a chore submission: the bare [fileName] of the encrypted file in the app's private photo
 * store (never a path, so it cannot point elsewhere) and the lower-case hex [sha256] of its plaintext, which lets
 * a receiver check the bytes it decrypts. The file name follows the id alphabet (see [ID_PATTERN]) and cannot be
 * `.` or `..`.
 *
 * [blobId] is set once the kid's device has uploaded the photo as an end-to-end encrypted blob (M4 §2, D-M4-5): the
 * parent's device fetches and decrypts it from there; while it is null the photo exists only on the kid's device.
 * A null [blobId] is left out of the serialised form, so references written before M4 encode exactly as before.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class PhotoRef(
    val fileName: String,
    val sha256: String,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val blobId: String? = null,
) {
    init {
        require(ID_PATTERN.matches(fileName) && fileName != "." && fileName != "..") {
            "fileName must be a bare file name of 1 to $MAX_ID_LENGTH characters from [A-Za-z0-9._-]"
        }
        require(SHA256_HEX.matches(sha256)) { "sha256 must be 64 lower-case hex characters" }
        require(blobId == null || (ID_PATTERN.matches(blobId) && blobId.length <= MAX_PHOTO_BLOB_ID_LENGTH)) {
            "blobId must be 1 to $MAX_PHOTO_BLOB_ID_LENGTH characters from [A-Za-z0-9._-]"
        }
    }
}
